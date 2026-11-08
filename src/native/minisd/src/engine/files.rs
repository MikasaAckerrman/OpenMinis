//! [PARALLEL_TOOL_ENGINE_SPEC Опора 2] The macro-tool `multi_file_read`:
//! ONE model call fans out into up to 16 parallel file reads under the
//! I/O semaphore — the zero-cognitive-load read multiplier. A weak model
//! should not have to know ten file paths ten times; it names them once
//! and the engine does the fan-out.
//!
//! Path contract (flagged for the audit): the daemon runs HOST-side,
//! the model names GUEST paths. Resolution:
//!  - relative path → joined under `ctx.workspace` (the host dir of the
//!    session workspace bind, passed as `--workspace` at M-R3 wiring);
//!  - guest-absolute `/var/minis/workspace/...` → the prefix is stripped,
//!    the rest joined under the same root;
//!  - any other absolute path → refused (outside the workspace);
//!  - `..` never survives normalization, and an EXISTING candidate is
//!    canonicalize-checked against the root (symlink escape refused).

use crate::telegram::tools::{ToolCtx, ToolError};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Semaphore;

/// The accepted path count; the I/O pool still throttles concurrency to
/// 16 — the count cap protects the response size, not the pool.
pub const MAX_PATHS: usize = 64;
/// The fan-out width of one multi_file_read call.
pub const IO_FANOUT: usize = 16;
pub const DEFAULT_MAX_BYTES_PER_FILE: u64 = 65_536;
pub const MAX_BYTES_PER_FILE: u64 = 1_048_576;
/// The guest-side mount point of the session workspace.
pub const GUEST_WORKSPACE_PREFIX: &str = "/var/minis/workspace";

/// Truncate a byte buffer at `max` bytes without splitting a UTF-8
/// char: the cut walks back to a char boundary. Returns the slice and
/// whether truncation happened.
pub fn truncate_utf8(bytes: &[u8], max: usize) -> (&[u8], bool) {
    if bytes.len() <= max {
        return (bytes, false);
    }
    let mut cut = max;
    while cut > 0 && (bytes[cut] & 0b1100_0000) == 0b1000_0000 {
        cut -= 1;
    }
    (&bytes[..cut], true)
}

/// The host path for one model-supplied path, under the workspace root.
fn resolve_workspace_path(ctx: &ToolCtx, raw: &str) -> Result<PathBuf, String> {
    let root: &Path = ctx.workspace.as_ref().ok_or_else(|| {
        "minisd error: workspace root is not configured (pass --workspace)".to_string()
    })?;
    let normalized = crate::engine::keys::normalize_resource_path(raw);
    if normalized.is_empty() {
        return Err("minisd error: empty path".into());
    }
    let rel: String = if normalized.starts_with(GUEST_WORKSPACE_PREFIX) {
        normalized[GUEST_WORKSPACE_PREFIX.len()..]
            .trim_start_matches('/')
            .to_string()
    } else if normalized.starts_with('/') {
        return Err(format!(
            "minisd error: path '{raw}' is outside the workspace"
        ));
    } else {
        normalized
    };
    if rel.is_empty() {
        return Err(format!(
            "minisd error: path '{raw}' resolves to the workspace root itself"
        ));
    }
    if rel.starts_with("..") || rel.split('/').any(|p| p == "..") {
        return Err(format!(
            "minisd error: path '{raw}' escapes the workspace"
        ));
    }
    let candidate = root.join(&rel);
    // Belt and braces: an EXISTING file (or symlink target) must
    // canonicalize inside the root — a symlink pointing at /etc would
    // otherwise pass the lexical check.
    if let Ok(canon) = candidate.canonicalize() {
        let canon_root = root
            .canonicalize()
            .map_err(|e| format!("minisd error: workspace root unreadable: {e}"))?;
        if !canon.starts_with(&canon_root) {
            return Err(format!(
                "minisd error: path '{raw}' escapes the workspace"
            ));
        }
    }
    Ok(candidate)
}

/// The tool handler. Missing/foreign-encoding files degrade PER FILE —
/// one bad path never fails the batch (the same law as tg_scan_channels).
pub async fn multi_file_read(ctx: &ToolCtx, params: &Value) -> Result<Value, ToolError> {
    if ctx.workspace.is_none() {
        return Err(ToolError(
            "minisd error: workspace root is not configured (pass --workspace)".to_string(),
        ));
    }
    let paths: Vec<String> = params
        .get("paths")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
        .unwrap_or_default();
    if paths.is_empty() {
        return Err(ToolError(
            "missing required parameters: paths".to_string(),
        ));
    }
    if paths.len() > MAX_PATHS {
        return Err(ToolError(format!(
            "minisd error: multi_file_read accepts at most {MAX_PATHS} paths"
        )));
    }
    let max_bytes = params
        .get("max_bytes_per_file")
        .and_then(Value::as_u64)
        .unwrap_or(DEFAULT_MAX_BYTES_PER_FILE)
        .clamp(1, MAX_BYTES_PER_FILE) as usize;

    let sem = Arc::new(Semaphore::new(IO_FANOUT));
    let mut set = tokio::task::JoinSet::new();
    for raw in paths {
        let sem = sem.clone();
        let resolved = resolve_workspace_path(ctx, &raw);
        set.spawn(async move {
            let path = match resolved {
                Ok(p) => p,
                Err(e) => return Err(json!({ "path": raw, "error": e })),
            };
            let _permit = sem.acquire_owned().await.map_err(|e| {
                json!({ "path": raw, "error": format!("io pool closed: {e}") })
            })?;
            match tokio::fs::read(&path).await {
                Ok(bytes) => {
                    let size_bytes = bytes.len();
                    let (slice, truncated) = truncate_utf8(&bytes, max_bytes);
                    match std::str::from_utf8(slice) {
                        Ok(text) => Ok(json!({
                            "path": raw,
                            "size_bytes": size_bytes,
                            "truncated": truncated,
                            "content": text,
                        })),
                        Err(_) => Err(json!({
                            "path": raw,
                            "size_bytes": size_bytes,
                            "error": "not valid UTF-8 (binary file?)",
                        })),
                    }
                }
                Err(e) => Err(json!({ "path": raw, "error": format!("read failed: {e}") })),
            }
        });
    }

    let mut files = Vec::new();
    let mut errors = Vec::new();
    while let Some(joined) = set.join_next().await {
        match joined {
            Ok(Ok(v)) => files.push(v),
            Ok(Err(e)) => errors.push(e),
            Err(e) => errors.push(json!({ "error": format!("task failed: {e}") })),
        }
    }
    let requested = files.len() + errors.len();
    Ok(json!({
        "files": files,
        "errors": errors,
        "count": files.len(),
        "requested": requested,
    }))
}

// ── [T-native-grep-glob] Native filesystem search ──
// grep/glob live HERE so the walk pays zero PRoot ptrace tax: the old shell
// pipeline ran every stat/read 3.3x slower under the tracer (vc152 live
// log, bg_check wall stretch). Plain std::fs walk — and deliberately NO
// regex crate: Cargo.toml keeps the cross-compiled ELF lean ("No regex
// chrono dep: link"), so grep is a substring matcher (+ ignore_case) and
// glob is a hand-rolled backtracking matcher; a real regex engine is a
// separate, explicitly-sanctioned dependency decision.

pub const GREP_MAX_RESULTS: usize = 200;
pub const GREP_MAX_FILE_BYTES: u64 = 2_097_152; // 2 MiB — archives/logs skip
pub const WALK_MAX_DEPTH: usize = 12;
pub const LINE_DISPLAY_MAX: usize = 240;
pub const GLOB_DEFAULT_LIMIT: usize = 200;

/// Directory names the walker never descends into — VCS, build outputs and
/// package caches are noise for code search and would dominate the walk.
const PRUNED_DIRS: &[&str] = &[
    ".git", ".gradle", ".m2", ".idea", ".kotlin",
    "target", "node_modules", "build", "obj", "bin", "__pycache__",
];

/// The root of a search tree. Unlike [resolve_workspace_path] the workspace
/// root ITSELF is a valid target ("search everything"), so an empty or
/// guest-prefixed-only path resolves to the root; anything else reuses the
/// strict per-path guards (no "..", canonicalize inside the root).
fn resolve_search_root(ctx: &ToolCtx, raw: &str) -> Result<PathBuf, String> {
    let normalized = crate::engine::keys::normalize_resource_path(raw);
    let stripped = normalized
        .strip_prefix(GUEST_WORKSPACE_PREFIX)
        .map(|s| s.trim_start_matches('/'))
        .unwrap_or(normalized.as_str());
    if stripped.is_empty() {
        return ctx.workspace.as_ref().map(|w| w.clone()).ok_or_else(|| {
            "minisd error: workspace root is not configured (pass --workspace)".to_string()
        });
    }
    resolve_workspace_path(ctx, raw)
}

/// Depth-capped, prune-aware file walker (iterative — no recursion bombs).
fn walk_files(root: &Path, out: &mut Vec<PathBuf>) {
    let mut stack = vec![(root.to_path_buf(), 0usize)];
    while let Some((dir, depth)) = stack.pop() {
        let entries = match std::fs::read_dir(&dir) {
            Ok(e) => e,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let Ok(ft) = entry.file_type() else { continue };
            if ft.is_dir() {
                if depth >= WALK_MAX_DEPTH || PRUNED_DIRS.contains(&name.as_str()) {
                    continue;
                }
                stack.push((path, depth + 1));
            } else if ft.is_file() {
                out.push(path);
            }
        }
    }
}

struct GrepOutcome {
    matches: Vec<Value>,
    files_matched: usize,
    files_scanned: usize,
    truncated: bool,
}

fn grep_sync(ws_root: &Path, search_root: &Path, needle: &str, ignore_case: bool) -> GrepOutcome {
    // Walk the SEARCH root; report paths relative to the WORKSPACE root —
    // a scoped search ("path":"src") must still return "src/b.rs", or the
    // follow-up file_read("b.rs") would miss (reviewer, CI 42488108776).
    let needle_lower = needle.to_lowercase();
    let mut files = Vec::new();
    walk_files(search_root, &mut files);
    // Deterministic output: sort so the model sees a stable order across
    // identical queries (readdir order is arbitrary).
    files.sort();
    let mut matches = Vec::new();
    let mut files_matched = 0usize;
    let mut files_scanned = 0usize;
    let mut truncated = false;
    for path in files {
        if matches.len() >= GREP_MAX_RESULTS {
            truncated = true;
            break;
        }
        let Ok(meta) = std::fs::metadata(&path) else { continue };
        if meta.len() > GREP_MAX_FILE_BYTES {
            continue;
        }
        let Ok(bytes) = std::fs::read(&path) else { continue };
        // Binary probe: a NUL byte in the first 8 KiB is not a text file.
        if bytes[..bytes.len().min(8192)].contains(&0) {
            continue;
        }
        files_scanned += 1;
        let text = String::from_utf8_lossy(&bytes);
        let mut file_hit = false;
        for (idx, line) in text.lines().enumerate() {
            let hit = if ignore_case {
                line.to_lowercase().contains(&needle_lower)
            } else {
                line.contains(needle)
            };
            if !hit {
                continue;
            }
            file_hit = true;
            if matches.len() < GREP_MAX_RESULTS {
                let display: String = line.chars().take(LINE_DISPLAY_MAX).collect();
                matches.push(json!({
                    "file": path.strip_prefix(ws_root).unwrap_or(&path).to_string_lossy(),
                    "line": idx + 1,
                    "text": display,
                }));
            } else {
                truncated = true;
                break;
            }
        }
        if file_hit {
            files_matched += 1;
        }
    }
    GrepOutcome { matches, files_matched, files_scanned, truncated }
}

/// `pub async fn grep` — substring search over the workspace tree.
/// Args: `pattern` (required), `path` (optional search root, default the
/// workspace root), `ignore_case` (optional, default false).
pub async fn grep(ctx: &ToolCtx, params: &Value) -> Result<Value, ToolError> {
    if ctx.workspace.is_none() {
        return Err(ToolError(
            "minisd error: workspace root is not configured (pass --workspace)".to_string(),
        ));
    }
    let pattern = params
        .get("pattern")
        .and_then(Value::as_str)
        .map(str::to_string)
        .filter(|p| !p.is_empty())
        .ok_or_else(|| ToolError("missing required parameters: pattern".to_string()))?;
    if pattern.contains("..") {
        return Err(ToolError(format!(
            "minisd error: grep pattern '{pattern}' may not contain '..'"
        )));
    }
    let ignore_case = params.get("ignore_case").and_then(Value::as_bool).unwrap_or(false);
    let root_raw = params.get("path").and_then(Value::as_str).unwrap_or("");
    let search_root = resolve_search_root(ctx, root_raw).map_err(ToolError)?;
    let ws_root = ctx.workspace.clone().expect("checked above");
    // Clone before the move-closure: the json! envelope below still needs
    // `pattern` (E0382, CI 42486749610).
    let pat = pattern.clone();
    let outcome =
        tokio::task::spawn_blocking(move || grep_sync(&ws_root, &search_root, &pat, ignore_case))
        .await
        .map_err(|e| ToolError(format!("minisd error: grep join failed: {e}")))?;
    Ok(json!({
        "pattern": pattern,
        "path": root_raw,
        "ignore_case": ignore_case,
        "matches": outcome.matches,
        "files_matched": outcome.files_matched,
        "files_scanned": outcome.files_scanned,
        "truncated": outcome.truncated,
    }))
}

/// Recursive glob match against a single path component (`*` — any run
/// WITHOUT '/', `?` — one non-'/', `**/` — zero or more whole directories,
/// bare trailing `**` — anything). Classic backtracking, no regex dep.
fn glob_match(pat: &[u8], name: &[u8]) -> bool {
    if pat.is_empty() {
        return name.is_empty();
    }
    match pat[0] {
        b'*' => {
            if pat.len() >= 2 && pat[1] == b'*' {
                // `**/` — zero or more whole directories. The conventional
                // `src/**/*.rs` MUST also match `src/x.rs` (zero dirs).
                if pat.len() >= 3 && pat[2] == b'/' {
                    let rest = &pat[3..];
                    if glob_match(rest, name) {
                        return true;
                    }
                    for i in 0..name.len() {
                        if name[i] == b'/' && glob_match(rest, &name[i + 1..]) {
                            return true;
                        }
                    }
                    return false;
                }
                // bare trailing `**` — anything at all.
                if pat.len() == 2 {
                    return true;
                }
                // `**x` degenerates to `*x`.
                return glob_match(&pat[1..], name);
            }
            let rest = &pat[1..];
            for i in 0..=name.len() {
                if i > 0 && name[i - 1] == b'/' {
                    break; // a single `*` never crosses a '/'
                }
                if glob_match(rest, &name[i..]) {
                    return true;
                }
            }
            false
        }
        b'?' => !name.is_empty() && name[0] != b'/' && glob_match(&pat[1..], &name[1..]),
        c => !name.is_empty() && name[0] == c && glob_match(&pat[1..], &name[1..]),
    }
}

struct GlobOutcome {
    matches: Vec<Value>,
    files_scanned: usize,
    truncated: bool,
}

fn glob_sync(ws_root: &Path, search_root: &Path, pattern: &str, limit: usize) -> GlobOutcome {
    // Walk the SEARCH root; report paths relative to the WORKSPACE root
    // (same contract as grep_sync).
    let pat = pattern.trim_start_matches('/');
    let bytes = pat.as_bytes();
    let mut files = Vec::new();
    walk_files(search_root, &mut files);
    files.sort();
    let mut matches = Vec::new();
    let mut files_scanned = 0usize;
    let mut truncated = false;
    for path in files {
        files_scanned += 1;
        if matches.len() >= limit {
            truncated = true;
            break;
        }
        let rel = path.strip_prefix(ws_root).unwrap_or(&path);
        // A pattern without '/' matches the BASENAME at any depth (the
        // common "find me every Cargo.toml" expectation); a pattern with
        // '/' matches the path relative to the WORKSPACE root — reported
        // paths must stay file_read-addressable.
        let target = if pat.contains('/') {
            rel.to_string_lossy().to_string()
        } else {
            path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()
        };
        if glob_match(bytes, target.as_bytes()) {
            matches.push(json!(rel.to_string_lossy()));
        }
    }
    GlobOutcome { matches, files_scanned, truncated }
}

/// `pub async fn glob` — glob path search over the workspace tree.
/// Args: `pattern` (required; `*`, `?`, `**/`), `path` (optional root),
/// `limit` (optional, default 200).
pub async fn glob(ctx: &ToolCtx, params: &Value) -> Result<Value, ToolError> {
    if ctx.workspace.is_none() {
        return Err(ToolError(
            "minisd error: workspace root is not configured (pass --workspace)".to_string(),
        ));
    }
    let pattern = params
        .get("pattern")
        .and_then(Value::as_str)
        .map(str::to_string)
        .filter(|p| !p.is_empty())
        .ok_or_else(|| ToolError("missing required parameters: pattern".to_string()))?;
    if pattern.contains("..") {
        return Err(ToolError(format!(
            "minisd error: glob pattern '{pattern}' may not contain '..'"
        )));
    }
    let limit = params
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(GLOB_DEFAULT_LIMIT as u64)
        .clamp(1, GLOB_DEFAULT_LIMIT as u64) as usize;
    let root_raw = params.get("path").and_then(Value::as_str).unwrap_or("");
    let search_root = resolve_search_root(ctx, root_raw).map_err(ToolError)?;
    let ws_root = ctx.workspace.clone().expect("checked above");
    // Clone before the move-closure: the json! envelope below still needs
    // `pattern` (E0382, CI 42486749610).
    let pat = pattern.clone();
    let outcome =
        tokio::task::spawn_blocking(move || glob_sync(&ws_root, &search_root, &pat, limit))
        .await
        .map_err(|e| ToolError(format!("minisd error: glob join failed: {e}")))?;
    Ok(json!({
        "pattern": pattern,
        "path": root_raw,
        "matches": outcome.matches,
        "files_scanned": outcome.files_scanned,
        "truncated": outcome.truncated,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn ctx_with_workspace(root: &Path) -> ToolCtx {
        ToolCtx {
            storage: root.join("storage"),
            archive_dir: root.join("archive"),
            tz_offset_secs: 0,
            workspace: Some(root.join("workspace")),
        }
    }

    fn ctx_without_workspace() -> ToolCtx {
        ToolCtx {
            storage: PathBuf::from("/tmp/minisd-files-nw"),
            archive_dir: PathBuf::from("/tmp/minisd-files-nw/archive"),
            tz_offset_secs: 0,
            workspace: None,
        }
    }

    fn params(paths: &[&str], max: Option<u64>) -> Value {
        let arr: Vec<Value> = paths.iter().map(|p| json!(p)).collect();
        let mut v = json!({ "paths": arr });
        if let Some(m) = max {
            v["max_bytes_per_file"] = json!(m);
        }
        v
    }

    #[test]
    fn truncation_walks_back_to_a_char_boundary() {
        // "жюри" is 8 bytes (2 per char); a cut at 5 must walk back to 4.
        let bytes = "жюри".as_bytes();
        let (slice, truncated) = truncate_utf8(bytes, 5);
        assert!(truncated);
        assert_eq!(slice, "жю".as_bytes());
        // Within the limit: untouched.
        let (slice, truncated) = truncate_utf8(bytes, 8);
        assert!(!truncated);
        assert_eq!(slice, bytes);
    }

    #[tokio::test]
    async fn reads_every_file_in_one_call_and_reports_sizes() {
        let dir = std::env::temp_dir().join(format!("minisd-files-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("workspace/src")).unwrap();
        std::fs::write(dir.join("workspace/src/a.rs"), "alpha").unwrap();
        std::fs::write(dir.join("workspace/src/b.rs"), "beta").unwrap();
        let ctx = ctx_with_workspace(&dir);

        // Relative AND guest-absolute forms resolve to the same root.
        let out = multi_file_read(
            &ctx,
            &params(&["src/a.rs", "/var/minis/workspace/src/b.rs"], None),
        )
        .await
        .unwrap();
        assert_eq!(out["count"], 2);
        assert_eq!(out["requested"], 2);
        let files = out["files"].as_array().unwrap();
        assert!(files.iter().any(|f| f["content"] == "alpha"));
        assert!(files.iter().any(|f| f["content"] == "beta"));
        assert!(files.iter().all(|f| f["truncated"] == json!(false)));
        assert!(out["errors"].as_array().unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn a_missing_file_degrades_per_file_not_batch_wide() {
        let dir = std::env::temp_dir().join(format!("minisd-files-miss-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("workspace")).unwrap();
        std::fs::write(dir.join("workspace/ok.txt"), "fine").unwrap();
        let ctx = ctx_with_workspace(&dir);

        let out = multi_file_read(&ctx, &params(&["ok.txt", "nope.txt"], None)).await.unwrap();
        assert_eq!(out["count"], 1);
        let errors = out["errors"].as_array().unwrap();
        assert_eq!(errors.len(), 1);
        assert!(errors[0]["error"].as_str().unwrap().contains("read failed"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn per_file_byte_cap_truncates_without_splitting_chars() {
        let dir = std::env::temp_dir().join(format!("minisd-files-trunc-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("workspace")).unwrap();
        let long = "жюри".repeat(100); // 800 bytes
        std::fs::write(dir.join("workspace/big.txt"), &long).unwrap();
        let ctx = ctx_with_workspace(&dir);

        let out = multi_file_read(&ctx, &params(&["big.txt"], Some(5))).await.unwrap();
        let file = &out["files"][0];
        assert_eq!(file["truncated"], json!(true));
        let content = file["content"].as_str().unwrap();
        // 5-byte cap walks back to a 4-byte char boundary: one "жюри" char pair.
        assert_eq!(content, "жю");
        assert_eq!(file["size_bytes"], 800); // the REAL size is reported
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn traversal_and_foreign_absolute_paths_are_refused_per_file() {
        let dir = std::env::temp_dir().join(format!("minisd-files-trav-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("workspace")).unwrap();
        let ctx = ctx_with_workspace(&dir);

        let out = multi_file_read(
            &ctx,
            &params(&["../outside.txt", "/etc/passwd", "/var/minis/shared/x.txt"], None),
        )
        .await
        .unwrap();
        assert_eq!(out["count"], 0);
        let errors = out["errors"].as_array().unwrap();
        assert_eq!(errors.len(), 3);
        assert!(errors.iter().all(|e| e["error"].as_str().unwrap().contains("minisd error")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn a_symlink_escaping_the_workspace_is_refused() {
        #[cfg(unix)]
        {
            let dir = std::env::temp_dir().join(format!("minisd-files-link-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(dir.join("workspace")).unwrap();
            std::fs::write(dir.join("secret.txt"), "top secret").unwrap();
            std::os::unix::fs::symlink(dir.join("secret.txt"), dir.join("workspace/link.txt"))
                .unwrap();
            let ctx = ctx_with_workspace(&dir);
            let out = multi_file_read(&ctx, &params(&["link.txt"], None)).await.unwrap();
            assert_eq!(out["count"], 0);
            let err = &out["errors"][0]["error"];
            assert!(err.as_str().unwrap().contains("escapes the workspace"));
            let _ = std::fs::remove_dir_all(&dir);
        }
    }

    #[tokio::test]
    async fn without_a_configured_workspace_the_tool_says_so() {
        let ctx = ctx_without_workspace();
        let err = multi_file_read(&ctx, &params(&["a.txt"], None)).await.unwrap_err();
        assert!(err.0.contains("workspace root is not configured"));
    }

    #[tokio::test]
    async fn missing_paths_param_is_the_verbatim_schema_error() {
        let dir = std::env::temp_dir().join(format!("minisd-files-nop-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let err = multi_file_read(&ctx_with_workspace(&dir), &json!({})).await.unwrap_err();
        assert_eq!(err.0, "missing required parameters: paths");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn more_than_64_paths_is_refused_up_front() {
        let dir = std::env::temp_dir().join(format!("minisd-files-cap-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let ctx = ctx_with_workspace(&dir);
        let paths: Vec<String> = (0..65).map(|i| format!("f{i}.txt")).collect();
        let err = multi_file_read(&ctx, &json!({ "paths": paths })).await.unwrap_err();
        assert!(err.0.contains("at most 64"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn binary_content_is_an_honest_per_file_error() {
        let dir = std::env::temp_dir().join(format!("minisd-files-bin-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("workspace")).unwrap();
        std::fs::write(dir.join("workspace/blob.bin"), [0xFF, 0xFE, 0x00, 0xC3]).unwrap();
        std::fs::write(dir.join("workspace/text.txt"), "fine").unwrap();
        let ctx = ctx_with_workspace(&dir);
        let out = multi_file_read(&ctx, &params(&["blob.bin", "text.txt"], None)).await.unwrap();
        assert_eq!(out["count"], 1);
        assert!(out["errors"][0]["error"].as_str().unwrap().contains("UTF-8"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn classification_declares_one_fs_key_per_path() {
        let (keys, effect, pool) =
            crate::engine::keys::classify_tool("multi_file_read", r#"{"paths":["src/a.rs","src/b.rs"]}"#);
        assert_eq!(
            keys,
            Some(vec![
                "fs:src/a.rs".to_string(),
                "fs:src/b.rs".to_string(),
            ])
        );
        assert_eq!(effect, crate::engine::types::ToolEffect::Read);
        assert_eq!(pool, crate::engine::types::ToolPool::Io);
        // No usable paths: the conservative barrier.
        let (keys2, effect2, _) = crate::engine::keys::classify_tool("multi_file_read", r#"{"paths":[]}"#);
        assert_eq!(keys2, None);
        assert_eq!(effect2, crate::engine::types::ToolEffect::GlobalBarrier);
    }

    // ── [T-native-grep-glob] ──

    fn temp_workspace(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("minisd-search-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("workspace/src/deep")).unwrap();
        root
    }

    #[tokio::test]
    async fn grep_finds_needle_with_line_numbers_and_prunes_vcs() {
        let root = temp_workspace("grep");
        let ws = root.join("workspace");
        std::fs::write(ws.join("src/a.rs"), "use std::io;\ntokio::spawn(async {});\n").unwrap();
        std::fs::write(ws.join("src/deep/b.txt"), "nothing to see here\n").unwrap();
        std::fs::create_dir_all(ws.join(".git")).unwrap();
        std::fs::write(ws.join(".git/needle.txt"), "tokio::spawn in the VCS dir\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let out = grep(&ctx, &json!({"pattern": "tokio::spawn"})).await.unwrap();
        assert_eq!(out["files_matched"], json!(1));
        assert_eq!(out["files_scanned"], json!(2)); // .git pruned, b.txt scanned
        let matches = out["matches"].as_array().unwrap();
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0]["file"], json!("src/a.rs"));
        assert_eq!(matches[0]["line"], json!(2));
        assert_eq!(out["truncated"], json!(false));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn grep_ignore_case_and_search_root_scoping() {
        let root = temp_workspace("grep-ci");
        let ws = root.join("workspace");
        std::fs::write(ws.join("a.txt"), "Hello World\nbye\n").unwrap();
        std::fs::create_dir_all(ws.join("sub")).unwrap();
        std::fs::write(ws.join("sub/b.txt"), "hello WORLD\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        // Case-sensitive: only a.txt line 1.
        let out = grep(&ctx, &json!({"pattern": "Hello World"})).await.unwrap();
        assert_eq!(out["files_matched"], json!(1));
        // ignore_case: both files.
        let out2 = grep(&ctx, &json!({"pattern": "hello world", "ignore_case": true})).await.unwrap();
        assert_eq!(out2["files_matched"], json!(2));
        // Scoped root: only sub/ is searched.
        let out3 = grep(&ctx, &json!({"pattern": "hello world", "ignore_case": true, "path": "sub"})).await.unwrap();
        assert_eq!(out3["files_matched"], json!(1));
        assert_eq!(out3["matches"][0]["file"], json!("sub/b.txt"));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn grep_skips_binary_and_oversized_files() {
        let root = temp_workspace("grep-bin");
        let ws = root.join("workspace");
        std::fs::write(ws.join("bin.dat"), [0u8, 1, 2, b't', b'o']).unwrap();
        std::fs::write(ws.join("huge.txt"), "tokio ".repeat(500_000)).unwrap(); // > 2 MiB
        std::fs::write(ws.join("ok.txt"), "tokio here\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let out = grep(&ctx, &json!({"pattern": "tokio"})).await.unwrap();
        assert_eq!(out["files_scanned"], json!(1));
        assert_eq!(out["files_matched"], json!(1));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn glob_basename_pattern_matches_any_depth() {
        let root = temp_workspace("glob-base");
        let ws = root.join("workspace");
        std::fs::write(ws.join("main.rs"), "fn main() {}\n").unwrap();
        std::fs::write(ws.join("src/deep/main.rs"), "fn deep() {}\n").unwrap();
        std::fs::write(ws.join("src/other.rs"), "fn o() {}\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let out = glob(&ctx, &json!({"pattern": "main.rs"})).await.unwrap();
        let matches = out["matches"].as_array().unwrap();
        assert_eq!(matches.len(), 2);
        assert!(matches.contains(&json!("main.rs")));
        assert!(matches.contains(&json!("src/deep/main.rs")));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn glob_single_star_does_not_cross_directories() {
        let root = temp_workspace("glob-star");
        let ws = root.join("workspace");
        std::fs::write(ws.join("a.rs"), "1\n").unwrap();
        std::fs::write(ws.join("src/b.rs"), "2\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let out = glob(&ctx, &json!({"pattern": "*.rs", "path": "src"})).await.unwrap();
        assert_eq!(out["matches"], json!(["src/b.rs"]));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn glob_double_star_crosses_and_matches_zero_dirs() {
        let root = temp_workspace("glob-dstar");
        let ws = root.join("workspace");
        std::fs::write(ws.join("src/x.rs"), "1\n").unwrap();
        std::fs::write(ws.join("src/deep/y.rs"), "2\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let out = glob(&ctx, &json!({"pattern": "src/**/*.rs"})).await.unwrap();
        let matches = out["matches"].as_array().unwrap();
        assert_eq!(matches.len(), 2);
        assert!(matches.contains(&json!("src/x.rs")));
        assert!(matches.contains(&json!("src/deep/y.rs")));
        std::fs::remove_dir_all(&root).ok();
    }

    #[tokio::test]
    async fn glob_and_grep_refuse_workspace_less_ctx_and_dotdot() {
        let ctx = ctx_without_workspace();
        let err = glob(&ctx, &json!({"pattern": "*.rs"})).await.unwrap_err();
        assert!(err.0.contains("workspace"));
        let root = temp_workspace("glob-guard");
        let ctx2 = ctx_with_workspace(&root);
        let err2 = glob(&ctx2, &json!({"pattern": "../outside.rs"})).await.unwrap_err();
        assert!(err2.0.contains(".."));
        let err3 = grep(&ctx2, &json!({"pattern": "a..b"})).await.unwrap_err();
        assert!(err3.0.contains(".."));
        std::fs::remove_dir_all(&root).ok();
    }

    // [T-native-grep-glob] THE contract, named: whatever root a search is
    // scoped to, reported paths stay relative to the WORKSPACE root —
    // every file path any tool returns must be file_read-addressable.
    #[tokio::test]
    async fn scoped_search_reports_workspace_relative_paths() {
        let root = temp_workspace("grep-contract");
        let ws = root.join("workspace");
        std::fs::write(ws.join("src/deep/needle.txt"), "findme\n").unwrap();
        let ctx = ctx_with_workspace(&root);
        let g = grep(&ctx, &json!({"pattern": "findme", "path": "src"})).await.unwrap();
        assert_eq!(g["matches"][0]["file"], json!("src/deep/needle.txt"));
        let gl = glob(&ctx, &json!({"pattern": "needle.txt", "path": "src"})).await.unwrap();
        assert_eq!(gl["matches"], json!(["src/deep/needle.txt"]));
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn glob_matcher_semantics() {
        // Single '*' never crosses '/'.
        assert!(glob_match(b"*.rs", b"main.rs"));
        assert!(!glob_match(b"*.rs", b"deep/main.rs"));
        // `?` — exactly one non-'/' (cannot skip zero characters).
        assert!(glob_match(b"m?in.rs", b"main.rs"));
        assert!(!glob_match(b"m?in.rs", b"min.rs"));
        // `**/` — zero or more whole directories.
        assert!(glob_match(b"src/**/*.rs", b"src/x.rs"));
        assert!(glob_match(b"src/**/*.rs", b"src/deep/y.rs"));
        assert!(!glob_match(b"src/**/*.rs", b"other/x.rs"));
        // Bare trailing '**' — anything.
        assert!(glob_match(b"src/**", b"src/a/b/c.rs"));
    }
}

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
}

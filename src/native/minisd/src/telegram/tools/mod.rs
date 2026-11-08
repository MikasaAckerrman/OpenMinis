//! [M-R2] The tool surface: 21 JSON-RPC schemas in `tools/list`
//! (16 core read-only + 5 extended, §3/§4 of docs/M_R2_TOOL_MAPPING.md;
//! yt_transcript stays in the guest sandbox) and the `tools/call`
//! dispatcher. The MCP envelope — `result.content[0].text` carrying the
//! JSON string — matches telegram.py:1434-1440 exactly.

pub mod params;

#[cfg(test)]
mod mock_tests;

pub mod channels;
pub mod media;
pub mod read;
pub mod search;

use crate::telegram::api::{TgApi, TgError};
use crate::telegram::tools::params::MissingParams;
use serde_json::{json, Value};
use std::path::PathBuf;

/// Everything a handler needs besides the API seam.
#[derive(Clone)]
pub struct ToolCtx {
    /// filesDir/tg-vault equivalent: whitelist, cache, cursor, accounts.
    pub storage: PathBuf,
    /// Host path serving the guest `/var/minis/shared/tg` bind (the
    /// archive.db lives here; wired by DaemonSupervisor at M-R3).
    pub archive_dir: PathBuf,
    /// Fixed UTC offset reproducing the legacy daemon's POSIX TZ
    /// ("LCL-8" on the device → +8h).
    pub tz_offset_secs: i32,
    /// Host directory serving the guest workspace bind
    /// (/var/minis/workspace) — the root multi_file_read resolves
    /// relative paths against. None = the tool answers a per-call
    /// configuration error instead of guessing.
    pub workspace: Option<PathBuf>,
}

pub type ToolResult = Result<Value, ToolError>;

/// A business error: renders INSIDE `content[0].text` as
/// `{"error": ...}` (§5.2) — not a JSON-RPC error envelope.
#[derive(Debug)]
pub struct ToolError(pub String);

/// The schema surface — one entry per tool, name unique by a
/// compile-time-checked registry (the dispatch match and this list are
/// pinned against each other by `all_names_are_dispatched`).
pub fn schemas() -> Vec<Value> {
    vec![
        json!({"name": "tg_whoami", "description": "Profile of the authorized account; refreshes accounts.json.", "inputSchema": {"type": "object", "properties": {}}}),
        json!({"name": "tg_list_chats", "description": "Dialogs (cached, TTL 30 min) with the whitelist filter applied.", "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer", "description": "max chats to return (default 50)"}}}}),
        json!({"name": "tg_read", "description": "Chat history page, newest first.", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 20}, "offset_id": {"type": "integer", "description": "start strictly below this message id"}}}}),
        json!({"name": "tg_search", "description": "Search inside one chat.", "inputSchema": {"type": "object", "required": ["chat_id", "query"], "properties": {"chat_id": {"type": "integer"}, "query": {"type": "string"}, "limit": {"type": "integer", "default": 20}}}}),
        json!({"name": "tg_unread", "description": "Dialogs with unread counters.", "inputSchema": {"type": "object", "properties": {}}}),
        json!({"name": "tg_get_chat", "description": "Full chat info (description, members).", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}}}}),
        json!({"name": "tg_global_search", "description": "Global message search across Telegram.", "inputSchema": {"type": "object", "required": ["query"], "properties": {"query": {"type": "string"}, "limit": {"type": "integer", "default": 20}}}}),
        json!({"name": "tg_channel_digest", "description": "One-line previews (<=120 chars) of recent channel posts.", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 10}}}}),
        json!({"name": "tg_scan_channels", "description": "Batch scan of up to 30 channels, 3 posts each.", "inputSchema": {"type": "object", "properties": {"channels_limit": {"type": "integer", "default": 30}, "per_channel": {"type": "integer", "default": 3}}}}),
        json!({"name": "tg_period_read", "description": "History slice date_from..date_to with an early stop (cap 4000).", "inputSchema": {"type": "object", "required": ["chat_id", "date_from", "date_to"], "properties": {"chat_id": {"type": "integer"}, "date_from": {"type": "string", "description": "YYYY-MM-DD inclusive"}, "date_to": {"type": "string", "description": "YYYY-MM-DD inclusive"}}}}),
        json!({"name": "tg_replies", "description": "Discussion replies of one channel post.", "inputSchema": {"type": "object", "required": ["chat_id", "message_id"], "properties": {"chat_id": {"type": "integer"}, "message_id": {"type": "integer"}, "limit": {"type": "integer", "default": 20}}}}),
        json!({"name": "tg_stats", "description": "Recent activity of the top channels (last-100 sample, 24h count).", "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer", "default": 10}}}}),
        json!({"name": "tg_link_search", "description": "Messages carrying http(s) links.", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 20}}}}),
        json!({"name": "tg_multi_search", "description": "Sequential search across up to 20 chats.", "inputSchema": {"type": "object", "required": ["chat_ids", "query"], "properties": {"chat_ids": {"type": "array", "items": {"type": "integer"}}, "query": {"type": "string"}, "limit": {"type": "integer", "default": 10}}}}),
        json!({"name": "tg_download_media", "description": "Download one message's media (path-traversal-safe).", "inputSchema": {"type": "object", "required": ["chat_id", "message_id", "file_name"], "properties": {"chat_id": {"type": "integer"}, "message_id": {"type": "integer"}, "file_name": {"type": "string"}}}}),
        json!({"name": "tg_archive_chat", "description": "Archive a chat's history into archive.db (SQLite).", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 1000}}}}),
        json!({"name": "tg_read_parallel", "description": "Parallel history fetch across chats (<=10 in flight).", "inputSchema": {"type": "object", "required": ["chat_ids"], "properties": {"chat_ids": {"type": "array", "items": {"type": "integer"}}, "limit": {"type": "integer", "default": 20}}}}),
        json!({"name": "tg_read_new", "description": "Only messages newer than the stored cursor; advances it.", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 100}}}}),
        json!({"name": "tg_read_chunked", "description": "One chat read as parallel offset windows.", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "chunk_size": {"type": "integer", "default": 100}, "windows": {"type": "integer", "default": 3}}}}),
        json!({"name": "tg_search_parallel", "description": "Parallel search across chats (<=10 in flight).", "inputSchema": {"type": "object", "required": ["chat_ids", "query"], "properties": {"chat_ids": {"type": "array", "items": {"type": "integer"}}, "query": {"type": "string"}, "limit": {"type": "integer", "default": 10}}}}),
        json!({"name": "tg_analyze_chat", "description": "Top words and senders from archive.db (or a live sample).", "inputSchema": {"type": "object", "required": ["chat_id"], "properties": {"chat_id": {"type": "integer"}, "limit": {"type": "integer", "default": 10}}}}),
        json!({"name": "multi_file_read", "description": "Read up to 64 files in ONE call: the engine fans out 16 parallel reads under the I/O pool; per-file byte cap with UTF-8-safe truncation; a missing file degrades per-file, never fails the batch.", "inputSchema": {"type": "object", "required": ["paths"], "properties": {"paths": {"type": "array", "items": {"type": "string"}, "description": "file paths, relative to the session workspace (or /var/minis/workspace/...)"}, "max_bytes_per_file": {"type": "integer", "default": 65536}}}}),
        json!({"name": "grep", "description": "Native substring search over the workspace tree (zero ptrace overhead): per-match file/line/text, binary and >2MiB files skipped, .git/target/node_modules pruned, 200-hit cap with a truncation flag.", "inputSchema": {"type": "object", "required": ["pattern"], "properties": {"pattern": {"type": "string", "description": "literal substring to find (no regex)"}, "path": {"type": "string", "description": "search root relative to the workspace; defaults to the workspace root"}, "ignore_case": {"type": "boolean", "default": false}}}}),
        json!({"name": "glob", "description": "Native glob path search over the workspace tree: a pattern without '/' matches basenames at any depth; '*' and '?' never cross '/'; '**/' spans whole directories; '.git'/build dirs pruned; 200-hit cap.", "inputSchema": {"type": "object", "required": ["pattern"], "properties": {"pattern": {"type": "string", "description": "glob like '*.rs', 'src/**/*.toml'"}, "path": {"type": "string", "description": "search root relative to the workspace; defaults to the workspace root"}, "limit": {"type": "integer", "default": 200}}}}),
    ]
}

/// Names the dispatcher knows — pinned equal to `schemas()` by a test.
pub const TOOL_NAMES: [&str; 24] = [
    "tg_whoami",
    "tg_list_chats",
    "tg_read",
    "tg_search",
    "tg_unread",
    "tg_get_chat",
    "tg_global_search",
    "tg_channel_digest",
    "tg_scan_channels",
    "tg_period_read",
    "tg_replies",
    "tg_stats",
    "tg_link_search",
    "tg_multi_search",
    "tg_download_media",
    "tg_archive_chat",
    "tg_read_parallel",
    "tg_read_new",
    "tg_read_chunked",
    "tg_search_parallel",
    "tg_analyze_chat",
    "multi_file_read",
    "grep",
    "glob",
];

/// The `tools/call` dispatcher. `authorized == false` short-circuits
/// every tool with the §5.2 session error. Rate limiting and flood
/// retry live inside the transport (`TgApi` adapter), not here.
pub async fn call<A: TgApi + 'static>(
    api: &std::sync::Arc<A>,
    ctx: &ToolCtx,
    authorized: bool,
    name: &str,
    params: &Value,
) -> ToolResult {
    // Only Telegram-backed tools require an authorized MTProto session.
    // Workspace engine tools (e.g. multi_file_read) run offline without Telegram.
    if name.starts_with("tg_") && !authorized {
        return Err(ToolError(
            "not authorized - run send-code/verify first".into(),
        ));
    }
    let args = if params.get("arguments").is_some() {
        params.get("arguments").cloned().unwrap_or(json!({}))
    } else {
        params.clone()
    };
    match name {
        "tg_whoami" => media::whoami(api, ctx).await,
        "tg_list_chats" => channels::list_chats(api, ctx, &args).await,
        "tg_read" => read::read(api, ctx, &args).await,
        "tg_search" => search::search(api, ctx, &args).await,
        "tg_unread" => channels::unread(api, ctx, &args).await,
        "tg_get_chat" => channels::get_chat(api, ctx, &args).await,
        "tg_global_search" => search::global_search(api, ctx, &args).await,
        "tg_channel_digest" => channels::channel_digest(api, ctx, &args).await,
        "tg_scan_channels" => channels::scan_channels(api, ctx, &args).await,
        "tg_period_read" => read::period_read(api, ctx, &args).await,
        "tg_replies" => read::replies(api, ctx, &args).await,
        "tg_stats" => channels::stats(api, ctx, &args).await,
        "tg_link_search" => search::link_search(api, ctx, &args).await,
        "tg_multi_search" => search::multi_search(api, ctx, &args).await,
        "tg_download_media" => media::download_media(api, ctx, &args).await,
        "tg_archive_chat" => media::archive_chat(api, ctx, &args).await,
        "tg_read_parallel" => read::read_parallel(api, ctx, &args).await,
        "tg_read_new" => read::read_new(api, ctx, &args).await,
        "tg_read_chunked" => read::read_chunked(api, ctx, &args).await,
        "tg_search_parallel" => search::search_parallel(api, ctx, &args).await,
        "tg_analyze_chat" => media::analyze_chat(api, ctx, &args).await,
        // [Опора 2] The read multiplier: one call, the engine fans out.
        "multi_file_read" => crate::engine::files::multi_file_read(ctx, &args).await,
        // [T-native-grep-glob] The walk lives in the daemon: zero ptrace tax.
        "grep" => crate::engine::files::grep(ctx, &args).await,
        "glob" => crate::engine::files::glob(ctx, &args).await,
        other => Err(ToolError(format!("unknown tool: {other}"))),
    }
}

/// The MCP envelope for a SUCCESSFUL tool call:
/// `result.content[0].text` = the JSON-stringified tool payload.
pub fn mcp_success(payload: &Value) -> Value {
    json!({
        "content": [{"type": "text", "text": serde_json::to_string(payload).unwrap_or_default()}]
    })
}

/// The MCP envelope for a business error (§5.1/§5.2): still a SUCCESS
/// envelope at the JSON-RPC level, the error rides inside the text.
pub fn mcp_error(tool_error: &ToolError) -> Value {
    mcp_success(&json!({"error": tool_error.0}))
}

/// Schema-level failure (missing params) — the §5.1 verbatim shape.
#[allow(dead_code)]
pub fn mcp_missing_params(missing: MissingParams) -> Value {
    mcp_success(&json!({"error": missing.error_text()}))
}

pub(crate) fn gate(
    whitelist: &crate::telegram::whitelist::WhitelistConfig,
    chat_id: i64,
    kind: crate::telegram::whitelist::ChatType,
) -> Result<(), ToolError> {
    use crate::telegram::whitelist::Verdict;
    match whitelist.check(chat_id, kind) {
        Verdict::Allowed => Ok(()),
        v => Err(ToolError(
            v.error_text(chat_id, kind.token())
                .unwrap_or_else(|| "blocked".into()),
        )),
    }
}

pub(crate) fn fmt_one(
    raw: &crate::telegram::format::RawMessage,
    tz_offset_secs: i32,
) -> Value {
    serde_json::to_value(crate::telegram::format::format_message(raw, tz_offset_secs))
        .unwrap_or(Value::Null)
}

pub(crate) fn fmt_many(
    raws: &[crate::telegram::format::RawMessage],
    tz_offset_secs: i32,
) -> Vec<Value> {
    raws.iter().map(|r| fmt_one(r, tz_offset_secs)).collect()
}

/// Shared TgError → ToolError translation.
impl From<TgError> for ToolError {
    fn from(e: TgError) -> Self {
        ToolError(e.error_text())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_registry_and_the_dispatcher_agree_on_every_name() {
        let all_schemas = schemas();
        let schema_names: Vec<&str> = all_schemas
            .iter()
            .filter_map(|s| s["name"].as_str())
            .collect();
        assert_eq!(schema_names.len(), 24);
        assert_eq!(schema_names, TOOL_NAMES.to_vec());
        // Uniqueness: no duplicate tool names in tools/list.
        let mut sorted = schema_names.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), 24);
    }

    #[test]
    fn every_schema_is_an_object_with_a_description() {
        for s in schemas() {
            assert!(s["name"].as_str().is_some(), "{}", s);
            assert!(!s["description"].as_str().unwrap().is_empty(), "{}", s);
            assert_eq!(s["inputSchema"]["type"], "object", "{}", s);
        }
    }

    #[test]
    fn required_params_are_declared_consistently() {
        let must_require: &[(&str, &[&str])] = &[
            ("tg_read", &["chat_id"]),
            ("tg_search", &["chat_id", "query"]),
            ("tg_get_chat", &["chat_id"]),
            ("tg_global_search", &["query"]),
            ("tg_channel_digest", &["chat_id"]),
            ("tg_period_read", &["chat_id", "date_from", "date_to"]),
            ("tg_replies", &["chat_id", "message_id"]),
            ("tg_link_search", &["chat_id"]),
            ("tg_multi_search", &["chat_ids", "query"]),
            ("tg_download_media", &["chat_id", "message_id", "file_name"]),
            ("tg_archive_chat", &["chat_id"]),
            ("tg_read_parallel", &["chat_ids"]),
            ("tg_read_new", &["chat_id"]),
            ("tg_read_chunked", &["chat_id"]),
            ("tg_search_parallel", &["chat_ids", "query"]),
            ("tg_analyze_chat", &["chat_id"]),
        ];
        for (name, required) in must_require {
            let schema = schemas()
                .into_iter()
                .find(|s| s["name"] == *name)
                .unwrap_or_else(|| panic!("{name} missing"));
            let declared: Vec<String> = schema["inputSchema"]["required"]
                .as_array()
                .map(|a| a.iter().filter_map(Value::as_str).map(String::from).collect())
                .unwrap_or_default();
            let expected: Vec<String> = required.iter().map(|s| s.to_string()).collect();
            assert_eq!(declared, expected, "schema of {name}");
        }
    }

    #[test]
    fn the_mcp_envelope_wraps_the_payload_as_a_text_block() {
        let env = mcp_success(&json!({"count": 2}));
        assert_eq!(env["content"][0]["type"], "text");
        let inner: Value = serde_json::from_str(env["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(inner["count"], 2);
    }

    #[test]
    fn the_business_error_rides_inside_a_success_envelope() {
        let env = mcp_error(&ToolError("Chat 5 is blocked in blacklist.".into()));
        let inner: Value =
            serde_json::from_str(env["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(
            inner["error"],
            "Chat 5 is blocked in blacklist."
        );
    }

    #[test]
    fn the_missing_param_error_is_the_verbatim_legacy_shape() {
        let mut missing = Vec::new();
        params::req_i64(&json!({}), "chat_id", &mut missing);
        let env = mcp_missing_params(MissingParams(missing));
        let inner: Value =
            serde_json::from_str(env["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(inner["error"], "missing required parameters: chat_id");
    }
}

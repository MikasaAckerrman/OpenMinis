//! [M-R2] JSON-RPC v1 contract of the vault gateway.
//!
//! `initialize` answers with the "telegram" server info — the
//! `isAlive()` probe of `TelegramVaultManager` (TelegramVaultManager.kt
//! :85-107) checks exactly that string. `tools/list` serves the 21
//! schemas; `tools/call` routes into the vault module with the MCP
//! envelope (`result.content[0].text` = the JSON-stringified payload).
//!
//! Bearer validation mirrors the B12 gate: any mismatch is HTTP 401.
//! JSON-RPC-level errors ride HTTP 200 inside the envelope.

use crate::telegram::tools::{self, ToolCtx};
use serde_json::{json, Value};

pub const SERVER_NAME: &str = "telegram";
pub const SERVER_VERSION: &str = "0.2.0-minisd";

/// Byte-wise comparison without early exit on mismatch. The length
/// early-return leaks only the length class — the expected token is a
/// fixed 32-byte B12 secret and callers are same-uid local processes.
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// B12 semantics: the header must be exactly `Bearer <token>` with the
/// expected token, byte-for-byte.
pub fn bearer_ok(expected: &str, authorization: Option<&str>) -> bool {
    let Some(header) = authorization else { return false };
    let Some(token) = header.strip_prefix("Bearer ") else { return false };
    constant_time_eq(token.as_bytes(), expected.as_bytes())
}

fn rpc_error(id: Value, code: i64, message: &str) -> Value {
    json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message } })
}

/// Dispatch one JSON-RPC request against the tool surface: the HTTP
/// status is always 200 (the Bearer gate answers 401 BEFORE dispatch);
/// `authorized` = the daemon's session state (false short-circuits
/// tools with the §5.2 session error).
pub async fn dispatch<A: crate::telegram::api::TgApi + 'static>(
    body: &[u8],
    authorized: bool,
    api: &std::sync::Arc<A>,
    ctx: &ToolCtx,
) -> Value {
    let parsed: Value = match serde_json::from_slice(body) {
        Ok(v) => v,
        Err(e) => return rpc_error(Value::Null, -32700, &format!("parse error: {e}")),
    };
    let id = parsed.get("id").cloned().unwrap_or(Value::Null);
    let Some(method) = parsed.get("method").and_then(Value::as_str) else {
        return rpc_error(id, -32600, "invalid request: missing method");
    };
    match method {
        "initialize" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": { "name": SERVER_NAME, "version": SERVER_VERSION }
            }
        }),
        "tools/list" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": { "tools": tools::schemas() }
        }),
        "tools/call" => {
            let name = parsed
                .pointer("/params/name")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let params = parsed.get("params").cloned().unwrap_or(json!({}));
            if !tools::TOOL_NAMES.contains(&name.as_str()) {
                // §5.1: an unknown tool is a JSON-RPC error.
                return rpc_error(id, -32601, &format!("unknown tool: {name}"));
            }
            match tools::call(api, ctx, authorized, &name, &params).await {
                Ok(payload) => json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": tools::mcp_success(&payload)
                }),
                Err(e) => json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": tools::mcp_error(&e)
                }),
            }
        }
        other => rpc_error(id, -32601, &format!("method not found: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bearer_matches_exact_token() {
        assert!(bearer_ok("secret123", Some("Bearer secret123")));
        assert!(!bearer_ok("secret123", Some("Bearer  secret123"))); // double space
    }

    #[test]
    fn bearer_rejects_every_mismatch_shape() {
        assert!(!bearer_ok("secret123", Some("Bearer secret124")));
        assert!(!bearer_ok("secret123", Some("bearer secret123"))); // wrong scheme case
        assert!(!bearer_ok("secret123", Some("Token secret123"))); // wrong scheme
        assert!(!bearer_ok("secret123", Some("secret123"))); // no scheme
        assert!(!bearer_ok("secret123", None)); // no header
        assert!(!bearer_ok("secret123", Some("Bearer "))); // empty token
    }

    #[test]
    fn constant_time_eq_is_plain_equality_on_bytes() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
        assert!(!constant_time_eq(b"", b"a"));
        assert!(constant_time_eq(b"", b""));
    }

    #[tokio::test]
    async fn an_unparseable_body_is_a_parse_error_with_null_id() {
        let body = b"not json at all";
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, false, &api, &test_ctx()).await;
        assert_eq!(v["error"]["code"], -32700);
        assert!(v["id"].is_null());
    }

    #[tokio::test]
    async fn an_unknown_json_rpc_method_is_minus_32601() {
        let body = br#"{"jsonrpc":"2.0","id":3,"method":"tools/nope"}"#;
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, true, &api, &test_ctx()).await;
        assert_eq!(v["error"]["code"], -32601);
    }

    #[tokio::test]
    async fn initialize_answers_with_the_telegram_server_info() {
        let body = br#"{"jsonrpc":"2.0","id":7,"method":"initialize"}"#;
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, true, &api, &test_ctx()).await;
        assert_eq!(v["id"], 7);
        assert_eq!(v["result"]["serverInfo"]["name"], SERVER_NAME);
        assert_eq!(v["result"]["serverInfo"]["name"], "telegram");
    }

    #[tokio::test]
    async fn tools_list_serves_all_21_schemas() {
        let body = br#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#;
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, true, &api, &test_ctx()).await;
        let tools = v["result"]["tools"].as_array().unwrap();
        assert_eq!(tools.len(), 21);
    }

    #[tokio::test]
    async fn tools_call_without_a_session_answers_the_verbatim_session_error() {
        let body = br#"{"jsonrpc":"2.0","id":2,"params":{"name":"tg_read","arguments":{"chat_id":5}},"method":"tools/call"}"#;
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, false, &api, &test_ctx()).await;
        let inner: Value =
            serde_json::from_str(v["result"]["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(inner["error"], "not authorized - run send-code/verify first");
    }

    #[tokio::test]
    async fn an_unknown_tool_name_is_a_json_rpc_error_not_a_business_error() {
        let body = br#"{"jsonrpc":"2.0","id":2,"params":{"name":"tg_delete_everything"},"method":"tools/call"}"#;
        let api = std::sync::Arc::new(crate::telegram::api::MockApi::new());
        let v = dispatch(body, true, &api, &test_ctx()).await;
        assert_eq!(v["error"]["code"], -32601);
    }

    pub fn test_ctx() -> ToolCtx {
        ToolCtx {
            storage: std::env::temp_dir().join("minisd-rpc-test"),
            archive_dir: std::env::temp_dir().join("minisd-rpc-test/archive"),
            tz_offset_secs: 0,
        }
    }
}

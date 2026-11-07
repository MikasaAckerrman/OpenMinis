//! [M-R1] The vault gateway's contract-v1 JSON-RPC, echo skeleton.
//!
//! `initialize` answers with the "telegram" server info — the
//! `isAlive()` probe of `TelegramVaultManager` (TelegramVaultManager.kt
//! :85-107) checks exactly that string, so the skeleton is probe-green
//! before a single Telegram tool exists. `tools/list` is the HONEST
//! empty list until M-R2 maps the 16 read-only tools onto grammers —
//! advertising tools the skeleton cannot serve would be the same lie
//! the B8 gate teaches children not to tell.
//!
//! Bearer validation mirrors the B12 gate: any mismatch is HTTP 401.
//! JSON-RPC-level errors ride HTTP 200 inside the envelope.

use serde_json::{json, Value};

pub const SERVER_NAME: &str = "telegram";
pub const SERVER_VERSION: &str = "0.1.0-minisd";

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

/// Dispatch one JSON-RPC request. Returns (http_status, body). A
/// missing `id` echoes back as null (the skeleton answers every
/// request; batch/notification semantics arrive with M-R2 if needed).
pub fn dispatch(body: &[u8], authorized: bool) -> (u16, Value) {
    if !authorized {
        return (
            401,
            rpc_error(Value::Null, -32001, "unauthorized: bearer token mismatch"),
        );
    }
    let parsed: Value = match serde_json::from_slice(body) {
        Ok(v) => v,
        Err(e) => return (200, rpc_error(Value::Null, -32700, &format!("parse error: {e}"))),
    };
    let id = parsed.get("id").cloned().unwrap_or(Value::Null);
    let Some(method) = parsed.get("method").and_then(Value::as_str) else {
        return (200, rpc_error(id, -32600, "invalid request: missing method"));
    };
    match method {
        "initialize" => (
            200,
            json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": { "name": SERVER_NAME, "version": SERVER_VERSION }
                }
            }),
        ),
        "tools/list" => (
            200,
            json!({ "jsonrpc": "2.0", "id": id, "result": { "tools": [] } }),
        ),
        other => (200, rpc_error(id, -32601, &format!("method not found: {other}"))),
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

    #[test]
    fn unauthorized_is_http_401_with_error_envelope() {
        let body = br#"{"jsonrpc":"2.0","id":7,"method":"initialize"}"#;
        let (status, json) = dispatch(body, false);
        assert_eq!(status, 401);
        assert_eq!(json["error"]["code"], -32001);
    }

    #[test]
    fn initialize_answers_with_the_telegram_server_info() {
        let body = br#"{"jsonrpc":"2.0","id":7,"method":"initialize"}"#;
        let (status, json) = dispatch(body, true);
        assert_eq!(status, 200);
        assert_eq!(json["id"], 7);
        assert_eq!(json["result"]["serverInfo"]["name"], SERVER_NAME);
        assert_eq!(json["result"]["serverInfo"]["name"], "telegram");
        assert!(json["result"]["protocolVersion"].is_string());
    }

    #[test]
    fn tools_list_is_the_honest_empty_list_until_m_r2() {
        let body = br#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#;
        let (status, json) = dispatch(body, true);
        assert_eq!(status, 200);
        assert_eq!(json["result"]["tools"].as_array().map(Vec::len), Some(0));
    }

    #[test]
    fn unknown_method_is_a_json_rpc_error_on_http_200() {
        let body = br#"{"jsonrpc":"2.0","id":3,"method":"tools/call"}"#;
        let (status, json) = dispatch(body, true);
        assert_eq!(status, 200);
        assert_eq!(json["error"]["code"], -32601);
        assert_eq!(json["id"], 3);
    }

    #[test]
    fn a_request_without_a_method_is_invalid() {
        let body = br#"{"jsonrpc":"2.0","id":1}"#;
        let (status, json) = dispatch(body, true);
        assert_eq!(status, 200);
        assert_eq!(json["error"]["code"], -32600);
    }

    #[test]
    fn unparseable_body_is_a_parse_error_with_null_id() {
        let body = b"not json at all";
        let (status, json) = dispatch(body, true);
        assert_eq!(status, 200);
        assert_eq!(json["error"]["code"], -32700);
        assert!(json["id"].is_null());
    }

    #[test]
    fn a_missing_id_echoes_back_null() {
        let body = br#"{"jsonrpc":"2.0","method":"tools/list"}"#;
        let (_, json) = dispatch(body, true);
        assert!(json["id"].is_null());
    }
}

//! [M-R2] Tool parameter parsing. Every "missing required parameter"
//! failure must render the verbatim legacy string of telegram.py:1434-1440:
//! `{"error": "missing required parameters: chat_id"}`.

use serde_json::Value;

/// The one schema-level failure text (plural when several params miss).
pub struct MissingParams(pub Vec<String>);

impl MissingParams {
    pub fn error_text(&self) -> String {
        format!("missing required parameters: {}", self.0.join(", "))
    }
}

pub fn req_i64(params: &Value, name: &str, missing: &mut Vec<String>) -> Option<i64> {
    match params.get(name) {
        Some(v) => match v.as_i64() {
            Some(n) => Some(n),
            None => {
                missing.push(name.to_string());
                None
            }
        },
        None => {
            missing.push(name.to_string());
            None
        }
    }
}

pub fn req_i32(params: &Value, name: &str, missing: &mut Vec<String>) -> Option<i32> {
    req_i64(params, name, missing).map(|n| n as i32)
}

pub fn req_str<'a>(params: &'a Value, name: &str, missing: &mut Vec<String>) -> Option<String> {
    match params.get(name).and_then(Value::as_str) {
        Some(s) => Some(s.to_string()),
        None => {
            missing.push(name.to_string());
            None
        }
    }
}

pub fn opt_i64(params: &Value, name: &str) -> Option<i64> {
    params.get(name).and_then(Value::as_i64)
}

pub fn opt_i32(params: &Value, name: &str) -> Option<i32> {
    opt_i64(params, name).map(|n| n as i32)
}

#[allow(dead_code)]
pub fn opt_str(params: &Value, name: &str) -> Option<String> {
    params.get(name).and_then(Value::as_str).map(String::from)
}

pub fn opt_i64_list(params: &Value, name: &str) -> Option<Vec<i64>> {
    params.get(name).and_then(Value::as_array).map(|arr| {
        arr.iter()
            .filter_map(Value::as_i64)
            .collect::<Vec<i64>>()
    })
}

/// Coerce a bool that may arrive as true/false, "true"/"false",
/// 1/0 — the agent-side callers are models, be liberal.
#[allow(dead_code)]
pub fn opt_bool(params: &Value, name: &str) -> Option<bool> {
    match params.get(name) {
        Some(Value::Bool(b)) => Some(*b),
        Some(Value::Number(n)) => n.as_i64().map(|n| n != 0),
        Some(Value::String(s)) => match s.as_str() {
            "true" | "1" | "yes" => Some(true),
            "false" | "0" | "no" => Some(false),
            _ => None,
        },
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn a_missing_required_param_renders_the_verbatim_error() {
        let params = json!({"limit": 5});
        let mut missing = Vec::new();
        req_i64(&params, "chat_id", &mut missing);
        let err = MissingParams(missing);
        assert_eq!(err.error_text(), "missing required parameters: chat_id");
    }

    #[test]
    fn several_missing_params_are_listed_in_order() {
        let params = json!({});
        let mut missing = Vec::new();
        req_i64(&params, "chat_id", &mut missing);
        req_str(&params, "query", &mut missing);
        assert_eq!(
            MissingParams(missing).error_text(),
            "missing required parameters: chat_id, query"
        );
    }

    #[test]
    fn wrong_types_count_as_missing() {
        let params = json!({"chat_id": "not a number"});
        let mut missing = Vec::new();
        assert!(req_i64(&params, "chat_id", &mut missing).is_none());
        assert_eq!(missing, vec!["chat_id".to_string()]);
    }

    #[test]
    fn optional_helpers_parse_liberally() {
        let params = json!({"offset_id": 42, "flag": "true", "ids": [1, -100, "7"]});
        assert_eq!(opt_i32(&params, "offset_id"), Some(42));
        assert_eq!(opt_i32(&params, "nope"), None);
        assert_eq!(opt_bool(&params, "flag"), Some(true));
        assert_eq!(opt_i64_list(&params, "ids"), Some(vec![1, -100]));
    }
}

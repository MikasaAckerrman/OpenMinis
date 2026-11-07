//! [M-R2] Domain types on the mock/boundary: everything the tool
//! handlers and the golden tests see, nothing grammers-specific. The
//! `GrammersApi` impl maps MTProto onto these; the legacy daemon's
//! JSON shapes are defined against these.

use crate::telegram::whitelist::ChatType;
#[allow(unused_imports)]
pub use crate::telegram::format::{FormattedMessage, MediaKind, RawMessage};
use serde::{Deserialize, Serialize};

/// The chat-class token family of `blocked_types` and the `"type"`
/// fields of the legacy JSON (uppercase, spec §2.1/§3.6).
pub type ChatKind = ChatType;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DialogInfo {
    pub id: i64,
    pub title: String,
    pub kind: ChatKind,
    /// @username without the @, when present.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(default)]
    pub unread_count: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct UserInfo {
    #[serde(rename = "userId")]
    pub user_id: i64,
    pub username: String,
    #[serde(rename = "firstName")]
    pub first_name: String,
    #[serde(rename = "lastName")]
    pub last_name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ChatFull {
    pub id: i64,
    pub title: String,
    pub kind: ChatKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub username: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub members: Option<i32>,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub bio: String,
}

/// A global-search hit (§3.7): the legacy shape carries its own
/// compact text, not the full `_fmt_msg`.
#[derive(Debug, Clone, Serialize)]
pub struct GlobalHit {
    pub chat: String,
    pub chat_id: i64,
    pub msg_id: i32,
    pub date: i32,
    pub text: String,
}

/// A downloaded media file report (§3.15).
#[derive(Debug, Clone, Serialize)]
pub struct MediaFile {
    pub file: String,
    pub size_bytes: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_info_serializes_the_legacy_camel_case() {
        let u = UserInfo {
            user_id: 7,
            username: "johndoe".into(),
            first_name: "John".into(),
            last_name: "Doe".into(),
        };
        let json = serde_json::to_value(&u).unwrap();
        assert!(json.get("userId").is_some());
        assert!(json.get("firstName").is_some());
        assert!(json.get("lastName").is_some());
        assert_eq!(json["username"], "johndoe");
    }

    #[test]
    fn dialog_info_roundtrips_through_json() {
        let d = DialogInfo {
            id: -100_500,
            title: "Channel".into(),
            kind: ChatType::Channel,
            username: Some("chan".into()),
            unread_count: 3,
        };
        let text = serde_json::to_string(&d).unwrap();
        let back: DialogInfo = serde_json::from_str(&text).unwrap();
        assert_eq!(back, d);
    }
}

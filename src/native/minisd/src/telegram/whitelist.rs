//! [M-R2] The whitelist/blacklist gate — exact semantics of
//! telegram.py:251-267 (docs/M_R2_TOOL_MAPPING.md §2.1), including the
//! verbatim error strings the chat tooling renders.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum ChatType {
    Private,
    Bot,
    Group,
    Channel,
    Supergroup,
}

impl ChatType {
    /// The uppercase token family of `blocked_types` (spec example:
    /// `"blocked_types": ["BOT", "PRIVATE"]`).
    pub fn token(&self) -> &'static str {
        match self {
            ChatType::Private => "PRIVATE",
            ChatType::Bot => "BOT",
            ChatType::Group => "GROUP",
            ChatType::Channel => "CHANNEL",
            ChatType::Supergroup => "SUPERGROUP",
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct WhitelistConfig {
    pub mode: Option<String>,
    #[serde(default)]
    pub allowed: Vec<i64>,
    #[serde(default)]
    pub blocked: Vec<i64>,
    #[serde(default)]
    pub blocked_types: Vec<String>,
}

/// The verdict of one chat against one config.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Allowed,
    NotInWhitelist,
    BlockedId,
    BlockedType,
}

impl Verdict {
    /// The verbatim legacy error (the chat-facing text), or None.
    pub fn error_text(&self, chat_id: i64, chat_type: &str) -> Option<String> {
        match self {
            Verdict::Allowed => None,
            Verdict::NotInWhitelist => Some(format!(
                "Chat {chat_id} is not in whitelist. Add it to tg_whitelist.json."
            )),
            Verdict::BlockedId => Some(format!("Chat {chat_id} is blocked in blacklist.")),
            Verdict::BlockedType => Some(format!(
                "Chat type {chat_type} is blocked in blacklist (blocked_types)."
            )),
        }
    }
}

impl WhitelistConfig {
    /// No file at all — setup mode, everything allowed (§2.1 rule 1).
    /// A MALFORMED file is also setup mode: the legacy daemon answered
    /// permission errors by allowing, and silently locking the agent
    /// out of every chat over a typo would be a worse failure mode.
    /// The parse problem surfaces through a `note` on the verdict path.
    pub fn from_json(text: Option<&str>) -> (WhitelistConfig, Option<String>) {
        match text {
            None => (WhitelistConfig::default(), None),
            Some(raw) => match serde_json::from_str(raw) {
                Ok(cfg) => (cfg, None),
                Err(e) => (WhitelistConfig::default(), Some(format!("tg_whitelist.json parse error, setup mode applies: {e}"))),
            },
        }
    }

    /// The gate itself. Rule order mirrors the legacy code: mode first,
    /// then the blacklist checks inside `blacklist`.
    pub fn check(&self, chat_id: i64, chat_type: ChatType) -> Verdict {
        match self.mode.as_deref() {
            Some("whitelist") => {
                if self.allowed.contains(&chat_id) {
                    Verdict::Allowed
                } else {
                    Verdict::NotInWhitelist
                }
            }
            Some("blacklist") => {
                if self.blocked.contains(&chat_id) {
                    return Verdict::BlockedId;
                }
                if self.blocked_types.iter().any(|t| t == chat_type.token()) {
                    return Verdict::BlockedType;
                }
                Verdict::Allowed
            }
            // No mode (or an unknown one) — setup mode, allowed.
            _ => Verdict::Allowed,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_config_is_setup_mode_everything_allowed() {
        let (cfg, note) = WhitelistConfig::from_json(None);
        assert!(note.is_none());
        assert_eq!(cfg.check(-100_123, ChatType::Channel), Verdict::Allowed);
    }

    #[test]
    fn a_malformed_config_degrades_to_setup_mode_with_a_note() {
        let (cfg, note) = WhitelistConfig::from_json(Some("{not json"));
        assert!(note.is_some());
        assert_eq!(cfg.check(1, ChatType::Private), Verdict::Allowed);
    }

    #[test]
    fn whitelist_mode_allows_only_listed_ids_with_the_verbatim_error() {
        let cfg = WhitelistConfig::from_json(Some(
            r#"{"mode": "whitelist", "allowed": [12345678, -10012345678]}"#,
        ))
        .0;
        assert_eq!(cfg.check(12_345_678, ChatType::Private), Verdict::Allowed);
        assert_eq!(cfg.check(-100_123_456_78, ChatType::Channel), Verdict::Allowed);
        let verdict = cfg.check(987_654_321, ChatType::Channel);
        assert_eq!(verdict, Verdict::NotInWhitelist);
        assert_eq!(
            verdict.error_text(987_654_321, "CHANNEL").unwrap(),
            "Chat 987654321 is not in whitelist. Add it to tg_whitelist.json."
        );
    }

    #[test]
    fn blacklist_mode_blocks_listed_ids_verbatim() {
        let cfg = WhitelistConfig::from_json(Some(
            r#"{"mode": "blacklist", "blocked": [98765432]}"#,
        ))
        .0;
        let verdict = cfg.check(98_765_432, ChatType::Bot);
        assert_eq!(verdict, Verdict::BlockedId);
        assert_eq!(
            verdict.error_text(98_765_432, "BOT").unwrap(),
            "Chat 98765432 is blocked in blacklist."
        );
    }

    #[test]
    fn blacklist_mode_blocks_types_verbatim() {
        let cfg = WhitelistConfig::from_json(Some(
            r#"{"mode": "blacklist", "blocked_types": ["BOT", "PRIVATE"]}"#,
        ))
        .0;
        let verdict = cfg.check(1, ChatType::Bot);
        assert_eq!(verdict, Verdict::BlockedType);
        assert_eq!(
            verdict.error_text(1, "BOT").unwrap(),
            "Chat type BOT is blocked in blacklist (blocked_types)."
        );
        // An id that is not blocked and a type that is not in
        // blocked_types passes through the blacklist gate.
        assert_eq!(cfg.check(2, ChatType::Channel), Verdict::Allowed);
    }

    #[test]
    fn an_unknown_mode_is_setup_mode() {
        let cfg = WhitelistConfig::from_json(Some(r#"{"mode": "paranoia"}"#)).0;
        assert_eq!(cfg.check(7, ChatType::Group), Verdict::Allowed);
    }

    #[test]
    fn blocked_id_takes_priority_over_blocked_type() {
        let cfg = WhitelistConfig::from_json(Some(
            r#"{"mode": "blacklist", "blocked": [5], "blocked_types": ["BOT"]}"#,
        ))
        .0;
        // id 5 blocked AND a bot — the id branch answers (legacy order).
        assert_eq!(cfg.check(5, ChatType::Bot), Verdict::BlockedId);
    }
}

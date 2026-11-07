//! [M-R2] The canonical message format — the structural equivalent of
//! `_fmt_msg` (telegram.py:268-298, docs/M_R2_TOOL_MAPPING.md §2.3).
//! Every tool that returns messages serializes through here, so the
//! golden contracts of the legacy daemon hold without a python runtime.
//!
//! Dates: the legacy daemon formatted `datetime.fromtimestamp(date)`
//! inside PRoot with the app-seeded POSIX TZ "LCL-8" (UTC+8). minisd is
//! timezone-less by design (std has no TZ database), so the offset is a
//! startup parameter (`--tz-offset-hours`, app wiring passes 8 at M-R3)
//! applied through a fixed-offset civil converter — no chrono dep.

use serde::Serialize;

/// One media marker of the legacy `_fmt_msg` contract: the exact string
/// set of §2.3 — `[photo]`, `[video]`, `[document: name]`, `[audio]`,
/// `[voice]`, `[sticker: emoji]`, `[animation]`, `[location]`,
/// `[poll: question]`, or empty for text-only.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MediaKind {
    None,
    Photo,
    Video,
    Document(String),
    Audio,
    Voice,
    Sticker(String),
    Animation,
    Location,
    Poll(String),
}

impl MediaKind {
    pub fn marker(&self) -> String {
        match self {
            MediaKind::None => String::new(),
            MediaKind::Photo => "[photo]".into(),
            MediaKind::Video => "[video]".into(),
            MediaKind::Document(name) => format!("[document: {name}]"),
            MediaKind::Audio => "[audio]".into(),
            MediaKind::Voice => "[voice]".into(),
            MediaKind::Sticker(emoji) => format!("[sticker: {emoji}]"),
            MediaKind::Animation => "[animation]".into(),
            MediaKind::Location => "[location]".into(),
            MediaKind::Poll(question) => format!("[poll: {question}]"),
        }
    }
}

/// A message already normalized by the API layer — everything `_fmt_msg`
/// needs and nothing grammers-specific.
#[derive(Debug, Clone)]
pub struct RawMessage {
    pub id: i32,
    /// Unix seconds, as MTProto carries them (UTC).
    pub date: i64,
    /// Display name of the sender, e.g. "John Doe".
    pub sender_name: String,
    /// @username without the @, when the sender has one.
    pub sender_username: Option<String>,
    pub text: String,
    pub media: MediaKind,
    pub reply_to: Option<i32>,
}

/// The canonical `_fmt_msg` JSON shape.
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct FormattedMessage {
    pub id: i32,
    pub date: String,
    pub sender: String,
    pub text: String,
    pub media: String,
    pub reply_to: Option<i32>,
}

pub const TEXT_LIMIT: usize = 2_000;
pub const PREVIEW_LIMIT: usize = 120;

/// `John Doe (@johndoe)` with a username, `John Doe` without one —
/// byte-compatible with the legacy sender line.
pub fn format_sender(name: &str, username: Option<&str>) -> String {
    match username {
        Some(u) if !u.is_empty() => format!("{name} (@{u})"),
        _ => name.to_string(),
    }
}

/// Text body of `_fmt_msg`: the raw text, truncated to 2000 chars —
/// on a char boundary (the legacy python slice was byte-unsafe on
/// multi-byte text; the canonical format is defined by intent, and the
/// intent is readable text).
pub fn clamp_text(text: &str) -> String {
    if text.chars().count() <= TEXT_LIMIT {
        return text.to_string();
    }
    text.chars().take(TEXT_LIMIT).collect()
}

/// One line, at most 120 chars, for digest/scan previews (§3.8-3.9):
/// newlines collapse to a space, truncation is on a char boundary.
pub fn clamp_preview(text: &str) -> String {
    let one_line: String = text
        .chars()
        .map(|c| if c == '\n' || c == '\r' || c == '\t' { ' ' } else { c })
        .collect();
    let collapsed: String = one_line
        .split(' ')
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if collapsed.chars().count() <= PREVIEW_LIMIT {
        return collapsed;
    }
    let cut: String = collapsed.chars().take(PREVIEW_LIMIT).collect();
    format!("{cut}…")
}

/// `2026-11-07T18:42:00` — naive-ISO at the fixed offset, the
/// `datetime.fromtimestamp` shape of the legacy daemon (UTC+8 on the
/// device). Leap years and the epoch are unit-tested.
pub fn format_date(unix_secs: i64, offset_secs: i32) -> String {
    let shifted = unix_secs + offset_secs as i64;
    let days = shifted.div_euclid(86_400);
    let secs_of_day = shifted.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}",
        secs_of_day / 3_600,
        (secs_of_day % 3_600) / 60,
        secs_of_day % 60
    )
}

/// Howard Hinnant's `civil_from_days` — days since 1970-01-01 to
/// (year, month, day) in the proleptic Gregorian calendar. Public
/// domain algorithm, hand-carried because the dependency budget says
/// no chrono.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// The `_fmt_msg` conversion itself.
pub fn format_message(raw: &RawMessage, offset_secs: i32) -> FormattedMessage {
    FormattedMessage {
        id: raw.id,
        date: format_date(raw.date, offset_secs),
        sender: format_sender(&raw.sender_name, raw.sender_username.as_deref()),
        text: clamp_text(&raw.text),
        media: raw.media.marker(),
        reply_to: raw.reply_to,
    }
}

/// Hand-rolled extraction of `https?://` links (spec §3.13) — the
/// regex-free twin of the legacy `re.findall(r"https?://\S+")`. A link
/// runs to the first whitespace or a closing bracket/quote.
pub fn extract_links(text: &str) -> Vec<String> {
    let mut links = Vec::new();
    let bytes = text.as_bytes();
    let mut i = 0;
    while i + 8 <= bytes.len() {
        let rest = &text[i..];
        let start_offset = if rest.starts_with("https://") {
            8
        } else if rest.starts_with("http://") {
            7
        } else {
            i += 1;
            continue;
        };
        let start = i;
        i += start_offset;
        while i < bytes.len() {
            let c = text[i..].chars().next().unwrap();
            if c.is_whitespace() || matches!(c, ')' | ']' | '}' | '"' | '\'' | '>' | '<') {
                break;
            }
            i += c.len_utf8();
        }
        let link = &text[start..i];
        // A bare protocol tail ("https://." or "https://") is not a link.
        if link.len() > start_offset {
            links.push(link.to_string());
        }
    }
    links
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_formats_to_1970_01_01() {
        assert_eq!(format_date(0, 0), "1970-01-01T00:00:00");
    }

    #[test]
    fn known_instant_matches_the_golden_shape() {
        // 1_794_048_120 = 2026-11-07T10:42:00Z (anchored externally:
        // day 20764 since the epoch is 2026-11-07, +36 000 + 2 520 s)
        // == 18:42:00 at +8 — the spec §2.3 example instant.
        let ts = 1_794_048_120;
        let utc = format_date(ts, 0);
        let plus8 = format_date(ts, 8 * 3_600);
        assert_eq!(utc, "2026-11-07T10:42:00");
        assert_eq!(plus8, "2026-11-07T18:42:00");
    }

    #[test]
    fn leap_day_is_preserved() {
        // 2024-02-29 12:00:00 UTC = 1709208000.
        assert_eq!(format_date(1_709_208_000, 0), "2024-02-29T12:00:00");
    }

    #[test]
    fn negative_epoch_stays_gregorian() {
        assert_eq!(format_date(-1, 0), "1969-12-31T23:59:59");
        assert_eq!(format_date(-86_400, 0), "1969-12-31T00:00:00");
    }

    #[test]
    fn offset_of_8_hours_rolls_the_day_forward() {
        // 2026-11-07 20:00 UTC -> +8 is 2026-11-08 04:00.
        let ts = civil_to_unix(2026, 11, 7, 20, 0, 0);
        assert_eq!(format_date(ts, 8 * 3_600), "2026-11-08T04:00:00");
    }

    /// Inverse helper for tests only (days_from_civil, same algorithm).
    fn civil_to_unix(y: i64, m: u32, d: u32, hh: i64, mm: i64, ss: i64) -> i64 {
        let y = if m <= 2 { y - 1 } else { y };
        let era = y.div_euclid(400);
        let yoe = y.rem_euclid(400);
        let mp = if m > 2 { m - 3 } else { m + 9 } as i64;
        let doy = (153 * mp + 2) / 5 + d as i64 - 1;
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        let days = era * 146_097 + doe - 719_468;
        days * 86_400 + hh * 3_600 + mm * 60 + ss
    }

    #[test]
    fn sender_line_matches_the_legacy_shape() {
        assert_eq!(format_sender("John Doe", Some("johndoe")), "John Doe (@johndoe)");
        assert_eq!(format_sender("John Doe", None), "John Doe");
        assert_eq!(format_sender("John Doe", Some("")), "John Doe");
    }

    #[test]
    fn text_is_truncated_on_char_boundaries_at_2000() {
        let long = "ж".repeat(2_500);
        let clamped = clamp_text(&long);
        assert_eq!(clamped.chars().count(), 2_000);
        // Multi-byte safety: the boundary is a char boundary by construction.
        assert!(clamped.ends_with('ж'));
        assert_eq!(clamp_text("short"), "short");
    }

    #[test]
    fn preview_is_one_line_max_120_chars() {
        let multi = "line one\nline two\t\ttabbed   spaces\n\nend";
        assert_eq!(clamp_preview(multi), "line one line two tabbed spaces end");
        let long = "a".repeat(300);
        let preview = clamp_preview(&long);
        assert_eq!(preview.chars().count(), 121); // 120 + the ellipsis
        assert!(preview.ends_with('…'));
    }

    #[test]
    fn media_markers_match_the_contract_set() {
        assert_eq!(MediaKind::None.marker(), "");
        assert_eq!(MediaKind::Photo.marker(), "[photo]");
        assert_eq!(MediaKind::Video.marker(), "[video]");
        assert_eq!(MediaKind::Document("file.pdf".into()).marker(), "[document: file.pdf]");
        assert_eq!(MediaKind::Audio.marker(), "[audio]");
        assert_eq!(MediaKind::Voice.marker(), "[voice]");
        assert_eq!(MediaKind::Sticker("👍".into()).marker(), "[sticker: 👍]");
        assert_eq!(MediaKind::Animation.marker(), "[animation]");
        assert_eq!(MediaKind::Location.marker(), "[location]");
        assert_eq!(MediaKind::Poll("Question".into()).marker(), "[poll: Question]");
    }

    #[test]
    fn fmt_msg_golden_shape() {
        let raw = RawMessage {
            id: 12_345,
            date: 1_794_048_120,
            sender_name: "John Doe".into(),
            sender_username: Some("johndoe".into()),
            text: "Текст сообщения до 2000 символов...".into(),
            media: MediaKind::Photo,
            reply_to: Some(12_340),
        };
        let fmt = format_message(&raw, 8 * 3_600);
        let json = serde_json::to_value(&fmt).unwrap();
        assert_eq!(json["id"], 12_345);
        assert_eq!(json["sender"], "John Doe (@johndoe)");
        assert_eq!(json["media"], "[photo]");
        assert_eq!(json["reply_to"], 12_340);
        assert!(json["date"].as_str().unwrap().contains('T'));
        // All six legacy fields present — map equality ignores key order
        // (serde_json's object is a BTreeMap; consumers parse JSON, they
        // do not diff bytes).
        assert_eq!(json.as_object().unwrap().len(), 6);
    }

    #[test]
    fn links_are_extracted_without_regex() {
        assert_eq!(
            extract_links("see https://example.com/a?b=1 and http://x.org"),
            vec!["https://example.com/a?b=1", "http://x.org"]
        );
        assert_eq!(extract_links("(https://in.parens)"), vec!["https://in.parens"]);
        assert_eq!(extract_links("no links here"), Vec::<String>::new());
        // A bare protocol with no host is not a link.
        assert_eq!(extract_links("https:// trailing"), Vec::<String>::new());
        assert_eq!(extract_links("\"https://quoted.example\""), vec!["https://quoted.example"]);
    }
}

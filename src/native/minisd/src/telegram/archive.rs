//! [M-R2] The archive store (rusqlite, bundled SQLite): the
//! `tg_archive_chat` target at `<archive_dir>/archive.db` — the host
//! path serving the guest bind `/var/minis/shared/tg`. A single flat
//! table with the canonical `_fmt_msg` columns, keyed by (chat, msg).

use rusqlite::Connection;
use serde_json::json;
use std::path::Path;

pub const ARCHIVE_DB_NAME: &str = "archive.db";

/// The schema, created idempotently on every open.
pub const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS archived_messages (
    chat_id  INTEGER NOT NULL,
    msg_id   INTEGER NOT NULL,
    date     TEXT NOT NULL,
    sender   TEXT NOT NULL,
    text     TEXT NOT NULL,
    media    TEXT NOT NULL,
    reply_to INTEGER,
    PRIMARY KEY (chat_id, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_archived_chat_date
    ON archived_messages (chat_id, date);
";

pub fn open(archive_dir: &Path) -> Result<Connection, String> {
    std::fs::create_dir_all(archive_dir)
        .map_err(|e| format!("minisd error: cannot create archive dir: {e}"))?;
    let conn = Connection::open(archive_dir.join(ARCHIVE_DB_NAME))
        .map_err(|e| format!("minisd error: cannot open archive.db: {e}"))?;
    conn.execute_batch(SCHEMA)
        .map_err(|e| format!("minisd error: archive schema failed: {e}"))?;
    Ok(conn)
}

#[derive(Debug, Clone, PartialEq)]
pub struct ArchiveRow {
    pub chat_id: i64,
    pub msg_id: i32,
    pub date: String,
    pub sender: String,
    pub text: String,
    pub media: String,
    pub reply_to: Option<i32>,
}

/// INSERT OR REPLACE — re-archiving a chat refreshes its rows.
pub fn upsert(conn: &Connection, rows: &[ArchiveRow]) -> Result<usize, String> {
    let mut stmt = conn
        .prepare("INSERT OR REPLACE INTO archived_messages (chat_id, msg_id, date, sender, text, media, reply_to) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)")
        .map_err(|e| format!("minisd error: archive insert failed: {e}"))?;
    let mut n = 0;
    for r in rows {
        let changed = stmt
            .execute(rusqlite::params![r.chat_id, r.msg_id, r.date, r.sender, r.text, r.media, r.reply_to])
            .map_err(|e| format!("minisd error: archive insert failed: {e}"))?;
        n += changed;
    }
    Ok(n)
}

/// How many rows the chat already has.
pub fn total_for_chat(conn: &Connection, chat_id: i64) -> Result<usize, String> {
    conn.query_row(
        "SELECT COUNT(*) FROM archived_messages WHERE chat_id = ?1",
        rusqlite::params![chat_id],
        |row| row.get::<_, i64>(0),
    )
    .map(|n| n as usize)
    .map_err(|e| format!("minisd error: archive count failed: {e}"))
}

/// The rows of one chat, newest first — `tg_analyze_chat`'s source.
pub fn rows_for_chat(conn: &Connection, chat_id: i64, limit: usize) -> Result<Vec<ArchiveRow>, String> {
    let mut stmt = conn
        .prepare("SELECT chat_id, msg_id, date, sender, text, media, reply_to FROM archived_messages WHERE chat_id = ?1 ORDER BY msg_id DESC LIMIT ?2")
        .map_err(|e| format!("minisd error: archive read failed: {e}"))?;
    let rows = stmt
        .query_map(rusqlite::params![chat_id, limit as i64], |row| {
            Ok(ArchiveRow {
                chat_id: row.get(0)?,
                msg_id: row.get(1)?,
                date: row.get(2)?,
                sender: row.get(3)?,
                text: row.get(4)?,
                media: row.get(5)?,
                reply_to: row.get(6)?,
            })
        })
        .map_err(|e| format!("minisd error: archive read failed: {e}"))?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| format!("minisd error: archive read failed: {e}"))
}

/// Compact JSON for the analyze tool (top words + top senders).
pub fn analyze_summary(
    chat_id: i64,
    source: &str,
    rows: &[ArchiveRow],
    top: usize,
) -> serde_json::Value {
    let mut words: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    let mut senders: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    for r in rows {
        *senders.entry(r.sender.clone()).or_insert(0) += 1;
        // Tokenization without a regex dep: alphanumeric runs, length >= 4.
        let mut word = String::new();
        for c in r.text.chars().chain(std::iter::once(' ')) {
            if c.is_alphanumeric() {
                word.extend(c.to_lowercase());
            } else if !word.is_empty() {
                if word.chars().count() >= 4 {
                    *words.entry(word.clone()).or_insert(0) += 1;
                }
                word.clear();
            }
        }
    }
    let mut top_words: Vec<(String, usize)> = words.into_iter().collect();
    top_words.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    top_words.truncate(top);
    let mut top_senders: Vec<(String, usize)> = senders.into_iter().collect();
    top_senders.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    top_senders.truncate(top);
    json!({
        "chat_id": chat_id,
        "source": source,
        "messages_analyzed": rows.len(),
        "top_words": top_words.iter().map(|(w, c)| json!({"word": w, "count": c})).collect::<Vec<_>>(),
        "top_senders": top_senders.iter().map(|(s, c)| json!({"sender": s, "count": c})).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn conn() -> Connection {
        Connection::open_in_memory().unwrap()
    }

    #[test]
    fn the_schema_opens_idempotently() {
        let c = conn();
        c.execute_batch(SCHEMA).unwrap();
        c.execute_batch(SCHEMA).unwrap(); // second open is a no-op
    }

    #[test]
    fn upsert_is_a_refresh_not_a_duplicate() {
        let c = conn();
        c.execute_batch(SCHEMA).unwrap();
        let rows = vec![ArchiveRow {
            chat_id: -100_1,
            msg_id: 5,
            date: "2026-11-07T18:42:00".into(),
            sender: "John Doe (@johndoe)".into(),
            text: "hello".into(),
            media: "[photo]".into(),
            reply_to: None,
        }];
        assert_eq!(upsert(&c, &rows).unwrap(), 1);
        // Same key, new text: the row is REPLACED.
        let refreshed = vec![ArchiveRow { text: "edited".into(), ..rows[0].clone() }];
        upsert(&c, &refreshed).unwrap();
        assert_eq!(total_for_chat(&c, -100_1).unwrap(), 1);
        let back = rows_for_chat(&c, -100_1, 10).unwrap();
        assert_eq!(back[0].text, "edited");
    }

    #[test]
    fn rows_read_newest_first_and_bounded() {
        let c = conn();
        c.execute_batch(SCHEMA).unwrap();
        let rows: Vec<ArchiveRow> = (1..=30)
            .map(|i| ArchiveRow {
                chat_id: 7,
                msg_id: i,
                date: format!("2026-01-{i:02}T00:00:00"),
                sender: "s".into(),
                text: format!("text {i}"),
                media: String::new(),
                reply_to: None,
            })
            .collect();
        upsert(&c, &rows).unwrap();
        let page = rows_for_chat(&c, 7, 10).unwrap();
        assert_eq!(page.len(), 10);
        assert_eq!(page[0].msg_id, 30);
        assert_eq!(page[9].msg_id, 21);
    }

    #[test]
    fn the_analyze_summary_counts_words_and_senders() {
        let mk = |id: i32, sender: &str, text: &str| ArchiveRow {
            chat_id: 1,
            msg_id: id,
            date: "2026-01-01T00:00:00".into(),
            sender: sender.into(),
            text: text.into(),
            media: String::new(),
            reply_to: None,
        };
        let rows = vec![
            mk(1, "Ann", "release deadline release friday"),
            mk(2, "Ann", "release notes are ready"),
            mk(3, "Bob", "я тоже за release"),
        ];
        let summary = analyze_summary(1, "archive", &rows, 5);
        assert_eq!(summary["messages_analyzed"], 3);
        assert_eq!(summary["top_words"][0]["word"], "release");
        assert_eq!(summary["top_words"][0]["count"], 4);
        assert_eq!(summary["top_senders"][0]["sender"], "Ann");
        assert_eq!(summary["top_senders"][0]["count"], 2);
        // Short tokens (<4 chars) never enter the word list.
        let words: Vec<&str> = summary["top_words"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|w| w["word"].as_str())
            .collect();
        assert!(!words.contains(&"за"));
    }
}

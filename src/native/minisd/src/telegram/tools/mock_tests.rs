//! [M-R2] The mock/golden gate (spec §6): every tool driven through the
//! REAL dispatcher (`tools::call`) against the `MockApi` seam, plus
//! golden fixtures for the legacy JSON shapes. Unit tests live inside
//! the binary crate on purpose: integration tests (tests/) cannot see
//! a bin-only crate's modules.

use super::{call, ToolCtx, ToolError};
use crate::telegram::api::MockApi;
use crate::telegram::format::{format_message, MediaKind, RawMessage};
use crate::telegram::types::{ChatFull, DialogInfo, GlobalHit, MediaFile, UserInfo};
use crate::telegram::whitelist::ChatType;
use serde_json::{json, Value};
use std::sync::Arc;

// ---------- helpers ----------

fn fresh_dir(tag: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("minisd-mock-{}-{tag}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn ctx(tag: &str) -> ToolCtx {
    let dir = fresh_dir(tag);
    ToolCtx {
        archive_dir: dir.join("archive"),
        storage: dir.clone(),
        tz_offset_secs: 0,
        workspace: Some(dir),
    }
}

async fn run(
    api: &Arc<MockApi>,
    ctx: &ToolCtx,
    name: &str,
    args: Value,
) -> Result<Value, ToolError> {
    call(api, ctx, true, name, &json!({"arguments": args})).await
}

/// 1_794_048_120 == 2026-11-07T10:42:00Z (anchored in format.rs tests).
const T: i64 = 1_794_048_120;

fn dlg(id: i64, title: &str, kind: ChatType, unread: i32) -> DialogInfo {
    DialogInfo {
        id,
        title: title.into(),
        kind,
        username: None,
        unread_count: unread,
    }
}

fn raw(id: i32, date: i64, sender: &str, text: &str) -> RawMessage {
    RawMessage {
        id,
        date,
        sender_name: sender.into(),
        sender_username: None,
        text: text.into(),
        media: MediaKind::None,
        reply_to: None,
    }
}

fn channel_api() -> MockApi {
    MockApi::new().with_dialogs(vec![
        dlg(-100_500, "News", ChatType::Channel, 0),
        dlg(-100_600, "Work", ChatType::Supergroup, 3),
        dlg(100, "Alice", ChatType::Private, 1),
        dlg(200, "Helper", ChatType::Bot, 0),
    ])
}

fn fixture(name: &str) -> Value {
    let path = format!(
        "{}/tests/fixtures/{name}",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn golden_fmt_msg_shape_is_byte_compatible() {
    let raw = RawMessage {
        id: 12_345,
        date: T,
        sender_name: "John Doe".into(),
        sender_username: Some("johndoe".into()),
        text: "Текст сообщения до 2000 символов...".into(),
        media: MediaKind::Photo,
        reply_to: Some(12_340),
    };
    let produced = serde_json::to_value(format_message(&raw, 8 * 3_600)).unwrap();
    assert_eq!(produced, fixture("golden_fmt_msg.json"));
}

#[tokio::test]
async fn golden_tg_read_response_shape() {
    let api = Arc::new(
        channel_api().with_messages(-100_500, vec![RawMessage {
            id: 12_345,
            date: T,
            sender_name: "John Doe".into(),
            sender_username: Some("johndoe".into()),
            text: "Текст сообщения до 2000 символов...".into(),
            media: MediaKind::Photo,
            reply_to: Some(12_340),
        }]),
    );
    let ctx = ctx("golden-read");
    let out = run(&api, &ctx, "tg_read", json!({"chat_id": -100_500})).await.unwrap();
    let expected = fixture("golden_read_response.json");
    assert_eq!(out, expected);
}

// ---------- 1. tg_whoami ----------

#[tokio::test]
async fn tg_whoami_returns_the_profile_and_writes_accounts_json() {
    let api = Arc::new(MockApi {
        me: Some(UserInfo {
            user_id: 42,
            username: "johndoe".into(),
            first_name: "John".into(),
            last_name: "Doe".into(),
        }),
        ..MockApi::new()
    });
    let ctx = ctx("whoami");
    let out = run(&api, &ctx, "tg_whoami", json!({})).await.unwrap();
    assert_eq!(out["userId"], 42);
    assert_eq!(out["username"], "johndoe");
    let accounts = std::fs::read_to_string(ctx.storage.join("accounts.json")).unwrap();
    assert!(accounts.contains("\"userId\": 42"));
}

// ---------- 2. tg_list_chats ----------

#[tokio::test]
async fn tg_list_chats_warms_the_cache_and_reports_it() {
    let api = Arc::new(channel_api());
    let ctx = ctx("list-chats");
    let out = run(&api, &ctx, "tg_list_chats", json!({})).await.unwrap();
    assert_eq!(out["total"], 4);
    assert_eq!(out["whitelist_active"], false);
    // The cache file now exists (§2.2).
    assert!(ctx.storage.join("tg_chats_cache.json").is_file());
}

#[tokio::test]
async fn tg_list_chats_applies_the_whitelist_modes() {
    let api = Arc::new(channel_api());
    let ctx1 = ctx("list-chats-wl");
    std::fs::write(
        ctx1.storage.join("tg_whitelist.json"),
        r#"{"mode": "whitelist", "allowed": [-100500]}"#,
    )
    .unwrap();
    let out = run(&api, &ctx1, "tg_list_chats", json!({})).await.unwrap();
    assert_eq!(out["total"], 1);
    assert_eq!(out["whitelist_active"], true);
    assert_eq!(out["chats"][0]["title"], "News");

    let ctx2 = ctx("list-chats-bl");
    std::fs::write(
        ctx2.storage.join("tg_whitelist.json"),
        r#"{"mode": "blacklist", "blocked_types": ["BOT", "PRIVATE"]}"#,
    )
    .unwrap();
    let out = run(&Arc::new(channel_api()), &ctx2, "tg_list_chats", json!({})).await.unwrap();
    let blocked: Vec<&Value> = out["chats"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|c| c["blocked"] == json!(true))
        .collect();
    assert_eq!(blocked.len(), 2); // Alice (PRIVATE) + Helper (BOT)
}

// ---------- 3-4. tg_read / tg_search ----------

#[tokio::test]
async fn tg_read_serves_the_golden_page() {
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(3, T, "John Doe", "hello world"), raw(2, T - 60, "Ann", "earlier")],
    ));
    let ctx = ctx("read");
    let out = run(&api, &ctx, "tg_read", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["chat_id"], -100_500);
    assert_eq!(out["count"], 2);
    assert_eq!(out["messages"][0]["text"], "hello world");
    assert_eq!(out["messages"][0]["date"], "2026-11-07T10:42:00");
    assert_eq!(out["messages"][0]["sender"], "John Doe");
}

#[tokio::test]
async fn tg_read_without_chat_id_is_the_verbatim_schema_error() {
    let api = Arc::new(channel_api());
    let ctx = ctx("read-missing");
    let err = run(&api, &ctx, "tg_read", json!({"limit": 5})).await.unwrap_err();
    assert_eq!(err.0, "missing required parameters: chat_id");
}

#[tokio::test]
async fn tg_read_honors_the_whitelist_gate() {
    let api = Arc::new(channel_api().with_messages(-100_500, vec![raw(1, T, "x", "y")]));
    let ctx = ctx("read-wl");
    std::fs::write(
        ctx.storage.join("tg_whitelist.json"),
        r#"{"mode": "whitelist", "allowed": [-100600]}"#,
    )
    .unwrap();
    let err = run(&api, &ctx, "tg_read", json!({"chat_id": -100_500})).await.unwrap_err();
    assert_eq!(
        err.0,
        "Chat -100500 is not in whitelist. Add it to tg_whitelist.json."
    );
}

#[tokio::test]
async fn tg_search_returns_matches_with_the_query_echo() {
    let api = Arc::new(channel_api().with_search(
        -100_500,
        vec![raw(9, T, "John", "the release notes"), raw(8, T - 60, "Ann", "unrelated")],
    ));
    let ctx = ctx("search");
    let out = run(&api, &ctx, "tg_search", json!({"chat_id": -100_500, "query": "release"})).await.unwrap();
    assert_eq!(out["query"], "release");
    assert_eq!(out["count"], 1);
    assert_eq!(out["messages"][0]["text"], "the release notes");
}

// ---------- 5. tg_unread ----------

#[tokio::test]
async fn tg_unread_counts_only_nonzero_dialogs() {
    let api = Arc::new(channel_api());
    let ctx = ctx("unread");
    let out = run(&api, &ctx, "tg_unread", json!({})).await.unwrap();
    assert_eq!(out["total_unread"], 4); // Work 3 + Alice 1
    assert_eq!(out["unread_chats"].as_array().unwrap().len(), 2);
}

// ---------- 6. tg_get_chat ----------

#[tokio::test]
async fn tg_get_chat_serves_the_full_shape() {
    let api = Arc::new(channel_api().with_full(
        -100_500,
        ChatFull {
            id: -100_500,
            title: "News".into(),
            kind: ChatType::Channel,
            username: Some("news".into()),
            members: Some(1_024),
            description: "daily digest".into(),
            bio: "daily digest".into(),
        },
    ));
    let ctx = ctx("get-chat");
    let out = run(&api, &ctx, "tg_get_chat", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["title"], "News");
    assert_eq!(out["type"], "CHANNEL");
    assert_eq!(out["members"], 1_024);
    assert_eq!(out["description"], "daily digest");
}

// ---------- 7. tg_global_search ----------

#[tokio::test]
async fn tg_global_search_serves_the_compact_hits() {
    let api = Arc::new(channel_api().with_global(vec![GlobalHit {
        chat: "News".into(),
        chat_id: -100_500,
        msg_id: 77,
        date: 1_794_048_120_i32,
        text: "found it".into(),
    }]));
    let ctx = ctx("global");
    let out = run(&api, &ctx, "tg_global_search", json!({"query": "found"})).await.unwrap();
    assert_eq!(out["total"], 1);
    assert_eq!(out["results"][0]["chat"], "News");
    assert_eq!(out["results"][0]["chat_id"], -100_500);
}

// ---------- 8-9. digest / scan ----------

#[tokio::test]
async fn tg_channel_digest_clamps_previews_to_120_chars() {
    let long_text = "word ".repeat(60); // 300 chars
    let api = Arc::new(
        channel_api().with_messages(
            -100_500,
            vec![RawMessage { text: long_text.clone(), media: MediaKind::Photo, ..raw(5, T, "a", "") }],
        ),
    );
    let ctx = ctx("digest");
    let out = run(&api, &ctx, "tg_channel_digest", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["chat"], "News");
    let item = &out["digest"][0];
    assert_eq!(item["preview"].as_str().unwrap().chars().count(), 121);
    assert_eq!(item["has_media"], true);
}

#[tokio::test]
async fn tg_scan_channels_scans_only_channels_with_the_default_3_posts() {
    let api = Arc::new(
        channel_api()
            .with_messages(-100_500, vec![raw(3, T, "n", "one"), raw(2, T - 60, "n", "two"), raw(1, T - 120, "n", "three")])
            .with_messages(-100_600, vec![raw(9, T, "w", "work chatter")]),
    );
    let ctx = ctx("scan");
    let out = run(&api, &ctx, "tg_scan_channels", json!({})).await.unwrap();
    // Only the CHANNEL kind (-100_500) is scanned; the supergroup is not.
    assert_eq!(out["channels_scanned"], 1);
    assert_eq!(out["channels_with_content"], 1);
    assert!(out["digest"]["News"].is_object());
    assert!(out["digest"]["Work"].is_null());
}

// ---------- 10. tg_period_read ----------

#[tokio::test]
async fn tg_period_read_stops_at_the_date_from_boundary() {
    let nov7_noon = 1_794_009_600 + 43_200;
    let nov8 = 1_794_009_600 + 86_400;
    let nov5 = 1_794_009_600 - 2 * 86_400;
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(3, nov8, "a", "after"), raw(2, nov7_noon, "b", "inside"), raw(1, nov5, "c", "before")],
    ));
    let ctx = ctx("period");
    let out = run(
        &api,
        &ctx,
        "tg_period_read",
        json!({"chat_id": -100_500, "date_from": "2026-11-07", "date_to": "2026-11-07"}),
    )
    .await
    .unwrap();
    assert_eq!(out["count"], 1);
    assert_eq!(out["messages"][0]["text"], "inside");
    assert_eq!(out["note"], "stopped at the date_from boundary");
}

#[tokio::test]
async fn tg_period_read_rejects_an_inverted_range() {
    let api = Arc::new(channel_api());
    let ctx = ctx("period-bad");
    let err = run(
        &api,
        &ctx,
        "tg_period_read",
        json!({"chat_id": -100_500, "date_from": "2026-11-07", "date_to": "2026-11-06"}),
    )
    .await
    .unwrap_err();
    assert!(err.0.contains("date_to is before date_from"));
}

// ---------- 11. tg_replies ----------

#[tokio::test]
async fn tg_replies_returns_the_thread() {
    let api = Arc::new(channel_api().with_replies(
        -100_500,
        10,
        vec![raw(11, T, "Bob", "a reply"), raw(12, T + 30, "Cat", "another")],
    ));
    let ctx = ctx("replies");
    let out = run(&api, &ctx, "tg_replies", json!({"chat_id": -100_500, "message_id": 10})).await.unwrap();
    assert_eq!(out["message_id"], 10);
    assert_eq!(out["count"], 2);
    assert_eq!(out["replies"][0]["sender"], "Bob");
}

// ---------- 12. tg_stats ----------

#[tokio::test]
async fn tg_stats_counts_the_24h_window_of_the_sample() {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(2, now - 3_600, "a", "an hour ago"), raw(1, now - 3 * 86_400, "b", "three days ago")],
    ));
    let ctx = ctx("stats");
    let out = run(&api, &ctx, "tg_stats", json!({"limit": 1})).await.unwrap();
    assert_eq!(out["total_channels"], 1);
    let row = &out["stats"][0];
    assert_eq!(row["title"], "News");
    assert_eq!(row["recent_messages"], 1);
    assert!(row["last_activity"].is_string());
}

// ---------- 13. tg_link_search ----------

#[tokio::test]
async fn tg_link_search_extracts_links_from_matched_messages() {
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(4, T, "a", "look https://example.com/x now"), raw(3, T - 60, "b", "no links"), raw(2, T - 120, "c", "see http://b.org")],
    ));
    let ctx = ctx("links");
    let out = run(&api, &ctx, "tg_link_search", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["count"], 2);
    assert_eq!(out["messages_with_links"][0]["links"][0], "https://example.com/x");
    assert_eq!(out["messages_with_links"][1]["links"][0], "http://b.org");
}

// ---------- 14. tg_multi_search ----------

#[tokio::test]
async fn tg_multi_search_groups_by_title_and_counts() {
    let api = Arc::new(
        channel_api()
            .with_search(-100_500, vec![raw(1, T, "a", "needle here")])
            .with_search(-100_600, vec![raw(2, T, "b", "no match")]),
    );
    let ctx = ctx("multi");
    let out = run(
        &api,
        &ctx,
        "tg_multi_search",
        json!({"chat_ids": [-100_500, -100_600], "query": "needle"}),
    )
    .await
    .unwrap();
    assert_eq!(out["chats_searched"], 2);
    assert_eq!(out["chats_with_results"], 1);
    assert_eq!(out["total_messages"], 1);
    assert!(out["results"]["News"].is_array());
}

#[tokio::test]
async fn tg_multi_search_refuses_more_than_20_chats() {
    let api = Arc::new(channel_api());
    let ctx = ctx("multi-cap");
    let ids: Vec<i64> = (0..21).map(|i| -(100 + i)).collect();
    let err = run(&api, &ctx, "tg_multi_search", json!({"chat_ids": ids, "query": "x"})).await.unwrap_err();
    assert!(err.0.contains("at most 20"));
}

// ---------- 15. tg_download_media ----------

#[tokio::test]
async fn tg_download_media_writes_the_file_and_reports_the_size() {
    let api = Arc::new(channel_api().with_media(MediaFile {
        file: "dest".into(),
        size_bytes: 2_097_152,
    }));
    let ctx = ctx("media");
    let out = run(
        &api,
        &ctx,
        "tg_download_media",
        json!({"chat_id": -100_500, "message_id": 5, "file_name": "photo.jpg"}),
    )
    .await
    .unwrap();
    assert_eq!(out["size_bytes"], 2_097_152);
    let mb = out["size_mb"].as_f64().unwrap();
    assert!((mb - 2.0).abs() < 0.001);
    assert!(ctx.storage.join("media/photo.jpg").is_file());
}

// ---------- 16. tg_archive_chat ----------

#[tokio::test]
async fn tg_archive_chat_persists_and_re_archives_without_duplicates() {
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(2, T, "John", "second"), raw(1, T - 60, "Ann", "first")],
    ));
    let ctx = ctx("archive");
    let out = run(&api, &ctx, "tg_archive_chat", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["archived_messages"], 2);
    assert_eq!(out["total_archived"], 2);
    assert!(out["db_file"].as_str().unwrap().ends_with("archive.db"));

    // A re-archive refreshes, not duplicates (INSERT OR REPLACE).
    let out2 = run(&api, &ctx, "tg_archive_chat", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out2["archived_messages"], 2);
    assert_eq!(out2["total_archived"], 2);
    assert_eq!(out2["preexisting_rows"], 2);
}

// ---------- 17-20. parallel/new/chunked/parallel search ----------

#[tokio::test]
async fn tg_read_parallel_degrades_a_blocked_chat_into_errors() {
    let api = Arc::new(
        channel_api()
            .with_messages(-100_500, vec![raw(1, T, "a", "news item")])
            .dead(-100_600),
    );
    let ctx = ctx("parallel");
    std::fs::write(
        ctx.storage.join("tg_whitelist.json"),
        r#"{"mode": "blacklist", "blocked": [-100600]}"#,
    )
    .unwrap();
    let out = run(
        &api,
        &ctx,
        "tg_read_parallel",
        json!({"chat_ids": [-100_500, -100_600]}),
    )
    .await
    .unwrap();
    assert_eq!(out["chats_ok"], 1);
    assert_eq!(out["chats_failed"], 1);
    assert_eq!(out["errors"][0]["chat_id"], -100_600);
    assert_eq!(out["errors"][0]["error"], "Chat -100600 is blocked in blacklist.");
}

#[tokio::test]
async fn tg_read_new_advances_the_cursor_only_over_new_ids() {
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(5, T, "a", "newest"), raw(3, T - 60, "b", "older")],
    ));
    let ctx = ctx("cursor");
    let first = run(&api, &ctx, "tg_read_new", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(first["count"], 2);
    assert_eq!(first["cursor"]["previous"], 0);
    assert_eq!(first["cursor"]["current"], 5);
    // The cursor file now pins 5; the same page yields nothing new.
    let second = run(&Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(5, T, "a", "newest"), raw(3, T - 60, "b", "older")],
    )), &ctx, "tg_read_new", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(second["count"], 0);
    assert_eq!(second["cursor"]["current"], 5);
}

#[tokio::test]
async fn tg_read_chunked_returns_one_entry_per_window() {
    let api = Arc::new(channel_api().with_messages(-100_500, vec![raw(1, T, "a", "only real id")]));
    let ctx = ctx("chunked");
    let out = run(
        &api,
        &ctx,
        "tg_read_chunked",
        json!({"chat_id": -100_500, "chunk_size": 100, "windows": 3}),
    )
    .await
    .unwrap();
    assert_eq!(out["windows"].as_array().unwrap().len(), 3);
    // Real ids are far below the i32::MAX window walk — the windows
    // are honest empties.
    assert_eq!(out["total"], 0);
}

#[tokio::test]
async fn tg_search_parallel_matches_the_multi_contract() {
    let api = Arc::new(
        channel_api()
            .with_search(-100_500, vec![raw(1, T, "a", "parallel needle")])
            .with_search(700, vec![raw(2, T, "c", "another needle")]),
    );
    let ctx = ctx("search-parallel");
    let out = run(
        &api,
        &ctx,
        "tg_search_parallel",
        json!({"chat_ids": [-100_500, 700], "query": "needle"}),
    )
    .await
    .unwrap();
    assert_eq!(out["chats_searched"], 2);
    assert_eq!(out["chats_with_results"], 2);
    assert_eq!(out["total_messages"], 2);
}

// ---------- 21. tg_analyze_chat ----------

#[tokio::test]
async fn tg_analyze_chat_falls_back_to_live_when_not_archived() {
    let api = Arc::new(channel_api().with_messages(
        -100_500,
        vec![raw(1, T, "Ann", "deployment deployment deployment friday"), raw(2, T - 60, "Bob", "short")],
    ));
    let ctx = ctx("analyze-live");
    let out = run(&api, &ctx, "tg_analyze_chat", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["source"], "live");
    assert_eq!(out["top_words"][0]["word"], "deployment");
}

#[tokio::test]
async fn tg_analyze_chat_prefers_the_archive_when_present() {
    let api = Arc::new(channel_api());
    let ctx = ctx("analyze-archive");
    // Archive first…
    let archived_api = Arc::new(channel_api().with_messages(-100_500, vec![raw(1, T, "Ann", "archived words here archived")]));
    run(&archived_api, &ctx, "tg_archive_chat", json!({"chat_id": -100_500})).await.unwrap();
    // …then analyze with an EMPTY live api: the archive is the source.
    let out = run(&api, &ctx, "tg_analyze_chat", json!({"chat_id": -100_500})).await.unwrap();
    assert_eq!(out["source"], "archive");
    assert_eq!(out["messages_analyzed"], 1);
}

// ---------- the authorized gate at the dispatcher level ----------

#[tokio::test]
async fn every_tool_short_circuits_on_an_unauthorized_session() {
    let api = Arc::new(channel_api());
    let ctx = ctx("unauth");
    for name in crate::telegram::tools::TOOL_NAMES {
        let err = call(&api, &ctx, false, name, &json!({"arguments": {}}))
            .await
            .unwrap_err();
        assert_eq!(err.0, "not authorized - run send-code/verify first", "{name}");
    }
}

#[tokio::test]
async fn the_dialog_cache_is_written_from_the_mock_surface() {
    // §2.2: the cache stores the FULL list; the mock serves 4 dialogs,
    // so the cache file must hold 4 after the first dialog touch.
    let api = Arc::new(channel_api());
    let ctx = ctx("cache-file");
    run(&api, &ctx, "tg_unread", json!({})).await.unwrap();
    let cache: Value = serde_json::from_str(
        &std::fs::read_to_string(ctx.storage.join("tg_chats_cache.json")).unwrap(),
    )
    .unwrap();
    assert_eq!(cache["chats"].as_array().unwrap().len(), 4);
    assert!(cache["written_at"].is_i64());
}

//! [M-R2] The dialogs family: tg_list_chats, tg_unread, tg_get_chat,
//! tg_channel_digest, tg_scan_channels, tg_stats — plus the shared
//! dialog classification helpers every chat-id tool gates through.

use super::read::load_whitelist;
use super::{ToolCtx, ToolError, ToolResult};
use crate::telegram::api::TgApi;
use crate::telegram::cache::{DialogsCache, DIALOGS_FETCH_LIMIT};
use crate::telegram::format::{clamp_preview, format_date, MediaKind};
use crate::telegram::tools::params::{opt_i64, req_i64, MissingParams};
use crate::telegram::types::DialogInfo;
use crate::telegram::whitelist::{ChatType, Verdict, WhitelistConfig};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// The dialogs list through the cache (§2.2): fresh cache hit or a
/// FULL MTProto reload (the no-partial rule — an incomplete fetch is
/// an error, never a cache write).
pub(crate) async fn dialogs<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    _whitelist: &WhitelistConfig,
) -> Result<Vec<DialogInfo>, ToolError> {
    let cache = DialogsCache::new(&ctx.storage);
    if let Some(chats) = cache.load(now_secs()) {
        return Ok(chats);
    }
    let fetched: Vec<DialogInfo> = api.fetch_dialogs(DIALOGS_FETCH_LIMIT).await?;
    // Full-or-nothing: the transport delivers a complete list or an
    // error; here we additionally refuse to cache an obviously
    // truncated response (0 chats) to avoid caching transient faults.
    if fetched.is_empty() {
        return Err(ToolError(
            "minisd error: dialog list came back empty, refusing to cache".into(),
        ));
    }
    cache.save(now_secs(), &fetched).map_err(|e| {
        ToolError(format!("minisd error: cache write failed: {e}"))
    })?;
    Ok(fetched)
}

/// The kind of one chat, through the dialogs cache (best effort).
/// `None` = the chat is not among the dialogs; callers default.
pub(crate) async fn dialog_kind<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    whitelist: &WhitelistConfig,
    chat_id: i64,
) -> Option<ChatType> {
    let dialogs = dialogs(api, ctx, whitelist).await.ok()?;
    dialogs
        .iter()
        .find(|d| d.id == chat_id)
        .map(|d| d.kind.clone())
}

/// Kinds for a batch in ONE cache pass.
#[allow(dead_code)]
pub(crate) async fn dialog_kinds<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    whitelist: &WhitelistConfig,
    chat_ids: &[i64],
) -> HashMap<i64, ChatType> {
    let mut map = HashMap::new();
    if let Ok(dialogs) = dialogs(api, ctx, whitelist).await {
        for d in dialogs {
            if chat_ids.contains(&d.id) {
                map.insert(d.id, d.kind.clone());
            }
        }
    }
    map
}

/// The title of one chat through the dialogs cache.
pub(crate) async fn dialog_title<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    whitelist: &WhitelistConfig,
    chat_id: i64,
) -> Option<String> {
    let dialogs = dialogs(api, ctx, whitelist).await.ok()?;
    dialogs
        .iter()
        .find(|d| d.id == chat_id)
        .map(|d| d.title.clone())
}

/// `tg_list_chats` — §3.2: cached dialogs + whitelist filter.
pub async fn list_chats<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let limit = opt_i64(params, "limit").unwrap_or(50).clamp(1, 200) as usize;
    let whitelist = load_whitelist(ctx);
    let all = dialogs(api, ctx, &whitelist).await?;

    let mut chats_out: Vec<Value> = Vec::new();
    let mut whitelist_active = false;
    for d in all.iter() {
        // The dialogs LIST is informational: a blacklist verdict is
        // rendered as a blocked marker instead of an error, a
        // whitelist verdict hides the chat entirely.
        match whitelist.check(d.id, d.kind.clone()) {
            Verdict::Allowed => chats_out.push(chat_json(d)),
            Verdict::BlockedId | Verdict::BlockedType => {
                whitelist_active = true;
                let mut j = chat_json(d);
                j["blocked"] = json!(true);
                chats_out.push(j);
            }
            Verdict::NotInWhitelist => {
                whitelist_active = true;
                continue;
            }
        }
        if chats_out.len() >= limit {
            break;
        }
    }
    Ok(json!({
        "chats": chats_out,
        "total": chats_out.len(),
        "whitelist_active": whitelist_active,
    }))
}

fn chat_json(d: &DialogInfo) -> Value {
    json!({
        "id": d.id,
        "title": d.title,
        "type": d.kind.token(),
        "username": d.username,
        "unread": d.unread_count,
    })
}

/// `tg_unread` — §3.5: dialogs with unread counters.
pub async fn unread<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    _params: &Value,
) -> ToolResult {
    let whitelist = load_whitelist(ctx);
    let all = dialogs(api, ctx, &whitelist).await?;
    let unread_chats: Vec<Value> = all
        .iter()
        .filter(|d| d.unread_count > 0)
        .map(|d| {
            json!({
                "id": d.id,
                "title": d.title,
                "unread": d.unread_count,
            })
        })
        .collect();
    let total_unread: i32 = unread_chats
        .iter()
        .filter_map(|c| c["unread"].as_i64())
        .map(|n| n as i32)
        .sum();
    Ok(json!({
        "unread_chats": unread_chats,
        "total_unread": total_unread,
    }))
}

/// `tg_get_chat` — §3.6: full chat info via the full-* TL calls.
pub async fn get_chat<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    let kind = dialog_kind(api, ctx, &whitelist, chat_id)
        .await
        .unwrap_or(ChatType::Private);
    if let Some(err) = whitelist
        .check(chat_id, kind.clone())
        .error_text(chat_id, kind.token())
    {
        return Err(ToolError(err));
    }
    let full = api.get_chat_full(chat_id).await?;
    Ok(json!({
        "id": full.id,
        "title": full.title,
        "type": full.kind.token(),
        "username": full.username,
        "members": full.members,
        "description": full.description,
        "bio": full.bio,
    }))
}

/// `tg_channel_digest` — §3.8: one-line previews (<=120 chars).
pub async fn channel_digest<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(10).clamp(1, 50) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    let title = dialog_title(api, ctx, &whitelist, chat_id)
        .await
        .unwrap_or_else(|| format!("chat {chat_id}"));
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;
    let messages = api.fetch_messages(chat_id, limit, 0).await?;
    let digest: Vec<Value> = messages
        .iter()
        .map(|m| {
            json!({
                "msg_id": m.id,
                "date": format_date(m.date, ctx.tz_offset_secs),
                "preview": clamp_preview(&m.text),
                "has_media": !matches!(m.media, MediaKind::None),
            })
        })
        .collect();
    Ok(json!({
        "chat": title,
        "chat_id": chat_id,
        "digest": digest,
        "count": digest.len(),
    }))
}

/// `tg_scan_channels` — §3.9: batch scan of up to 30 channels,
/// `per_channel` (default 3) posts each. A dead channel degrades, it
/// never kills the scan.
pub async fn scan_channels<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let channels_limit = opt_i64(params, "channels_limit").unwrap_or(30).clamp(1, 30) as usize;
    let per_channel = opt_i64(params, "per_channel").unwrap_or(3).clamp(1, 20) as u32;
    let whitelist = load_whitelist(ctx);
    let all = dialogs(api, ctx, &whitelist).await?;

    let mut digest = serde_json::Map::new();
    let mut scanned = 0usize;
    let mut with_content = 0usize;
    for d in all.iter().filter(|d| matches!(d.kind, ChatType::Channel)) {
        if scanned >= channels_limit {
            break;
        }
        scanned += 1;
        let messages = match api.fetch_messages(d.id, per_channel, 0).await {
            Ok(m) => m,
            Err(_) => continue,
        };
        if messages.is_empty() {
            continue;
        }
        with_content += 1;
        let recent: Vec<Value> = messages
            .iter()
            .map(|m| {
                json!({
                    "d": format_date(m.date, ctx.tz_offset_secs),
                    "p": clamp_preview(&m.text),
                })
            })
            .collect();
        digest.insert(d.title.clone(), json!({"chat_id": d.id, "recent": recent}));
    }
    Ok(json!({
        "channels_scanned": scanned,
        "channels_with_content": with_content,
        "digest": digest,
    }))
}

/// `tg_stats` — §3.12: top channels, a last-100 sample each;
/// `recent_messages` counts the sample inside the last 24 h.
pub async fn stats<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let limit = opt_i64(params, "limit").unwrap_or(10).clamp(1, 30) as usize;
    let whitelist = load_whitelist(ctx);
    let all = dialogs(api, ctx, &whitelist).await?;

    let now = now_secs();
    let mut stats_out: Vec<Value> = Vec::new();
    for d in all.iter().filter(|d| matches!(d.kind, ChatType::Channel)).take(limit) {
        let sample = match api.fetch_messages(d.id, 100, 0).await {
            Ok(m) => m,
            Err(_) => continue,
        };
        let last_activity = sample.first().map(|m| format_date(m.date, ctx.tz_offset_secs));
        let recent_messages = sample.iter().filter(|m| m.date >= now - 86_400).count() as i32;
        stats_out.push(json!({
            "id": d.id,
            "title": d.title,
            "type": d.kind.token(),
            "recent_messages": recent_messages,
            "last_activity": last_activity,
            "unread": 0,
        }));
    }
    Ok(json!({
        "stats": stats_out,
        "total_channels": stats_out.len(),
    }))
}

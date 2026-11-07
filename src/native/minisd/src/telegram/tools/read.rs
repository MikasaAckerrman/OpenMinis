//! [M-R2] The reading family: tg_read, tg_period_read, tg_replies,
//! tg_read_parallel, tg_read_new, tg_read_chunked.
//!
//! Rate limiting and FloodWait retry are TRANSPORT properties (§2.4) —
//! they live inside the `TgApi` adapter, so handlers call the seam
//! directly and the mock in tests is instant and deterministic.

use super::{fmt_many, gate, ToolCtx, ToolError, ToolResult};
use crate::telegram::api::TgApi;
use crate::telegram::format::RawMessage;
use crate::telegram::tools::params::{opt_i32, opt_i64, req_i32, req_i64, req_str, MissingParams};
use crate::telegram::whitelist::{ChatType, WhitelistConfig};
use serde_json::{json, Value};
use std::sync::Arc;

/// The whitelist file is read PER REQUEST (§2.1: "читается при каждом
/// запросе") — no cache, so edits apply without a daemon restart.
pub(crate) fn load_whitelist(ctx: &ToolCtx) -> WhitelistConfig {
    let text = std::fs::read_to_string(ctx.storage.join("tg_whitelist.json")).ok();
    WhitelistConfig::from_json(text.as_deref()).0
}

/// The whitelist gate for one chat: classify via the dialogs
/// cache/fetch, then apply. An unknown chat defaults to PRIVATE (the
/// least privileged of the user-facing kinds).
pub(crate) async fn gated_kind<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    whitelist: &WhitelistConfig,
    chat_id: i64,
) -> Result<ChatType, ToolError> {
    let kind = super::channels::dialog_kind(api, ctx, whitelist, chat_id)
        .await
        .unwrap_or(ChatType::Private);
    gate(whitelist, chat_id, kind)?;
    Ok(kind)
}

/// Whitelist-gated fetch used by every member of the read family.
pub(crate) async fn gated_fetch<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    whitelist: &WhitelistConfig,
    chat_id: i64,
    limit: u32,
    offset_id: i32,
) -> Result<Vec<RawMessage>, ToolError> {
    gated_kind(api, ctx, whitelist, chat_id).await?;
    Ok(api.fetch_messages(chat_id, limit, offset_id).await?)
}

/// `tg_read` — §3.3: `{"chat_id", "messages": [FormattedMsg], "count"}`.
pub async fn read<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 200) as u32;
    let offset_id = opt_i32(params, "offset_id").unwrap_or(0);
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    let messages = gated_fetch(api, ctx, &whitelist, chat_id, limit, offset_id).await?;
    Ok(json!({
        "chat_id": chat_id,
        "messages": fmt_many(&messages, ctx.tz_offset_secs),
        "count": messages.len(),
    }))
}

/// `tg_period_read` — §3.10: history slice `date_from..date_to`
/// (inclusive local dates at the daemon offset), early stop at the
/// from-boundary, hard caps 40 batches / 4000 messages.
pub async fn period_read<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let date_from = req_str(params, "date_from", &mut missing);
    let date_to = req_str(params, "date_to", &mut missing);
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let (chat_id, date_from, date_to) = (chat_id.unwrap(), date_from.unwrap(), date_to.unwrap());

    let from_ts = match day_start_secs(&date_from, ctx.tz_offset_secs) {
        Some(ts) => ts,
        None => return Err(ToolError("minisd error: date_from must be YYYY-MM-DD".into())),
    };
    let to_ts = match day_start_secs(&date_to, ctx.tz_offset_secs) {
        Some(ts) => ts + 86_399, // inclusive end of day
        None => return Err(ToolError("minisd error: date_to must be YYYY-MM-DD".into())),
    };
    if to_ts < from_ts {
        return Err(ToolError("minisd error: date_to is before date_from".into()));
    }

    let whitelist = load_whitelist(ctx);
    gated_kind(api, ctx, &whitelist, chat_id).await?;

    const BATCH: u32 = 100;
    const MAX_BATCHES: u32 = 40;
    const MAX_MESSAGES: usize = 4_000;

    let mut collected: Vec<RawMessage> = Vec::new();
    let mut offset_id = 0i32;
    let mut note = String::new();
    'walk: for _batch in 0..MAX_BATCHES {
        let page = api.fetch_messages(chat_id, BATCH, offset_id).await?;
        if page.is_empty() {
            note = "history exhausted".into();
            break;
        }
        for m in page {
            offset_id = m.id;
            if m.date > to_ts {
                continue; // newer than the window: skip, keep walking back
            }
            if m.date < from_ts {
                note = "stopped at the date_from boundary".into();
                break 'walk;
            }
            collected.push(m);
            if collected.len() >= MAX_MESSAGES {
                note = "stopped at the 4000 message cap".into();
                break 'walk;
            }
        }
    }
    if note.is_empty() {
        note = format!("stopped at the {MAX_BATCHES}-batch cap");
    }

    Ok(json!({
        "chat_id": chat_id,
        "date_from": date_from,
        "date_to": date_to,
        "messages": fmt_many(&collected, ctx.tz_offset_secs),
        "count": collected.len(),
        "note": note,
    }))
}

/// `YYYY-MM-DD` at the daemon's fixed offset → unix seconds of that
/// local midnight. Returns None on any other shape.
pub(crate) fn day_start_secs(date: &str, tz_offset_secs: i32) -> Option<i64> {
    let mut parts = date.split('-');
    let y: i64 = parts.next()?.parse().ok()?;
    let m: i64 = parts.next()?.parse().ok()?;
    let d: i64 = parts.next()?.parse().ok()?;
    if parts.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    // Days-from-civil (Hinnant), same family as the formatter.
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let mp = if m > 2 { m - 3 } else { m + 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days * 86_400 - tz_offset_secs as i64)
}

/// `tg_replies` — §3.11: discussion replies of one post.
pub async fn replies<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let message_id = req_i32(params, "message_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 200) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let (chat_id, message_id) = (chat_id.unwrap(), message_id.unwrap());
    let whitelist = load_whitelist(ctx);
    gated_kind(api, ctx, &whitelist, chat_id).await?;
    let replies = api.fetch_discussion_replies(chat_id, message_id, limit).await?;
    Ok(json!({
        "chat_id": chat_id,
        "message_id": message_id,
        "replies": fmt_many(&replies, ctx.tz_offset_secs),
        "count": replies.len(),
    }))
}

/// `tg_read_parallel` — §4.17: bounded (10 in flight) parallel reads
/// across chats. Per-chat whitelist violations and fetch failures
/// degrade into the `errors` list instead of killing the batch.
pub async fn read_parallel<A: TgApi + 'static>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_ids = crate::telegram::tools::params::opt_i64_list(params, "chat_ids");
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 200) as u32;
    let chat_ids = match chat_ids {
        Some(ids) if !ids.is_empty() => ids,
        _ => {
            missing.push("chat_ids".into());
            return Err(ToolError(MissingParams(missing).error_text()));
        }
    };
    let whitelist = load_whitelist(ctx);

    // Gate every chat FIRST — a blocked chat must not consume a slot.
    let mut allowed: Vec<i64> = Vec::new();
    let mut errors: Vec<Value> = Vec::new();
    for chat_id in &chat_ids {
        match gated_kind(api, ctx, &whitelist, *chat_id).await {
            Ok(_) => allowed.push(*chat_id),
            Err(ToolError(text)) => errors.push(json!({"chat_id": chat_id, "error": text})),
        }
    }

    let pairs = crate::telegram::api::fetch_bounded(
        api.clone(),
        10,
        allowed.into_iter().map(|id| (id, limit)).collect(),
        |api, chat_id, limit| {
            Box::pin(async move { api.fetch_messages(chat_id, limit, 0).await })
        },
    )
    .await;

    let mut chats_out: Vec<Value> = Vec::new();
    for (chat_id, result) in pairs {
        match result {
            Ok(messages) => chats_out.push(json!({
                "chat_id": chat_id,
                "messages": fmt_many(&messages, ctx.tz_offset_secs),
                "count": messages.len(),
            })),
            Err(e) => errors.push(json!({"chat_id": chat_id, "error": e.error_text()})),
        }
    }
    Ok(json!({
        "chats": chats_out,
        "errors": errors,
        "chats_ok": chats_out.len(),
        "chats_failed": errors.len(),
    }))
}

/// `tg_read_new` — §4.18: only messages newer than the stored cursor;
/// the cursor advances only when the fetch succeeded.
pub async fn read_new<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(100).clamp(1, 400) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();

    let cursor_path = ctx.storage.join("tg_cursor.json");
    let mut cursors: Value = std::fs::read_to_string(&cursor_path)
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_else(|| json!({}));
    let key = chat_id.to_string();
    let cursor = cursors.get(&key).and_then(Value::as_i64).unwrap_or(0);

    let whitelist = load_whitelist(ctx);
    let messages = gated_fetch(api, ctx, &whitelist, chat_id, limit, 0).await?;
    // Telegram returns newest-first; the cursor means "everything with
    // id <= cursor is already seen".
    let mut new_msgs: Vec<RawMessage> = messages
        .into_iter()
        .filter(|m| (m.id as i64) > cursor)
        .collect();
    new_msgs.sort_by_key(|m| std::cmp::Reverse(m.id));

    let new_cursor = new_msgs.iter().map(|m| m.id as i64).max().unwrap_or(cursor);
    if new_cursor > cursor {
        cursors[key] = json!(new_cursor);
        std::fs::write(&cursor_path, serde_json::to_string(&cursors).unwrap()).ok();
    }

    Ok(json!({
        "chat_id": chat_id,
        "messages": fmt_many(&new_msgs, ctx.tz_offset_secs),
        "count": new_msgs.len(),
        "cursor": {"previous": cursor, "current": new_cursor},
    }))
}

/// `tg_read_chunked` — §4.19: one chat as parallel offset windows.
pub async fn read_chunked<A: TgApi + 'static>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let chunk_size = opt_i64(params, "chunk_size").unwrap_or(100).clamp(10, 200) as u32;
    let windows = opt_i64(params, "windows").unwrap_or(3).clamp(1, 10) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    gated_kind(api, ctx, &whitelist, chat_id).await?;

    // Window w covers ids (MAX - (w+1)*chunk, MAX - w*chunk]: deep
    // offsets near the top of the 32-bit id space; real ids live lower,
    // so the deepest windows come back empty — the honest shape of a
    // sparse window walk.
    let base = i32::MAX;
    let items: Vec<(i64, (i64, u32))> = (0..windows)
        .map(|w| {
            let offset = base.saturating_sub((w as i32 + 1) * chunk_size as i32);
            (offset as i64, (chat_id, chunk_size))
        })
        .collect();

    let pairs = crate::telegram::api::fetch_bounded(
        api.clone(),
        4,
        items,
        |api, offset_key, (chat_id, chunk)| {
            Box::pin(async move { api.fetch_messages(chat_id, chunk, offset_key as i32).await })
        },
    )
    .await;

    let mut windows_out = Vec::new();
    let mut total = 0usize;
    for (offset, result) in pairs {
        let offset_id = offset as i32;
        match result {
            Ok(messages) => {
                total += messages.len();
                windows_out.push(json!({
                    "offset_id": offset_id,
                    "messages": fmt_many(&messages, ctx.tz_offset_secs),
                    "count": messages.len(),
                }));
            }
            Err(e) => windows_out.push(json!({"offset_id": offset_id, "error": e.error_text()})),
        }
    }
    Ok(json!({
        "chat_id": chat_id,
        "chunk_size": chunk_size,
        "windows": windows_out,
        "total": total,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn day_start_parses_gregorian_dates_and_rejects_junk() {
        // 2026-11-07 local midnight at UTC+8 == 2026-11-06T16:00Z.
        assert_eq!(day_start_secs("2026-11-07", 8 * 3_600), Some(1_794_009_600 - 8 * 3_600));
        assert_eq!(day_start_secs("2026-13-01", 0), None);
        assert_eq!(day_start_secs("junk", 0), None);
        assert_eq!(day_start_secs("2026-11-07T10:00", 0), None);
    }
}

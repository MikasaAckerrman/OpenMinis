//! [M-R2] The search family: tg_search, tg_global_search,
//! tg_link_search, tg_multi_search, tg_search_parallel.

use super::read::load_whitelist;
use super::{fmt_many, ToolCtx, ToolError, ToolResult};
use crate::telegram::api::{fetch_bounded, TgApi};
use crate::telegram::tools::params::{opt_i64, req_i64, req_str, MissingParams};
use crate::telegram::types::GlobalHit;
use serde_json::{json, Value};
use std::sync::Arc;

/// `tg_search` — §3.4: search inside one chat.
pub async fn search<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let query = req_str(params, "query", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 200) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let (chat_id, query) = (chat_id.unwrap(), query.unwrap());
    let whitelist = load_whitelist(ctx);
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;
    let messages = api.search_messages(chat_id, &query, limit).await?;
    Ok(json!({
        "chat_id": chat_id,
        "query": query,
        "messages": fmt_many(&messages, ctx.tz_offset_secs),
        "count": messages.len(),
    }))
}

/// `tg_global_search` — §3.7: raw `messages.SearchGlobal` semantics.
pub async fn global_search<A: TgApi>(
    api: &Arc<A>,
    _ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let query = req_str(params, "query", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 100) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let query = query.unwrap();
    let hits: Vec<GlobalHit> = api.search_global(&query, limit).await?;
    Ok(json!({
        "query": query,
        "results": hits,
        "total": hits.len(),
    }))
}

/// `tg_link_search` — §3.13: messages carrying `https?://` links,
/// fetched at `limit * 3` depth like the legacy walker.
pub async fn link_search<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(20).clamp(1, 100) as usize;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;

    let fetch_limit = (limit * 3).min(300) as u32;
    let messages = api.fetch_messages(chat_id, fetch_limit, 0).await?;
    let mut out: Vec<Value> = Vec::new();
    for m in &messages {
        let links = crate::telegram::format::extract_links(&m.text);
        if links.is_empty() {
            continue;
        }
        out.push(json!({
            "msg_id": m.id,
            "date": crate::telegram::format::format_date(m.date, ctx.tz_offset_secs),
            "text": crate::telegram::format::clamp_text(&m.text),
            "links": links,
        }));
        if out.len() >= limit {
            break;
        }
    }
    Ok(json!({
        "chat_id": chat_id,
        "messages_with_links": out,
        "count": out.len(),
    }))
}

/// `tg_multi_search` — §3.14: sequential search across up to 20 chats;
/// failures of individual chats degrade into `chat_errors`.
pub async fn multi_search<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_ids = crate::telegram::tools::params::opt_i64_list(params, "chat_ids");
    let query = req_str(params, "query", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(10).clamp(1, 100) as u32;
    let ids_ok = chat_ids.as_ref().map_or(false, |v| !v.is_empty());
    if query.is_none() {
        missing.push("query".into());
    }
    if !ids_ok {
        missing.push("chat_ids".into());
    }
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let query = query.unwrap();
    let chat_ids = chat_ids.unwrap();
    if chat_ids.len() > 20 {
        return Err(ToolError("minisd error: tg_multi_search accepts at most 20 chats".into()));
    }
    let whitelist = load_whitelist(ctx);

    let mut results = serde_json::Map::new();
    let mut chats_with_results = 0usize;
    let mut total_messages = 0usize;
    let mut chats_searched = 0usize;
    let mut chat_errors: Vec<Value> = Vec::new();

    for chat_id in chat_ids {
        chats_searched += 1;
        let title = super::channels::dialog_title(api, ctx, &whitelist, chat_id)
            .await
            .unwrap_or_else(|| format!("chat {chat_id}"));
        if let Err(e) = super::read::gated_kind(api, ctx, &whitelist, chat_id).await {
            chat_errors.push(json!({"chat_id": chat_id, "error": e.0}));
            continue;
        }
        match api.search_messages(chat_id, &query, limit).await {
            Ok(messages) => {
                if !messages.is_empty() {
                    chats_with_results += 1;
                    total_messages += messages.len();
                    results.insert(title, Value::Array(fmt_many(&messages, ctx.tz_offset_secs)));
                }
            }
            Err(e) => chat_errors.push(json!({"chat_id": chat_id, "error": e.error_text()})),
        }
    }

    Ok(json!({
        "query": query,
        "chats_searched": chats_searched,
        "chats_with_results": chats_with_results,
        "total_messages": total_messages,
        "results": results,
        "chat_errors": chat_errors,
    }))
}

/// `tg_search_parallel` — §4.20: the same contract as multi_search,
/// fetched with bounded parallelism (10 in flight).
pub async fn search_parallel<A: TgApi + 'static>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_ids = crate::telegram::tools::params::opt_i64_list(params, "chat_ids");
    let query = req_str(params, "query", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(10).clamp(1, 100) as u32;
    let ids_ok = chat_ids.as_ref().map_or(false, |v| !v.is_empty());
    if query.is_none() {
        missing.push("query".into());
    }
    if !ids_ok {
        missing.push("chat_ids".into());
    }
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let query = query.unwrap();
    let chat_ids = chat_ids.unwrap();
    let total_requested = chat_ids.len();
    if total_requested > 50 {
        return Err(ToolError("minisd error: tg_search_parallel accepts at most 50 chats".into()));
    }
    let whitelist = load_whitelist(ctx);

    // Gate first (blocked chats never consume a parallel slot).
    let mut allowed: Vec<i64> = Vec::new();
    let mut chat_errors: Vec<Value> = Vec::new();
    for chat_id in &chat_ids {
        match super::read::gated_kind(api, ctx, &whitelist, *chat_id).await {
            Ok(_) => allowed.push(*chat_id),
            Err(ToolError(text)) => chat_errors.push(json!({"chat_id": chat_id, "error": text})),
        }
    }

    let query_arc = Arc::new(query.clone());
    let pairs = fetch_bounded(
        api.clone(),
        10,
        allowed
            .into_iter()
            .map(|id| (id, (query_arc.clone(), limit)))
            .collect(),
        |api, chat_id, (query, limit)| {
            Box::pin(async move { api.search_messages(chat_id, &query, limit).await })
        },
    )
    .await;

    let mut results = serde_json::Map::new();
    let mut chats_with_results = 0usize;
    let mut total_messages = 0usize;
    for (chat_id, result) in pairs {
        let title = super::channels::dialog_title(api, ctx, &whitelist, chat_id)
            .await
            .unwrap_or_else(|| format!("chat {chat_id}"));
        match result {
            Ok(messages) => {
                if !messages.is_empty() {
                    chats_with_results += 1;
                    total_messages += messages.len();
                    results.insert(title, Value::Array(fmt_many(&messages, ctx.tz_offset_secs)));
                }
            }
            Err(e) => chat_errors.push(json!({"chat_id": chat_id, "error": e.error_text()})),
        }
    }

    Ok(json!({
        "query": query,
        "chats_searched": total_requested,
        "chats_with_results": chats_with_results,
        "total_messages": total_messages,
        "results": results,
        "chat_errors": chat_errors,
    }))
}

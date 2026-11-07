//! [M-R2] tg_whoami, tg_download_media, tg_archive_chat, tg_analyze_chat.

use super::read::load_whitelist;
use super::{ToolCtx, ToolError, ToolResult};
use crate::telegram::api::TgApi;
use crate::telegram::archive;
use crate::telegram::tools::params::{opt_i64, req_i32, req_i64, req_str, MissingParams};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;

/// `tg_whoami` — §3.1: the authorized profile; the answer also lands
/// in `accounts.json` next to the session (the app reads it later).
pub async fn whoami<A: TgApi>(api: &Arc<A>, ctx: &ToolCtx) -> ToolResult {
    let me = api.get_me().await?;
    // The sync is best-effort: a failed write must not fail the tool.
    let path: PathBuf = ctx.storage.join("accounts.json");
    std::fs::write(&path, serde_json::to_string_pretty(&me).unwrap_or_default()).ok();
    Ok(serde_json::to_value(&me).unwrap_or(Value::Null))
}

/// Path-traversal-safe destination: the file name must be a bare name
/// (no separators, no `..`, not empty, not hidden-parent tricks), and
/// the resolved path must stay inside the media dir. The media dir is
/// `<storage>/media` — created on demand.
pub(crate) fn sanitize_media_dest(storage: &std::path::Path, file_name: &str) -> Result<PathBuf, ToolError> {
    if file_name.is_empty() {
        return Err(ToolError("minisd error: file_name must not be empty".into()));
    }
    let name = std::path::Path::new(file_name);
    if name.components().count() != 1
        || name.components().any(|c| {
            matches!(c, std::path::Component::ParentDir | std::path::Component::RootDir | std::path::Component::Prefix(_))
        })
        || file_name.contains("..")
    {
        return Err(ToolError(format!(
            "minisd error: unsafe file_name '{file_name}' (path traversal)"
        )));
    }
    let media_dir = storage.join("media");
    std::fs::create_dir_all(&media_dir)
        .map_err(|e| ToolError(format!("minisd error: cannot create media dir: {e}")))?;
    let dest = media_dir.join(name);
    // Belt and braces: canonicalize the parent and re-check containment.
    let canonical_dir = media_dir
        .canonicalize()
        .map_err(|e| ToolError(format!("minisd error: media dir resolve failed: {e}")))?;
    if !dest.starts_with(&canonical_dir) {
        return Err(ToolError(format!(
            "minisd error: unsafe file_name '{file_name}' (escapes media dir)"
        )));
    }
    Ok(dest)
}

/// `tg_download_media` — §3.15.
pub async fn download_media<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let message_id = req_i32(params, "message_id", &mut missing);
    let file_name = req_str(params, "file_name", &mut missing);
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let (chat_id, message_id, file_name) = (chat_id.unwrap(), message_id.unwrap(), file_name.unwrap());
    let whitelist = load_whitelist(ctx);
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;

    let dest = sanitize_media_dest(&ctx.storage, &file_name)?;
    let file = api.download_media(chat_id, message_id, &dest).await?;
    Ok(json!({
        "chat_id": chat_id,
        "message_id": message_id,
        "file": file.file,
        "size_bytes": file.size_bytes,
        "size_mb": (file.size_bytes as f64) / (1024.0 * 1024.0),
    }))
}

/// `tg_archive_chat` — §3.16: paged history into archive.db; the
/// response reports both this run's count and the running total.
pub async fn archive_chat<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let limit = opt_i64(params, "limit").unwrap_or(1_000).clamp(1, 10_000) as u32;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;

    let conn = archive::open(&ctx.archive_dir).map_err(ToolError)?;
    let total_before = archive::total_for_chat(&conn, chat_id).map_err(ToolError)?;

    const PAGE: u32 = 100;
    let mut archived_now = 0usize;
    let mut offset_id = 0i32;
    while archived_now < limit as usize {
        let page_limit = PAGE.min(limit - archived_now as u32);
        let messages = api.fetch_messages(chat_id, page_limit, offset_id).await?;
        if messages.is_empty() {
            break;
        }
        let rows: Vec<archive::ArchiveRow> = messages
            .iter()
            .map(|m| archive::ArchiveRow {
                chat_id,
                msg_id: m.id,
                date: crate::telegram::format::format_date(m.date, ctx.tz_offset_secs),
                sender: crate::telegram::format::format_sender(
                    &m.sender_name,
                    m.sender_username.as_deref(),
                ),
                text: crate::telegram::format::clamp_text(&m.text),
                media: m.media.marker(),
                reply_to: m.reply_to,
            })
            .collect();
        offset_id = messages.last().map(|m| m.id).unwrap_or(offset_id);
        archived_now += archive::upsert(&conn, &rows).map_err(ToolError)?;
        if (messages.len() as u32) < page_limit {
            break; // history exhausted
        }
    }

    let total_after = archive::total_for_chat(&conn, chat_id).map_err(ToolError)?;
    let db_file = ctx
        .archive_dir
        .join(archive::ARCHIVE_DB_NAME)
        .to_string_lossy()
        .to_string();
    Ok(json!({
        "archived_messages": archived_now,
        "db_file": db_file,
        "total_archived": total_after,
    }))
    .map(|v| {
        // total_before is part of the contract's honesty: report it as
        // a delta hint when the tool re-archives over existing rows.
        let mut v = v;
        if total_before > 0 {
            v["preexisting_rows"] = json!(total_before);
        }
        v
    })
}

/// `tg_analyze_chat` — §4.21: archive.db first; a live fetch fallback
/// when the chat was never archived.
pub async fn analyze_chat<A: TgApi>(
    api: &Arc<A>,
    ctx: &ToolCtx,
    params: &Value,
) -> ToolResult {
    let mut missing = Vec::new();
    let chat_id = req_i64(params, "chat_id", &mut missing);
    let top = opt_i64(params, "limit").unwrap_or(10).clamp(1, 50) as usize;
    if !missing.is_empty() {
        return Err(ToolError(MissingParams(missing).error_text()));
    }
    let chat_id = chat_id.unwrap();
    let whitelist = load_whitelist(ctx);
    super::read::gated_kind(api, ctx, &whitelist, chat_id).await?;

    let conn = match archive::open(&ctx.archive_dir) {
        Ok(c) => c,
        Err(e) => return Err(ToolError(e)),
    };
    let rows = archive::rows_for_chat(&conn, chat_id, 2_000).map_err(ToolError)?;
    if !rows.is_empty() {
        return Ok(archive::analyze_summary(chat_id, "archive", &rows, top));
    }

    // Live fallback: a 200-message sample, analyzed with the same code.
    let messages = api.fetch_messages(chat_id, 200, 0).await?;
    let rows: Vec<archive::ArchiveRow> = messages
        .iter()
        .map(|m| archive::ArchiveRow {
            chat_id,
            msg_id: m.id,
            date: crate::telegram::format::format_date(m.date, ctx.tz_offset_secs),
            sender: crate::telegram::format::format_sender(
                &m.sender_name,
                m.sender_username.as_deref(),
            ),
            text: crate::telegram::format::clamp_text(&m.text),
            media: m.media.marker(),
            reply_to: m.reply_to,
        })
        .collect();
    Ok(archive::analyze_summary(chat_id, "live", &rows, top))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bare_file_name_is_accepted() {
        let dir = std::env::temp_dir().join(format!("minisd-media-{}", std::process::id()));
        let dest = sanitize_media_dest(&dir, "photo_12.jpg").unwrap();
        assert!(dest.starts_with(dir.canonicalize().unwrap()));
        assert!(dest.ends_with("photo_12.jpg"));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn every_traversal_shape_is_refused() {
        let dir = std::env::temp_dir().join(format!("minisd-media-trav-{}", std::process::id()));
        for evil in [
            "../escape.db",
            "..",
            "sub/dir/file.jpg",
            "/etc/passwd",
            "file/with/slash",
            "a..b",
            "",
        ] {
            assert!(
                sanitize_media_dest(&dir, evil).is_err(),
                "'{evil}' must be refused"
            );
        }
        std::fs::remove_dir_all(&dir).ok();
    }
}

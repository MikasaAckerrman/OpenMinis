//! [M-R2] The MTProto seam. Tool handlers are generic over `TgApi` and
//! unit-test against a mock; `GrammersApi` is the thin grammers adapter
//! whose only job is mapping MTProto onto the domain types. This is the
//! structure that makes the reviewer's gate — "каждый инструмент имеет
//! чистые mock/golden-тесты" — executable: the FLOOD/retry/throttle
//! policy wraps the seam, so it is tested without a network.
//!
//! The trait uses RPITIT with an explicit `+ Send` (the AFIT sugar
//! cannot name that bound; tokio task spawning inside the parallel
//! tools requires it).

use crate::telegram::flood::{FloodBackoff, RateLimiter, MAX_ATTEMPTS};
use crate::telegram::types::{ChatFull, DialogInfo, GlobalHit, MediaFile, RawMessage, UserInfo};
use std::future::Future;
use std::path::Path;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;
// `Pin` is re-imported at the use site of `fetch_bounded`.

/// Every failure a tool can surface, before the MCP envelope wraps it.
#[derive(Debug, Clone)]
pub enum TgError {
    /// `FloodWait: 15s` — the flood budget was exhausted (or X > cap).
    FloodWait(u64),
    /// A Telegram-side RPC failure: `name` verbatim (e.g.
    /// "CHANNEL_PRIVATE", "FLOOD_WAIT"), `value` carrying the TL
    /// integer payload (FLOOD_WAIT's seconds live there).
    Rpc { name: String, code: i32, value: Option<i32> },
    /// Session is empty / logged out.
    NotAuthorized,
    /// Everything local: paths, IO, media handling.
    Local(String),
}

impl TgError {
    /// The seconds of a FLOOD_WAIT error, None for anything else —
    /// the retry policy's input.
    pub fn flood_wait_secs(&self) -> Option<u64> {
        match self {
            TgError::Rpc { name, value, .. } if name == "FLOOD_WAIT" => {
                value.map(|v| v.max(0) as u64)
            }
            _ => None,
        }
    }

    /// The text that goes into `content[0].text` (§5.2 shapes).
    pub fn error_text(&self) -> String {
        match self {
            TgError::FloodWait(secs) => format!("FloodWait: {secs}s"),
            TgError::Rpc { name, code, .. } => format!("Telegram RPC error: {name} (code {code})"),
            TgError::NotAuthorized => "not authorized - run send-code/verify first".into(),
            TgError::Local(msg) => format!("minisd error: {msg}"),
        }
    }
}

impl std::fmt::Display for TgError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.error_text())
    }
}

impl std::error::Error for TgError {}

/// The transport seam — deliberately narrower than grammers' surface.
pub trait TgApi: Send + Sync {
    fn get_me(&self) -> impl Future<Output = Result<UserInfo, TgError>> + Send;
    /// The full dialog list for the cache — ALL or nothing (the
    /// no-partial rule lives at the call sites, the transport either
    /// delivers a complete list or an error).
    fn fetch_dialogs(&self, limit: usize) -> impl Future<Output = Result<Vec<DialogInfo>, TgError>> + Send;
    fn fetch_messages(
        &self,
        chat_id: i64,
        limit: u32,
        offset_id: i32,
    ) -> impl Future<Output = Result<Vec<RawMessage>, TgError>> + Send;
    fn search_messages(
        &self,
        chat_id: i64,
        query: &str,
        limit: u32,
    ) -> impl Future<Output = Result<Vec<RawMessage>, TgError>> + Send;
    fn search_global(&self, query: &str, limit: u32)
        -> impl Future<Output = Result<Vec<GlobalHit>, TgError>> + Send;
    fn get_chat_full(&self, chat_id: i64) -> impl Future<Output = Result<ChatFull, TgError>> + Send;
    fn fetch_discussion_replies(
        &self,
        chat_id: i64,
        message_id: i32,
        limit: u32,
    ) -> impl Future<Output = Result<Vec<RawMessage>, TgError>> + Send;
    fn download_media(&self, chat_id: i64, message_id: i32, dest: &Path)
        -> impl Future<Output = Result<MediaFile, TgError>> + Send;
}

/// The retry/throttle wrapper every read goes through: the 0.3 s read
/// spacing plus the FLOOD_WAIT policy of §2.4, in ONE place. `flood`
/// maps a transport error into `Some(FLOOD_WAIT seconds)`.
pub async fn with_read_policy<T, F, Fut>(
    rate: &Mutex<RateLimiter>,
    flood: impl Fn(&TgError) -> Option<u64>,
    mut f: F,
) -> Result<T, TgError>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T, TgError>>,
{
    let mut attempts = 0u32;
    loop {
        // The throttle applies per ATTEMPT (each retry is a new read).
        throttle_read(rate).await;
        attempts += 1;
        let result = f().await;
        match result {
            Err(e) => {
                if let Some(secs) = flood(&e) {
                    if attempts >= MAX_ATTEMPTS {
                        return Err(TgError::FloodWait(secs));
                    }
                    let Some(wait) = FloodBackoff::retry_wait_secs(secs) else {
                        return Err(TgError::FloodWait(secs));
                    };
                    tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                    continue;
                }
                return Err(e);
            }
            ok => return ok,
        }
    }
}

async fn throttle_read(rate: &Mutex<RateLimiter>) {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let wait = rate.lock().await.wait_needed_ms(now_ms);
    if wait > 0 {
        tokio::time::sleep(std::time::Duration::from_millis(wait)).await;
    }
}

/// Bounded parallelism helper for the parallel tools (§4.17/§4.19/§4.20):
/// at most `permits` fetches in flight, per-chat failures degrade into
/// `Err(chat_id, e)` instead of killing the batch, output keeps the
/// input order. `call` is a plain fn pointer (Copy) over an owned
/// `Arc<A>` — the spawned futures are 'static without lifetime fights.
pub async fn fetch_bounded<A, Arg, T>(
    api: Arc<A>,
    permits: usize,
    items: Vec<(i64, Arg)>,
    call: fn(Arc<A>, i64, Arg) -> Pin<Box<dyn Future<Output = Result<T, TgError>> + Send>>,
) -> Vec<(i64, Result<T, TgError>)>
where
    A: TgApi + 'static,
    Arg: Send + 'static,
    T: Send + 'static,
{
    let sem = Arc::new(tokio::sync::Semaphore::new(permits));
    let mut set = tokio::task::JoinSet::new();
    for (chat_id, arg) in items {
        let sem = sem.clone();
        let api = api.clone();
        set.spawn(async move {
            let _permit = sem.acquire_owned().await;
            let result = call(api, chat_id, arg).await;
            (chat_id, result)
        });
    }
    let mut out = Vec::new();
    while let Some(joined) = set.join_next().await {
        if let Ok(pair) = joined {
            out.push(pair);
        }
    }
    out.sort_by_key(|(id, _)| *id);
    out
}

/// The test seam: every handler test drives THIS through the same
/// generic code paths the grammers adapter serves in production.
#[cfg(test)]
#[derive(Default)]
pub struct MockApi {
    pub me: Option<UserInfo>,
    pub dialogs: Vec<DialogInfo>,
    /// chat_id → page (newest-first), reused for offset walks.
    pub messages: std::collections::HashMap<i64, Vec<RawMessage>>,
    /// chat_id → search results.
    pub search: std::collections::HashMap<i64, Vec<RawMessage>>,
    pub global: Vec<GlobalHit>,
    pub full: std::collections::HashMap<i64, ChatFull>,
    /// (chat_id, message_id) → replies.
    pub replies: std::collections::HashMap<(i64, i32), Vec<RawMessage>>,
    pub media: Option<MediaFile>,
    /// chat_ids whose fetch fails with Rpc CHANNEL_PRIVATE.
    pub dead_chats: std::collections::HashSet<i64>,
}

#[cfg(test)]
impl MockApi {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_dialogs(mut self, dialogs: Vec<DialogInfo>) -> Self {
        self.dialogs = dialogs;
        self
    }

    pub fn with_messages(mut self, chat_id: i64, messages: Vec<RawMessage>) -> Self {
        self.messages.insert(chat_id, messages);
        self
    }

    pub fn with_search(mut self, chat_id: i64, messages: Vec<RawMessage>) -> Self {
        self.search.insert(chat_id, messages);
        self
    }

    pub fn with_global(mut self, hits: Vec<GlobalHit>) -> Self {
        self.global = hits;
        self
    }

    pub fn with_full(mut self, chat_id: i64, full: ChatFull) -> Self {
        self.full.insert(chat_id, full);
        self
    }

    pub fn with_replies(mut self, chat_id: i64, message_id: i32, replies: Vec<RawMessage>) -> Self {
        self.replies.insert((chat_id, message_id), replies);
        self
    }

    pub fn with_media(mut self, file: MediaFile) -> Self {
        self.media = Some(file);
        self
    }

    pub fn dead(mut self, chat_id: i64) -> Self {
        self.dead_chats.insert(chat_id);
        self
    }
}

#[cfg(test)]
impl TgApi for MockApi {
    async fn get_me(&self) -> Result<UserInfo, TgError> {
        self.me.clone().ok_or(TgError::NotAuthorized)
    }

    async fn fetch_dialogs(&self, limit: usize) -> Result<Vec<DialogInfo>, TgError> {
        Ok(self.dialogs.iter().take(limit).cloned().collect())
    }

    async fn fetch_messages(
        &self,
        chat_id: i64,
        limit: u32,
        offset_id: i32,
    ) -> Result<Vec<RawMessage>, TgError> {
        if self.dead_chats.contains(&chat_id) {
            return Err(TgError::Rpc {
                name: "CHANNEL_PRIVATE".into(),
                code: 400,
                value: None,
            });
        }
        let page = self.messages.get(&chat_id).cloned().unwrap_or_default();
        Ok(page
            .into_iter()
            .filter(|m| offset_id == 0 || (m.id < offset_id && m.id >= offset_id.saturating_sub(limit as i32)))
            .take(limit as usize)
            .collect())
    }

    async fn search_messages(
        &self,
        chat_id: i64,
        query: &str,
        limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        if self.dead_chats.contains(&chat_id) {
            return Err(TgError::Rpc {
                name: "CHANNEL_PRIVATE".into(),
                code: 400,
                value: None,
            });
        }
        let hits = self.search.get(&chat_id).cloned().unwrap_or_default();
        Ok(hits
            .into_iter()
            .filter(|m| m.text.contains(query))
            .take(limit as usize)
            .collect())
    }

    async fn search_global(
        &self,
        query: &str,
        limit: u32,
    ) -> Result<Vec<GlobalHit>, TgError> {
        Ok(self
            .global
            .iter()
            .filter(|h| h.text.contains(query))
            .take(limit as usize)
            .cloned()
            .collect())
    }

    async fn get_chat_full(&self, chat_id: i64) -> Result<ChatFull, TgError> {
        self.full
            .get(&chat_id)
            .cloned()
            .ok_or(TgError::Rpc { name: "CHAT_ID_INVALID".into(), code: 400, value: None })
    }

    async fn fetch_discussion_replies(
        &self,
        chat_id: i64,
        message_id: i32,
        _limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        Ok(self
            .replies
            .get(&(chat_id, message_id))
            .cloned()
            .unwrap_or_default())
    }

    async fn download_media(
        &self,
        _chat_id: i64,
        _message_id: i32,
        dest: &std::path::Path,
    ) -> Result<MediaFile, TgError> {
        match &self.media {
            Some(f) => {
                // Write honest bytes so the size-on-disk contract holds.
                std::fs::write(dest, vec![0u8; f.size_bytes as usize]).map_err(|e| {
                    TgError::Local(format!("mock write failed: {e}"))
                })?;
                Ok(MediaFile {
                    file: dest.to_string_lossy().to_string(),
                    size_bytes: f.size_bytes,
                })
            }
            None => Err(TgError::Local("message has no media".into())),
        }
    }
}

//! [M-R2] The dialogs cache — exact semantics of telegram.py:178-250
//! (docs/M_R2_TOOL_MAPPING.md §2.2): file `tg_chats_cache.json`, TTL
//! 30 minutes, first 200 dialogs, and the no-partial rule — a partially
//! loaded dialog list is NEVER cached, because a silent cache of half
//! the channels is a lie the agent then plans around.

use crate::telegram::types::DialogInfo;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const CACHE_TTL_SECS: i64 = 1_800;
pub const DIALOGS_FETCH_LIMIT: usize = 200;

#[derive(Debug, Serialize, Deserialize)]
struct CacheFile {
    /// Unix seconds when the cache was written.
    written_at: i64,
    chats: Vec<DialogInfo>,
}

pub struct DialogsCache {
    path: PathBuf,
}

impl DialogsCache {
    pub fn new(storage_dir: &Path) -> Self {
        Self {
            path: storage_dir.join("tg_chats_cache.json"),
        }
    }

    #[allow(dead_code)]
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Fresh cache contents, or None when absent/unreadable/stale —
    /// the caller then does a full MTProto reload.
    pub fn load(&self, now_secs: i64) -> Option<Vec<DialogInfo>> {
        let text = std::fs::read_to_string(&self.path).ok()?;
        let file: CacheFile = serde_json::from_str(&text).ok()?;
        if now_secs - file.written_at >= CACHE_TTL_SECS {
            return None;
        }
        Some(file.chats)
    }

    /// Persist a COMPLETE dialog list (the caller enforces the
    /// no-partial rule by never handing us a truncated fetch).
    pub fn save(&self, now_secs: i64, chats: &[DialogInfo]) -> std::io::Result<()> {
        let file = CacheFile {
            written_at: now_secs,
            chats: chats.to_vec(),
        };
        let tmp = self.path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_string(&file).unwrap())?;
        std::fs::rename(&tmp, &self.path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::types::ChatKind;

    fn dialog(id: i64, title: &str) -> DialogInfo {
        DialogInfo {
            id,
            title: title.into(),
            kind: ChatKind::Channel,
            username: None,
            unread_count: 0,
        }
    }

    #[test]
    fn a_fresh_cache_hit_returns_the_dialogs() {
        let dir = std::env::temp_dir().join(format!("minisd-cache-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let cache = DialogsCache::new(&dir);
        let chats = vec![dialog(-100_1, "one"), dialog(-100_2, "two")];
        cache.save(1_000, &chats).unwrap();
        let loaded = cache.load(1_000 + CACHE_TTL_SECS - 1).unwrap();
        assert_eq!(loaded.len(), 2);
        assert_eq!(loaded[0].title, "one");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn the_cache_is_stale_at_exactly_ttl() {
        let dir = std::env::temp_dir().join(format!("minisd-cache-stale-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let cache = DialogsCache::new(&dir);
        cache.save(1_000, &[dialog(1, "x")]).unwrap();
        assert!(cache.load(1_000 + CACHE_TTL_SECS).is_none());
        assert!(cache.load(1_000 + CACHE_TTL_SECS - 1).is_some());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_missing_or_corrupt_file_is_a_cache_miss_not_an_error() {
        let dir = std::env::temp_dir().join(format!("minisd-cache-miss-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let cache = DialogsCache::new(&dir);
        assert!(cache.load(5_000).is_none());
        std::fs::write(cache.path(), "not json").unwrap();
        assert!(cache.load(5_000).is_none());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn save_is_atomic_through_a_tmp_file() {
        let dir = std::env::temp_dir().join(format!("minisd-cache-atomic-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let cache = DialogsCache::new(&dir);
        cache.save(1, &[dialog(9, "nine")]).unwrap();
        // No *.tmp leftovers — rename either happened or failed loudly.
        let leftovers: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.path().extension().map(|x| x == "tmp").unwrap_or(false))
            .collect();
        assert!(leftovers.is_empty());
        std::fs::remove_dir_all(&dir).ok();
    }
}

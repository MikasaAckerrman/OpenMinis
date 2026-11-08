//! [PARALLEL_TOOL_ENGINE_SPEC §3] Resource key extraction and taxonomy.

use super::types::{ToolEffect, ToolPool};
use serde_json::Value;

/// Path normalization (§3.2): resolves `.` and `..`, collapses multiple slashes,
/// without requiring filesystem stat or realpath syscall.
pub fn normalize_resource_path(path: &str) -> String {
    let raw = path.trim();
    if raw.is_empty() {
        return String::new();
    }
    let is_absolute = raw.starts_with('/');
    let parts = raw.split('/').filter(|p| !p.is_empty() && *p != ".");
    let mut stack: Vec<&str> = Vec::new();
    for part in parts {
        if part == ".." {
            if let Some(last) = stack.last() {
                if *last != ".." {
                    stack.pop();
                    continue;
                }
            }
            if !is_absolute {
                stack.push("..");
            }
        } else {
            stack.push(part);
        }
    }
    let joined = stack.join("/");
    if is_absolute {
        format!("/{}", joined)
    } else {
        joined
    }
}

/// [PARALLEL_TOOL_ENGINE_SPEC §3.1] The hierarchical cross-key conflict rule:
/// a filesystem tree key overlaps a file key when the file lives inside the tree.
/// Everything outside the fs/fs_tree pair collides only on exact string equality.
pub fn keys_overlap(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    let a_tree = a.starts_with("fs_tree:");
    let b_tree = b.starts_with("fs_tree:");
    if !a_tree && !b_tree {
        return false;
    }
    let (dir, file) = match (a_tree, b_tree) {
        (true, false) => {
            if !b.starts_with("fs:") {
                return false;
            }
            (&a["fs_tree:".len()..], &b["fs:".len()..])
        }
        (false, true) => {
            if !a.starts_with("fs:") {
                return false;
            }
            (&b["fs_tree:".len()..], &a["fs:".len()..])
        }
        _ => return false, // two trees, different roots: reads coexist
    };
    let dir_norm = dir.trim_end_matches('/');
    if dir_norm.is_empty() || dir_norm == "." {
        return if dir_norm == "." {
            !file.starts_with('/')
        } else {
            file.starts_with('/') || dir.is_empty()
        };
    }
    file.starts_with(dir_norm)
        && (file.len() == dir_norm.len() || file[dir_norm.len()..].starts_with('/'))
}

/// Extracts resource keys, effect, and concurrency pool from a tool call specification.
/// Returns (keys, effect, pool). If keys is None, the call is treated as GLOBAL_BARRIER.
pub fn classify_tool(tool_name: &str, args_json: &str) -> (Option<Vec<String>>, ToolEffect, ToolPool) {
    let parsed: Option<Value> = serde_json::from_str(args_json).ok();

    match tool_name {
        // Filesystem Read (Shared READ, IO pool)
        "file_read" | "read_image" => {
            let path = parsed.as_ref()
                .and_then(|v| v.get("path"))
                .and_then(Value::as_str)
                .map(normalize_resource_path)
                .filter(|p| !p.is_empty());
            match path {
                Some(p) => (Some(vec![format!("fs:{p}")]), ToolEffect::Read, ToolPool::Io),
                None => (None, ToolEffect::GlobalBarrier, ToolPool::Cpu),
            }
        }

        // Filesystem Tree Read (Shared READ, IO pool)
        "grep" | "glob" | "list_dir" => {
            let path = parsed.as_ref()
                .and_then(|v| v.get("path"))
                .and_then(Value::as_str)
                .map(normalize_resource_path)
                .filter(|p| !p.is_empty())
                .unwrap_or_else(|| "/var/minis".to_string());
            (Some(vec![format!("fs_tree:{path}")]), ToolEffect::Read, ToolPool::Io)
        }

        // Filesystem Write (Exclusive WRITE, IO pool)
        "file_write" | "file_edit" => {
            let path = parsed.as_ref()
                .and_then(|v| v.get("path"))
                .and_then(Value::as_str)
                .map(normalize_resource_path)
                .filter(|p| !p.is_empty());
            match path {
                Some(p) => (Some(vec![format!("fs:{p}")]), ToolEffect::Write, ToolPool::Io),
                None => (None, ToolEffect::GlobalBarrier, ToolPool::Cpu),
            }
        }

        // Network Read (Shared READ, IO pool)
        "web_search" => {
            (Some(vec!["net:search".to_string()]), ToolEffect::Read, ToolPool::Io)
        }

        "webfetch" => {
            let url = parsed.as_ref()
                .and_then(|v| v.get("url"))
                .and_then(Value::as_str)
                .filter(|u| !u.trim().is_empty());
            match url {
                Some(u) => (Some(vec![format!("net:{u}")]), ToolEffect::Read, ToolPool::Io),
                None => (None, ToolEffect::GlobalBarrier, ToolPool::Cpu),
            }
        }

        // Telegram Vault MCP tools (§3.1)
        "tg_read" | "tg_unread" | "tg_get_chat" | "tg_period_read" | "tg_replies"
        | "tg_read_new" | "tg_read_chunked" | "tg_search" | "tg_link_search" | "tg_stats"
        | "tg_analyze_chat" | "tg_download_media" | "tg_archive_chat" => {
            let chat_id = parsed.as_ref()
                .and_then(|v| v.get("chat_id"))
                .and_then(|c| c.as_i64().or_else(|| c.as_str().and_then(|s| s.parse::<i64>().ok())));
            match chat_id {
                Some(id) => (Some(vec![format!("mcp:tg:chat_{id}")]), ToolEffect::Read, ToolPool::Io),
                None => (Some(vec!["mcp:tg:global_read".to_string()]), ToolEffect::Read, ToolPool::Io),
            }
        }

        "tg_multi_search" => {
            let mut keys = Vec::new();
            if let Some(arr) = parsed.as_ref().and_then(|v| v.get("chat_ids")).and_then(Value::as_array) {
                for item in arr {
                    if let Some(id) = item.as_i64().or_else(|| item.as_str().and_then(|s| s.parse::<i64>().ok())) {
                        keys.push(format!("mcp:tg:chat_{id}"));
                    }
                }
            }
            if keys.is_empty() {
                keys.push("mcp:tg:global_read".to_string());
            }
            (Some(keys), ToolEffect::Read, ToolPool::Io)
        }

        "tg_whoami" | "tg_list_chats" | "tg_scan_channels" | "tg_read_parallel" | "tg_search_parallel" => {
            (Some(vec!["mcp:tg:global_read".to_string()]), ToolEffect::Read, ToolPool::Io)
        }

        // [Опора 2] The macro-tool: ONE call declares EVERY file it
        // touches — the fan-out is the engine's job, the resource keys
        // stay per-file so a later write to any of them still orders.
        "multi_file_read" => {
            let mut keys = Vec::new();
            if let Some(arr) = parsed.as_ref().and_then(|v| v.get("paths")).and_then(Value::as_array) {
                for item in arr {
                    if let Some(p) = item.as_str() {
                        let n = normalize_resource_path(p);
                        if !n.is_empty() && !keys.contains(&format!("fs:{n}")) {
                            keys.push(format!("fs:{n}"));
                        }
                    }
                }
            }
            if keys.is_empty() {
                // No usable paths declared: the conservative barrier,
                // same rule as a file_read with a missing path.
                (None, ToolEffect::GlobalBarrier, ToolPool::Cpu)
            } else {
                (Some(keys), ToolEffect::Read, ToolPool::Io)
            }
        }

        // Memory Store (§3.1)
        "memory_get" | "supermemory_search" | "memory_blocks_view" | "todo_read" => {
            (Some(vec!["mem:store".to_string()]), ToolEffect::Read, ToolPool::Io)
        }

        "memory_write" | "memory_blocks_edit" => {
            (Some(vec!["mem:store".to_string()]), ToolEffect::Write, ToolPool::Io)
        }

        "todo_write" => {
            (Some(vec!["mem:store".to_string()]), ToolEffect::Write, ToolPool::Io)
        }

        // Subagents (Isolated WRITE, CPU pool)
        "spawn_subagent" | "spawn_many" | "subagent_task" => {
            (None, ToolEffect::Write, ToolPool::Cpu)
        }

        // Meta Operations
        "plan_submit" => {
            (Some(vec!["sys:plan".to_string()]), ToolEffect::Meta, ToolPool::Cpu)
        }

        // Global barrier (shell, UI, unclassified)
        _ => (None, ToolEffect::GlobalBarrier, ToolPool::Cpu),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_path_handles_relative_and_redundant_segments() {
        assert_eq!(normalize_resource_path("./src/a.rs"), "src/a.rs");
        assert_eq!(normalize_resource_path("src//b.rs"), "src/b.rs");
        assert_eq!(normalize_resource_path("src/nested/../c.rs"), "src/c.rs");
        assert_eq!(normalize_resource_path("/var/minis/./main.rs"), "/var/minis/main.rs");
        assert_eq!(normalize_resource_path("/a/b/../../c"), "/c");
        assert_eq!(normalize_resource_path(""), "");
    }

    #[test]
    fn keys_overlap_enforces_fs_tree_hierarchy() {
        assert!(keys_overlap("fs_tree:/var/minis", "fs:/var/minis/src/a.rs"));
        assert!(keys_overlap("fs:/var/minis/src/a.rs", "fs_tree:/var/minis"));
        assert!(keys_overlap("fs_tree:/", "fs:/a.txt"));
        assert!(keys_overlap("fs_tree:.", "fs:a.txt"));

        // Sibling directories do not overlap
        assert!(!keys_overlap("fs_tree:/var/minis", "fs:/var/other/b.rs"));
        assert!(!keys_overlap("fs_tree:/var/minis_backup", "fs:/var/minis/a.rs"));

        // Non-fs keys do not overlap with fs_tree
        assert!(!keys_overlap("fs_tree:/var", "mem:store"));
        assert!(!keys_overlap("fs_tree:/var", "net:search"));
        assert!(!keys_overlap("net:search", "fs_tree:/var"));

        // Exact match
        assert!(keys_overlap("net:search", "net:search"));
        assert!(keys_overlap("mem:store", "mem:store"));
        assert!(!keys_overlap("net:search", "net:other"));
    }

    #[test]
    fn classify_extracts_taxonomy_accurately() {
        let (k1, e1, p1) = classify_tool("file_read", r#"{"path":"./src/main.rs"}"#);
        assert_eq!(k1, Some(vec!["fs:src/main.rs".to_string()]));
        assert_eq!(e1, ToolEffect::Read);
        assert_eq!(p1, ToolPool::Io);

        let (k2, e2, p2) = classify_tool("file_write", r#"{"path":"src/main.rs"}"#);
        assert_eq!(k2, Some(vec!["fs:src/main.rs".to_string()]));
        assert_eq!(e2, ToolEffect::Write);
        assert_eq!(p2, ToolPool::Io);

        let (k3, e3, p3) = classify_tool("grep", r#"{"pattern":"needle","path":"/src"}"#);
        assert_eq!(k3, Some(vec!["fs_tree:/src".to_string()]));
        assert_eq!(e3, ToolEffect::Read);
        assert_eq!(p3, ToolPool::Io);

        let (k4, e4, p4) = classify_tool("web_search", r#"{"query":"tokio"}"#);
        assert_eq!(k4, Some(vec!["net:search".to_string()]));
        assert_eq!(e4, ToolEffect::Read);
        assert_eq!(p4, ToolPool::Io);

        let (k5, e5, p5) = classify_tool("shell_execute", r#"{"command":"cargo test"}"#);
        assert_eq!(k5, None);
        assert_eq!(e5, ToolEffect::GlobalBarrier);
        assert_eq!(p5, ToolPool::Cpu);
    }
}

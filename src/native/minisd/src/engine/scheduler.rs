//! [PARALLEL_TOOL_ENGINE_SPEC §4] Reactive DAG tool scheduler.

use super::keys::{classify_tool, keys_overlap};
use super::types::{ToolCallNode, ToolCallSpec, ToolEffect, ToolPool};
use std::collections::BTreeSet;

/// Recorded usage of a resource in the wave/turn.
struct KeyUse {
    key: String,
    id: String,
    wrote: bool,
}

/// Constructs a Reactive DAG from a list of model-ordered tool calls.
///
/// Algorithmic complexity is O(N * K) where N <= ~20 and K <= 3, executing in < 50 microseconds.
/// Every node specifies its exact dependency predecessor IDs, enabling zero-barrier execution.
pub fn plan_reactive(calls: &[ToolCallSpec]) -> Vec<ToolCallNode> {
    let mut nodes: Vec<ToolCallNode> = Vec::with_capacity(calls.len());
    let mut uses: Vec<KeyUse> = Vec::new();
    let mut global_barrier: Option<String> = None;
    let mut last_meta: Option<String> = None;

    for (index, call) in calls.iter().enumerate() {
        let (extracted_keys, default_effect, default_pool) = classify_tool(&call.name, &call.args_json);

        // Subagents declare an isolated unique key so multiple spawns run in parallel
        let is_spawn = extracted_keys.is_none() && call.name.starts_with("spawn_");
        let effect = if is_spawn { ToolEffect::Write } else { default_effect };
        let pool = if is_spawn { ToolPool::Cpu } else { default_pool };
        let keys: Option<Vec<String>> = if is_spawn {
            Some(vec![format!("agent-spawn:{index}")])
        } else {
            extracted_keys
        };

        let mut deps = BTreeSet::new();

        match &keys {
            None => {
                // GLOBAL_BARRIER: hermetic in both directions
                for earlier in &nodes {
                    deps.insert(earlier.call.id.clone());
                }
                global_barrier = Some(call.id.clone());
            }
            Some(keys_vec) => {
                if let Some(gb) = &global_barrier {
                    deps.insert(gb.clone());
                }
                if effect == ToolEffect::Meta {
                    if let Some(lm) = &last_meta {
                        deps.insert(lm.clone());
                    }
                    last_meta = Some(call.id.clone());
                } else {
                    for k in keys_vec {
                        match effect {
                            ToolEffect::Write => {
                                for u in &uses {
                                    if keys_overlap(k, &u.key) {
                                        deps.insert(u.id.clone());
                                    }
                                }
                            }
                            _ => {
                                for u in &uses {
                                    if u.wrote && keys_overlap(k, &u.key) {
                                        deps.insert(u.id.clone());
                                    }
                                }
                            }
                        }
                    }
                    let is_write = effect == ToolEffect::Write;
                    for k in keys_vec {
                        uses.push(KeyUse {
                            key: k.clone(),
                            id: call.id.clone(),
                            wrote: is_write,
                        });
                    }
                }
            }
        }

        nodes.push(ToolCallNode {
            call: call.clone(),
            effect,
            pool,
            resources: keys.unwrap_or_default(),
            depends_on: deps.into_iter().collect(),
        });
    }

    nodes
}

#[cfg(test)]
mod tests {
    use super::*;

    fn deps_of(nodes: &[ToolCallNode], id: &str) -> Vec<String> {
        nodes.iter().find(|n| n.call.id == id).unwrap().depends_on.clone()
    }

    #[test]
    fn research_batch_runs_four_calls_immediately() {
        let calls = vec![
            ToolCallSpec::new("1", "web_search", r#"{"query":"rust tokio"}"#),
            ToolCallSpec::new("2", "file_read", r#"{"path":"src/a.rs"}"#),
            ToolCallSpec::new("3", "file_read", r#"{"path":"src/b.rs"}"#),
            ToolCallSpec::new("4", "file_write", r#"{"path":"src/a.rs","content":"mod a;"}"#),
            ToolCallSpec::new("5", "file_read", r#"{"path":"src/c.rs"}"#),
        ];

        let nodes = plan_reactive(&calls);
        assert_eq!(nodes.len(), 5);

        // 1, 2, 3, 5 have no dependencies -> run concurrently at time 0
        assert_eq!(deps_of(&nodes, "1"), Vec::<String>::new());
        assert_eq!(deps_of(&nodes, "2"), Vec::<String>::new());
        assert_eq!(deps_of(&nodes, "3"), Vec::<String>::new());
        assert_eq!(deps_of(&nodes, "5"), Vec::<String>::new());

        // 4 writes src/a.rs -> depends ONLY on 2 (which reads src/a.rs)
        assert_eq!(deps_of(&nodes, "4"), vec!["2".to_string()]);
    }

    #[test]
    fn war_rule_ensures_writer_waits_for_all_active_readers() {
        let calls = vec![
            ToolCallSpec::new("1", "file_read", r#"{"path":"src/shared.rs"}"#),
            ToolCallSpec::new("2", "file_read", r#"{"path":"src/shared.rs"}"#),
            ToolCallSpec::new("3", "file_write", r#"{"path":"src/shared.rs","content":"new"}"#),
        ];

        let nodes = plan_reactive(&calls);
        assert_eq!(deps_of(&nodes, "1"), Vec::<String>::new());
        assert_eq!(deps_of(&nodes, "2"), Vec::<String>::new());
        // 3 must wait for BOTH 1 and 2
        assert_eq!(deps_of(&nodes, "3"), vec!["1".to_string(), "2".to_string()]);
    }

    #[test]
    fn global_barrier_serializes_everything_bidirectionally() {
        let calls = vec![
            ToolCallSpec::new("1", "file_read", r#"{"path":"src/a.rs"}"#),
            ToolCallSpec::new("2", "shell_execute", r#"{"command":"cargo check"}"#),
            ToolCallSpec::new("3", "file_read", r#"{"path":"src/b.rs"}"#),
        ];

        let nodes = plan_reactive(&calls);
        assert_eq!(deps_of(&nodes, "1"), Vec::<String>::new());
        // Shell barrier waits for 1
        assert_eq!(deps_of(&nodes, "2"), vec!["1".to_string()]);
        // Call after shell barrier waits for 2
        assert_eq!(deps_of(&nodes, "3"), vec!["2".to_string()]);
    }

    #[test]
    fn subagent_spawns_run_concurrently() {
        let calls = vec![
            ToolCallSpec::new("s1", "spawn_subagent", r#"{"role":"tester"}"#),
            ToolCallSpec::new("s2", "spawn_subagent", r#"{"role":"reviewer"}"#),
        ];

        let nodes = plan_reactive(&calls);
        assert_eq!(deps_of(&nodes, "s1"), Vec::<String>::new());
        assert_eq!(deps_of(&nodes, "s2"), Vec::<String>::new());
    }
}

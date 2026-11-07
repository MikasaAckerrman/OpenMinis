//! [PARALLEL_TOOL_ENGINE_SPEC §5.2] Asynchronous reactive DAG executor on Tokio.

use super::types::{ToolCallNode, ToolCallResult, ToolPool};
use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use tokio::sync::{mpsc, watch, Semaphore};

/// Resource throttlers maintaining distinct pools for I/O and CPU tools.
#[derive(Clone)]
pub struct ConcurrencyPools {
    pub io: Arc<Semaphore>,
    pub cpu: Arc<Semaphore>,
}

impl ConcurrencyPools {
    pub fn new(io_cap: usize, cpu_cap: usize) -> Self {
        Self {
            io: Arc::new(Semaphore::new(io_cap.max(1))),
            cpu: Arc::new(Semaphore::new(cpu_cap.max(1))),
        }
    }

    pub fn default_limits() -> Self {
        Self::new(ToolPool::Io.default_cap(), ToolPool::Cpu.default_cap())
    }
}

/// Executes a planned DAG of tool calls concurrently.
///
/// Properties:
/// 1. Zero-barrier: independent tools launch immediately at time 0.
/// 2. Granular dependencies: dependent tools wait only for their declared predecessors via `watch`.
/// 3. Zero slot-holding while waiting: semaphore permits are acquired only after all dependencies finish.
/// 4. Real-time streaming: outputs are yielded into `tx` the moment each tool finishes.
pub async fn execute_dag<F, Fut>(
    nodes: Vec<ToolCallNode>,
    pools: ConcurrencyPools,
    runner: F,
    result_tx: mpsc::Sender<ToolCallResult>,
) where
    F: Fn(String, String, String) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = (bool, serde_json::Value, Option<String>)> + Send + 'static,
{
    let runner = Arc::new(runner);

    // Create completion signals for every node in the DAG
    let mut completion_txs = HashMap::new();
    let mut completion_rxs = HashMap::new();

    for node in &nodes {
        let (tx, rx) = watch::channel(false);
        completion_txs.insert(node.call.id.clone(), tx);
        completion_rxs.insert(node.call.id.clone(), rx);
    }

    let mut join_handles = Vec::with_capacity(nodes.len());

    for node in nodes {
        let pools = pools.clone();
        let runner = runner.clone();
        let result_tx = result_tx.clone();
        let completion_tx = completion_txs.remove(&node.call.id).unwrap();

        // Collect dependency watch receivers
        let mut dep_rxs = Vec::new();
        for dep_id in &node.depends_on {
            if let Some(rx) = completion_rxs.get(dep_id) {
                dep_rxs.push(rx.clone());
            }
        }

        let handle = tokio::spawn(async move {
            // Step 1: Wait for all declared dependencies to signal completion.
            // No semaphore slot is acquired during this wait.
            for mut rx in dep_rxs {
                let _ = rx.wait_for(|&finished| finished).await;
            }

            // Step 2: Acquire permit from the corresponding concurrency pool.
            let _permit = match node.pool {
                ToolPool::Io => pools.io.acquire().await.ok(),
                ToolPool::Cpu => pools.cpu.acquire().await.ok(),
            };

            // Step 3: Execute tool handler.
            let call_id = node.call.id.clone();
            let call_name = node.call.name.clone();
            let (success, output, error) = runner(call_id.clone(), call_name.clone(), node.call.args_json.clone()).await;

            // Step 4: Drop permit before notifying dependents and streaming.
            drop(_permit);

            // Step 5: Notify downstream dependents immediately.
            let _ = completion_tx.send(true);

            // Step 6: Stream result to consumer.
            let _ = result_tx.send(ToolCallResult {
                id: call_id,
                name: call_name,
                success,
                output,
                error,
            }).await;
        });

        join_handles.push(handle);
    }

    // Await all tasks to finish
    for handle in join_handles {
        let _ = handle.await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::scheduler::plan_reactive;
    use crate::engine::types::ToolCallSpec;
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    #[tokio::test]
    async fn independent_calls_run_in_parallel() {
        let calls = vec![
            ToolCallSpec::new("1", "file_read", r#"{"path":"a.txt"}"#),
            ToolCallSpec::new("2", "file_read", r#"{"path":"b.txt"}"#),
            ToolCallSpec::new("3", "file_read", r#"{"path":"c.txt"}"#),
        ];

        let nodes = plan_reactive(&calls);
        let pools = ConcurrencyPools::new(16, 4);
        let (tx, mut rx) = mpsc::channel(10);

        let active = Arc::new(AtomicUsize::new(0));
        let max_concurrent = Arc::new(AtomicUsize::new(0));

        let active_clone = active.clone();
        let max_clone = max_concurrent.clone();

        execute_dag(
            nodes,
            pools,
            move |id, name, _args| {
                let active = active_clone.clone();
                let max_c = max_clone.clone();
                async move {
                    let cur = active.fetch_add(1, Ordering::SeqCst) + 1;
                    max_c.fetch_max(cur, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    active.fetch_sub(1, Ordering::SeqCst);
                    (true, json!({ "id": id, "name": name }), None)
                }
            },
            tx,
        ).await;

        let mut results = Vec::new();
        while let Ok(res) = rx.try_recv() {
            results.push(res);
        }

        assert_eq!(results.len(), 3);
        // All 3 independent reads should have run concurrently
        assert_eq!(max_concurrent.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn dependent_write_waits_for_read() {
        let calls = vec![
            ToolCallSpec::new("1", "file_read", r#"{"path":"data.txt"}"#),
            ToolCallSpec::new("2", "file_write", r#"{"path":"data.txt","content":"v2"}"#),
        ];

        let nodes = plan_reactive(&calls);
        let pools = ConcurrencyPools::new(16, 4);
        let (tx, mut rx) = mpsc::channel(10);

        let events = Arc::new(tokio::sync::Mutex::new(Vec::new()));
        let events_clone = events.clone();

        execute_dag(
            nodes,
            pools,
            move |id, _name, _args| {
                let ev = events_clone.clone();
                async move {
                    ev.lock().await.push(format!("start:{id}"));
                    tokio::time::sleep(Duration::from_millis(30)).await;
                    ev.lock().await.push(format!("finish:{id}"));
                    (true, json!({ "id": id }), None)
                }
            },
            tx,
        ).await;

        let log = events.lock().await.clone();
        assert_eq!(log, vec!["start:1", "finish:1", "start:2", "finish:2"]);

        let mut count = 0;
        while rx.try_recv().is_ok() {
            count += 1;
        }
        assert_eq!(count, 2);
    }
}

//! [PARALLEL_TOOL_ENGINE_SPEC §3, §4] Core types for the reactive DAG tool engine.

use serde::{Deserialize, Serialize};

/// Concurrency pool classification (§3.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ToolPool {
    /// High-concurrency I/O pool (cap = 16): disk, network, MCP.
    Io,
    /// Conservative CPU / compute pool (cap = 4..6): heavy compilation, rootfs, shell.
    Cpu,
}

impl ToolPool {
    /// Default concurrency limit per pool.
    pub fn default_cap(&self) -> usize {
        match self {
            ToolPool::Io => 16,
            ToolPool::Cpu => 4,
        }
    }
}

/// The concurrency effect of a tool call (§3.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ToolEffect {
    /// Shared read: concurrent with other readers of the same resource.
    Read,
    /// Exclusive write: requires exclusive access to the target resource.
    /// Blocks both readers and writers of the same resource.
    Write,
    /// Hermetic global barrier: runs completely alone.
    /// Waits for all earlier calls, and every later call waits for it.
    GlobalBarrier,
    /// Meta operations: serialized among themselves (e.g., plan_submit).
    Meta,
}

/// Input specification of a tool call in a batch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolCallSpec {
    pub id: String,
    pub name: String,
    pub args_json: String,
}

impl ToolCallSpec {
    pub fn new(id: impl Into<String>, name: impl Into<String>, args_json: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            args_json: args_json.into(),
        }
    }
}

/// A node in the planned reactive execution DAG (§4.1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolCallNode {
    pub call: ToolCallSpec,
    pub effect: ToolEffect,
    pub pool: ToolPool,
    pub resources: Vec<String>,
    pub depends_on: Vec<String>,
}

/// Execution outcome for a single tool call (§5.2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolCallResult {
    pub id: String,
    pub name: String,
    pub success: bool,
    pub output: serde_json::Value,
    pub error: Option<String>,
}

//! [PARALLEL_TOOL_ENGINE_SPEC §5.2] Native Rust Reactive DAG Tool Engine.
//!
//! Provides zero-barrier, hierarchical reader-writer scheduling and streaming
//! parallel execution over Tokio concurrency pools for native daemon tools.

pub mod executor;
pub mod keys;
pub mod scheduler;
pub mod types;

pub use executor::{execute_dag, ConcurrencyPools};
pub use keys::{classify_tool, keys_overlap, normalize_resource_path};
pub use scheduler::plan_reactive;
pub use types::{ToolCallNode, ToolCallResult, ToolCallSpec, ToolEffect, ToolPool};
pub mod files;

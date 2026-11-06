package com.openminis.app.engine

/**
 * [P1-io-concurrency] Which tools are DISK/NETWORK I/O rather than
 * CPU-bound work. The CPU-derived ceiling (AgentConcurrency.parallel,
 * max 6) exists for subagents: every spawn is a live model stream, a UI
 * row, a socket and a share of the provider's rate limit. A file
 * read/write holds none of that — routing it through the same semaphore
 * made 10 parallel writes to 10 distinct files run 6-and-wait for no
 * reason.
 *
 * Consumers: EngineAgentLoop acquires from the I/O semaphore for these
 * tools and from the CPU semaphore for everything else — shell, browser,
 * spawn_*, mcp: anything with shared session state or ordered side
 * effects stays on the conservative CPU gate regardless of this set.
 *
 * Pure JVM — the set is data, the predicate is one lookup.
 */
object ToolConcurrencyClass {

    /** Disk/network tools: high fan-out is safe and desirable. */
    val IO_TOOLS: Set<String> = setOf(
        "file_read", "file_write", "file_edit", "read_image", "webfetch",
    )

    fun isIo(toolName: String): Boolean = toolName in IO_TOOLS
}

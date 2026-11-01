package com.voicedock.ai.mcp

/**
 * v1.4.1 — what the servers said the last time we asked, kept only in memory.
 *
 * The confirmation decision has to be made *before* the network call (that is the point of confirming), so the
 * pipeline needs to know whether the tool it is about to run is read-only. It can only know that from the last
 * `tools/list`. So: remember the list for a short while, and if it is stale or missing, the answer is
 * "confirm" — which is what [McpCore.McpTool.needsConfirm] gives for an unknown tool.
 *
 * Nothing is persisted: a restart forgets every tool, and the first run after a restart asks for confirmation.
 */
object McpTools {

    private const val TTL_MS = 10 * 60 * 1000L

    private data class Snapshot(val at: Long, val tools: List<McpCore.McpTool>)

    private var snapshot: Snapshot? = null

    /** @param at only supplied by tests, so the expiry rule can be exercised without waiting ten minutes. */
    fun remember(tools: List<McpCore.McpTool>, at: Long = System.currentTimeMillis()) {
        snapshot = Snapshot(at, tools)
    }

    fun clear() {
        snapshot = null
    }

    /** Fresh tools, or an empty list — an expired snapshot is treated as "we know nothing". */
    fun cached(now: Long = System.currentTimeMillis()): List<McpCore.McpTool> {
        val s = snapshot ?: return emptyList()
        return if (now - s.at > TTL_MS) emptyList() else s.tools
    }

    /**
     * The tool a sentence names, by the same longest-name rule the executor uses, so the confirmation shown and
     * the call made can never be about two different tools.
     */
    fun named(text: String, now: Long = System.currentTimeMillis()): McpCore.McpTool? {
        val lower = text.lowercase()
        return cached(now)
            .filter { it.name.isNotBlank() && lower.contains(it.name.lowercase()) }
            .maxByOrNull { it.name.length }
    }

    /** True when running this sentence needs a confirmation card first. */
    fun needsConfirm(text: String, now: Long = System.currentTimeMillis()): Boolean =
        named(text, now)?.needsConfirm ?: true
}

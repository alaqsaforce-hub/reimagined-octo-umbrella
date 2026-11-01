package com.voicedock.ai.mcp

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v1.4.0 — MCP over "streamable HTTP".
 *
 * One exchange per call: POST a JSON-RPC message, read either a JSON body or an SSE stream. The session id the
 * server hands back in `Mcp-Session-Id` is remembered per server and echoed afterwards, which is what the spec
 * wants for stateful servers; stateless servers simply ignore it.
 *
 * Nothing is retried automatically: a tool call may have side effects, so a retry has to be the user's decision.
 */
object McpClient {

    private val JSON = "application/json".toMediaType()
    private const val ACCEPT = "application/json, text/event-stream"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Sessions are per process; a restart simply handshakes again. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class Connect(val ok: Boolean, val tools: List<McpCore.McpTool> = emptyList(), val reason: String? = null)

    fun forget(serverId: String) { sessions.remove(serverId) }

    /** Handshake + `tools/list`. Nothing is cached: the list always describes the server as it is now. */
    fun connect(server: McpCore.McpServer, token: String? = null): Connect {
        if (!server.url.startsWith("http")) return Connect(false, reason = "no_url")
        val (problem, sessionId) = handshake(server, token)
        if (problem != null) return Connect(false, reason = problem)
        val reply = post(server, token, McpCore.toolsListBody(), sessionId) ?: return Connect(false, reason = "not_mcp")
        if (!reply.ok) return Connect(false, reason = "rpc_" + (reply.errorMessage ?: "error").take(60))
        val tools = McpCore.parseTools(reply, server)
        return Connect(true, tools)
    }

    /** Runs one listed tool. The reply is reduced to text by [McpCore.parseToolResult]. */
    fun call(server: McpCore.McpServer, tool: String, argumentsJson: String, token: String? = null):
        McpCore.ToolRun {
        val sessionId = sessions[server.id]
        val reply = post(server, token, McpCore.toolsCallBody(3, tool, argumentsJson), sessionId)
            ?: return McpCore.ToolRun(false, "")
        return McpCore.parseToolResult(reply)
    }

    /** Returns null on success, or a failure reason. */
    private fun handshake(server: McpCore.McpServer, token: String?): Pair<String?, String?> {
        val reply = post(server, token, McpCore.initializeBody(1), null)
            ?: return "not_mcp" to null
        if (!reply.ok) return ("rpc_" + (reply.errorMessage ?: "initialize").take(60)) to null
        val sessionId = sessions[server.id]
        // `notifications/initialized` has no id and no reply; a server that dislikes it is still usable.
        runCatching { post(server, token, McpCore.initializedNotification(), sessionId) }
        return null to sessionId
    }

    private fun post(
        server: McpCore.McpServer,
        token: String?,
        body: String,
        sessionId: String?,
    ): McpCore.RpcReply? {
        val b = Request.Builder()
            .url(server.url)
            .header("Accept", ACCEPT)
            .header("MCP-Protocol-Version", McpCore.PROTOCOL_VERSION)
            .post(body.toRequestBody(JSON))
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer ${token.trim()}")
        if (!sessionId.isNullOrBlank()) b.header("Mcp-Session-Id", sessionId)
        return try {
            http.newCall(b.build()).execute().use { r ->
                r.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessions[server.id] = it }
                if (r.code == 401 || r.code == 403) {
                    sessions.remove(server.id)
                    return McpCore.RpcReply(null, null, r.code, "unauthorized")
                }
                if (!r.isSuccessful) return McpCore.RpcReply(null, null, r.code, "http ${r.code}")
                val text = r.body?.string().orEmpty()
                McpCore.parseRpc(text)
            }
        } catch (e: java.net.SocketTimeoutException) {
            McpCore.RpcReply(null, null, -1, "timeout")
        } catch (e: IOException) {
            McpCore.RpcReply(null, null, -1, "nonet")
        } catch (e: Exception) {
            McpCore.RpcReply(null, null, -2, "io")
        }
    }

    /**
     * Turns a reply's error code into the vocabulary [McpCore.failure] speaks: `http_401`, `nonet`, `timeout`,
     * `rpc_<message>`. Negative codes are transport-level; anything that looks like an HTTP status stays HTTP.
     */
    fun reasonFor(reply: McpCore.RpcReply?): String {
        val code = reply?.errorCode ?: return "not_mcp"
        return when {
            code == -1 && reply.errorMessage == "timeout" -> "timeout"
            code == -1 -> "nonet"
            code == -2 -> "io"
            code >= 100 -> "http_$code"
            else -> "rpc_" + reply.errorMessage.orEmpty().take(40).ifBlank { "error" }
        }
    }
}

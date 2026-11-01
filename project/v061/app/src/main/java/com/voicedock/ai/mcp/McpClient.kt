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

    data class Connect(
        val ok: Boolean,
        val tools: List<McpCore.McpTool> = emptyList(),
        val reason: String? = null,
        /** `serverInfo.name` from the handshake — shown so the user sees *what* answered. */
        val serverName: String = "",
        val serverVersion: String = "",
    )

    fun forget(serverId: String) { sessions.remove(serverId) }

    /**
     * Handshake + `tools/list`. Nothing is cached on disk: the list always describes the server as it is now.
     * The `initialize` reply is kept only for its `serverInfo`, which names the server in the UI.
     */
    fun connect(server: McpCore.McpServer, token: String? = null): Connect {
        if (!server.url.startsWith("http")) return Connect(false, reason = "no_url")

        val init = post(server, token, McpCore.initializeBody(1), null) ?: return Connect(false, reason = "not_mcp")
        if (!init.ok) return Connect(false, reason = "rpc_" + (init.errorMessage ?: "initialize").take(60))
        val (serverName, serverVersion) = McpCore.serverInfo(init)
        val sessionId = sessions[server.id]

        // `notifications/initialized` has no reply; a server that dislikes it is still usable.
        runCatching { post(server, token, McpCore.initializedNotification(), sessionId) }

        val reply = post(server, token, McpCore.toolsListBody(), sessionId) ?: return Connect(false, reason = "not_mcp")
        if (!reply.ok) return Connect(false, reason = "rpc_" + (reply.errorMessage ?: "tools/list").take(60))
        return Connect(true, McpCore.parseTools(reply, server), null, serverName, serverVersion)
    }

    /** Runs one listed tool. The reply is reduced to text by [McpCore.parseToolResult]. */
    fun call(server: McpCore.McpServer, tool: String, argumentsJson: String, token: String? = null):
        McpCore.ToolRun {
        val sessionId = sessions[server.id]
        val reply = post(server, token, McpCore.toolsCallBody(3, tool, argumentsJson), sessionId)
            ?: return McpCore.ToolRun(false, "")
        return McpCore.parseToolResult(reply)
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

package com.voicedock.ai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * v1.4.0 — the MCP half that makes no network calls.
 *
 * Every decision the client has to make is here so it can be unit-tested without a server: what a JSON-RPC
 * 2.0 message looks like, how a reply is read (plain JSON *or* SSE), which tools a server offers, what
 * arguments to send for a spoken sentence, and what to say when it fails.
 *
 * Deliberate limits, because an MCP tool can do anything the server allows:
 *  * the app never invents a tool name — it only calls a tool the server just listed;
 *  * `mcp.run` always confirms first (the app cannot know what a third-party tool will do);
 *  * tokens never appear in a URL that is shown or logged ([redact]).
 */
object McpCore {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val CLIENT_NAME = "VoiceDock AI"
    const val CLIENT_VERSION = "1.4.0"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ---- the server list (persisted inside AppSettings, never a secret) -----------------------------

    /** One configured MCP server. [tokenName] points at a [com.voicedock.ai.security.SecretStore] entry. */
    @Serializable
    data class McpServer(
        val id: String,
        val name: String,
        val url: String,
        val enabled: Boolean = true,
        /** SecretStore key for an optional bearer token; null = no auth header. */
        val tokenName: String? = null,
    ) {
        /** Stable id for "local" servers typed without a name. */
        val label: String get() = name.ifBlank { url }
    }

    fun sanitize(servers: List<McpServer>): List<McpServer> = servers
        .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
        .map {
            val url = it.url.trim().trimEnd('/')
            // A blank name is not allowed to become an empty row in Settings: fall back to the host/last path part.
            it.copy(name = it.name.trim().take(40).ifBlank { readableName(url) }, url = url)
        }
        .distinctBy { it.url }
        .take(8)

    /** "https://host.test/mcp/github" → "mcp" — short, printable, never a secret. */
    fun readableName(url: String): String =
        url.substringAfter("://", url).substringAfter('/').substringBefore('/').ifBlank { "mcp" }.take(40)

    /** A typed address becomes a server with a readable name ("github" for …/mcp/github). */
    fun serverFrom(name: String, url: String, tokenName: String? = null): McpServer? {
        val u = url.trim().let { if (it.contains("://")) it else "https://$it" }.trimEnd('/')
        if (!u.startsWith("https://") && !u.startsWith("http://")) return null
        if (u.length < 12 || u.contains(' ')) return null
        val id = u.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
        val readable = name.trim().ifBlank { readableName(u) }
        return McpServer(id = id, name = readable.take(40), url = u, tokenName = tokenName)
    }

    /** "https://user:pass@host/x?token=abc" → "https://host/x" — for anything shown or logged. */
    fun redact(url: String): String {
        var u = url
        if (u.contains("://")) {
            val (scheme, rest) = u.split("://", limit = 2)
            val hostPart = rest.substringAfter('@')
            u = "$scheme://$hostPart"
        }
        val q = u.indexOf('?')
        if (q >= 0) {
            val kept = u.substring(q + 1).split('&').filter {
                val k = it.substringBefore('=').lowercase()
                k in setOf("page", "limit", "lang", "v")
            }
            u = u.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
        return u
    }

    // ---- JSON-RPC 2.0 ------------------------------------------------------------------------------

    fun initializeBody(id: Long = 1): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "initialize")
        put("params", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", CLIENT_NAME); put("version", CLIENT_VERSION) })
        })
    }.toString()

    /** The one notification in the handshake; the server ignores its reply, if any. */
    fun initializedNotification(): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "notifications/initialized")
    }.toString()

    fun toolsListBody(id: Long = 2): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "tools/list")
    }.toString()

    fun toolsCallBody(id: Long = 3, tool: String, argumentsJson: String): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
        put("params", buildJsonObject {
            put("name", tool)
            put("arguments", runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
                .getOrElse { buildJsonObject { } })
        })
    }.toString()

    // ---- replies -----------------------------------------------------------------------------------

    /** A single JSON-RPC reply, however the transport delivered it (empty body allowed for notifications). */
    data class RpcReply(val id: Long?, val result: JsonObject?, val errorCode: Int?, val errorMessage: String?) {
        val ok: Boolean get() = errorCode == null && result != null
    }

    /**
     * Streamable-HTTP replies are either `application/json` or an SSE stream whose `data:` lines carry the
     * JSON-RPC message. Both are handled; a stream with only notifications is not an error, it is "no answer".
     */
    fun parseRpc(body: String): RpcReply? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val candidate = if (looksLikeSse(text)) {
            text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull { it.contains("\"result\"") || it.contains("\"error\"") }
                ?: return null
        } else text
        val obj = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: return null
        val err = obj["error"]?.let { e -> runCatching { e.jsonObject }.getOrNull() }
        return RpcReply(
            id = runCatching { obj["id"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull(),
            result = obj["result"]?.let { runCatching { it.jsonObject }.getOrNull() },
            errorCode = err?.get("code")?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() },
            errorMessage = err?.get("message")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
        )
    }

    fun looksLikeSse(body: String): Boolean =
        body.lineSequence().any { it.startsWith("event:") || it.startsWith("data:") }

    // ---- tools -------------------------------------------------------------------------------------

    /** A tool as the server described it. [props] keeps the schema's property names so arguments can be shaped. */
    data class McpTool(
        val serverId: String,
        val serverName: String,
        val name: String,
        val description: String = "",
        val props: List<String> = emptyList(),
        val required: List<String> = emptyList(),
        /** `annotations.readOnlyHint` — the server's own promise that the tool changes nothing. */
        val readOnly: Boolean = false,
        /** `annotations.destructiveHint` — the server's own warning that it can destroy data. */
        val destructive: Boolean = false,
        /** `annotations.title` when the server provides a human title. */
        val titleText: String = "",
    ) {
        val title: String get() = titleText.ifBlank { if (description.isBlank()) name else "$name — ${description.take(70)}" }

        /**
         * v1.4.1 — the policy, in one place:
         *  * a tool the server marks read-only and *not* destructive runs without a question;
         *  * everything else (write tools, and **any tool that says nothing**) confirms first.
         * Absence of annotations is not permission — a server that does not describe its tools is unknown,
         * and unknown is treated as dangerous.
         */
        val needsConfirm: Boolean get() = !readOnly || destructive
    }

    /** `initialize` result → ("files-mcp", "0.4.1"), so the UI can name what it is talking to. */
    fun serverInfo(reply: RpcReply?): Pair<String, String> {
        val info = reply?.result?.get("serverInfo")?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return "" to ""
        return (info["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(40)) to
            (info["version"]?.jsonPrimitive?.contentOrNull.orEmpty().take(20))
    }

    fun parseTools(reply: RpcReply, server: McpServer): List<McpTool> {
        val arr = reply.result?.get("tools")?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val schema = o["inputSchema"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val props = schema?.get("properties")?.let { p -> runCatching { p.jsonObject.keys.toList() }.getOrNull() }
                ?: emptyList()
            val required = (schema?.get("required") as? JsonArray)?.mapNotNull {
                runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
            } ?: emptyList()
            val annotations = o["annotations"]?.let { runCatching { it.jsonObject }.getOrNull() }
            fun hint(key: String): Boolean =
                annotations?.get(key)?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() } ?: false
            McpTool(
                serverId = server.id,
                serverName = server.label,
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                props = props,
                required = required,
                readOnly = hint("readOnlyHint"),
                destructive = hint("destructiveHint"),
                titleText = annotations?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** True when the schema has no required argument — such a tool can run from a bare name. */
    fun needsArguments(tool: McpTool): Boolean = tool.required.isNotEmpty()

    /**
     * What to send as `arguments` for a spoken sentence:
     *  1. the user literally dictated JSON → send it (validated);
     *  2. `key=value key2=value2` → those keys;
     *  3. otherwise the whole sentence goes into the tool's first required/text argument (usually `query`).
     * Never invents keys the schema does not have, except the last-resort `query`/`input`/`text`.
     */
    fun argumentsFor(tool: McpTool, spoken: String): String {
        val text = spoken.trim()
        if (text.startsWith("{") && text.endsWith("}")) {
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (parsed != null) return parsed.toString()
        }
        val pairs = Regex("(?<!\\S)([A-Za-z_][A-Za-z0-9_]{0,30})=([^\\s]+)")
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        if (pairs.isNotEmpty()) {
            return buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
        }
        val key = tool.required.firstOrNull { p -> p in tool.props }
            ?: tool.props.firstOrNull { it in setOf("query", "q", "text", "input", "prompt", "search") }
            ?: tool.required.firstOrNull()
            ?: "query"
        if (text.isBlank()) return "{}"
        return buildJsonObject { put(key, text) }.toString()
    }

    // ---- tool results ------------------------------------------------------------------------------

    /** What a `tools/call` produced, already reduced to something speakable. */
    data class ToolRun(val ok: Boolean, val text: String, val isError: Boolean = false)

    fun parseToolResult(reply: RpcReply): ToolRun {
        if (reply.errorCode != null) return ToolRun(false, "", false)
        val res = reply.result ?: return ToolRun(false, "", false)
        val isError = runCatching { res["isError"]?.jsonPrimitive?.contentOrNull?.toBoolean() }.getOrNull() ?: false
        val parts = mutableListOf<String>()
        var unseen = 0
        (res["content"] as? JsonArray)?.forEach { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> o["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += it }
                else -> unseen++
            }
        }
        // Some servers answer with structured data and no content array at all.
        if (parts.isEmpty() && res["structuredContent"] != null) parts += res.getValue("structuredContent").toString()
        val body = parts.joinToString("\n").trim()
        val text = when {
            body.isNotBlank() && unseen > 0 -> body + "\n(আরও ${unseen}টি অংশ ছিল যা টেক্সট নয় — ছবি/ফাইল)"
            body.isNotBlank() -> body
            unseen > 0 -> "টুলটি উত্তর দিয়েছে, কিন্তু টেক্সট নয় (${unseen}টি অংশ) — এই মোডে দেখানো যাচ্ছে না।"
            else -> "টুলটি কিছু ফেরত দেয়নি।"
        }
        return ToolRun(true, text.take(4000), isError)
    }

    // ---- words -------------------------------------------------------------------------------------

    fun noServers(bn: Boolean): String = if (bn) {
        "কোনো MCP সার্ভার যোগ করা নেই। সেটিংস → MCP-এ একটা ঠিকানা দিন (যেমন আপনার নিজের ফাইল সার্ভার)।"
    } else {
        "No MCP server is configured. Add one in Settings → MCP (e.g. your own filesystem server)."
    }

    /** "পড়া-মাত্র" / "লেখে — আগে জিজ্ঞেস করবে" — shown next to every tool so the policy is visible. */
    fun policyLabel(tool: McpTool, bn: Boolean): String = when {
        tool.readOnly && !tool.destructive -> if (bn) "পড়া-মাত্র" else "read-only"
        tool.destructive -> if (bn) "ধ্বংসকারী — আগে জিজ্ঞেস করবে" else "destructive — asks first"
        else -> if (bn) "অজানা — আগে জিজ্ঞেস করবে" else "unclassified — asks first"
    }

    fun toolsAnswer(tools: List<McpTool>, bn: Boolean): String {
        if (tools.isEmpty()) return if (bn) {
            "সার্ভারের সাথে কথা হয়েছে, কিন্তু সে কোনো টুল দেয়নি।"
        } else {
            "The server answered, but it exposes no tools."
        }
        val byServer = tools.groupBy { it.serverName }
        val head = if (bn) "MCP টুল (${tools.size}টি):" else "MCP tools (${tools.size}):"
        val lines = byServer.entries.take(6).flatMap { (server, list) ->
            val ro = list.count { !it.needsConfirm }
            listOf(
                "• $server — ${list.size}টি" + (if (bn) " (পড়া-মাত্র $ro)" else " ($ro read-only)"),
            ) + list.take(8).map { "   ◦ ${it.name} — ${policyLabel(it, bn)}" }
        }
        val tail = if (tools.size > 12 && bn) "\n(প্রথম কয়েকটা দেখানো হলো)" else ""
        return (listOf(head) + lines).joinToString("\n") + tail
    }

    fun runAnswer(run: ToolRun, tool: McpTool, bn: Boolean): String {
        val head = if (bn) "🔧 ${tool.name} (${tool.serverName}):" else "🔧 ${tool.name} (${tool.serverName}):"
        val body = if (run.isError && bn) "টুলটি এরর বলেছে:\n${run.text}" else run.text
        return "$head\n$body"
    }

    fun unknownTool(name: String, tools: List<McpTool>, bn: Boolean): String {
        val names = tools.take(6).joinToString(", ") { it.name }
        return if (bn) {
            "‘$name’ নামে কোনো টুল পাওয়া যায়নি। এখন যেগুলো আছে: $names"
        } else {
            "No tool named “$name”. Available now: $names"
        }
    }

    fun failure(reason: String?, bn: Boolean): String {
        val r = reason.orEmpty()
        fun t(b: String, e: String) = if (bn) b else e
        return when {
            r == "no_url" -> t("সার্ভারের ঠিকানাটা ঠিক নয় (https:// দিয়ে শুরু হতে হবে)।",
                "That server address is not valid (it must start with https://).")
            r == "nonet" -> t("ইন্টারনেট নেই, তাই MCP সার্ভারে পৌঁছানো যাচ্ছে না।", "No internet, so the MCP server cannot be reached.")
            r == "timeout" -> t("সার্ভার সময়মতো উত্তর দেয়নি।", "The server did not answer in time.")
            r == "http_401" || r == "http_403" -> t(
                "সার্ভার অনুমতি দিল না ($r)। সেটিংস → MCP-এ টোকেনটা দেখে নিন।",
                "The server refused access ($r). Check the token in Settings → MCP.",
            )
            r == "http_404" -> t("এই ঠিকানায় MCP endpoint নেই (404)।", "No MCP endpoint at that address (404).")
            r.startsWith("http_") -> t("সার্ভার HTTP ${r.removePrefix("http_")} ফেরত দিয়েছে।", "The server returned HTTP ${r.removePrefix("http_")}.")
            r.startsWith("rpc_") -> t("সার্ভার JSON-RPC এরর দিয়েছে (${r.removePrefix("rpc_")})।",
                "The server returned a JSON-RPC error (${r.removePrefix("rpc_")}).")
            r == "not_mcp" -> t("উত্তরটা MCP/JSON-RPC ফরম্যাটে ছিল না — ঠিকানাটা MCP সার্ভারের কিনা দেখে নিন।",
                "The reply was not JSON-RPC — check that the address is an MCP server.")
            else -> t("MCP সার্ভারে সমস্যা: ${r.ifBlank { "অজানা কারণ" }}", "MCP problem: ${r.ifBlank { "unknown" }}")
        }
    }
}

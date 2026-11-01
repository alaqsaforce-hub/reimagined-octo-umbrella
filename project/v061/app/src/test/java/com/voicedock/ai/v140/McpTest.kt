package com.voicedock.ai.v140

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.mcp.McpCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 — MCP without a server. These tests pin the parts that a real server would otherwise hide:
 * the exact JSON-RPC messages, SSE *and* JSON replies, tool discovery, argument shaping, and the rule that the
 * app never invents a tool name and never reports a failed call as success.
 */
class McpCoreTest {

    private val json = Json { isLenient = true }

    private fun server(name: String = "files", url: String = "https://example.test/mcp") =
        McpCore.McpServer(id = "s1", name = name, url = url)

    private val toolsJson = """
        {"jsonrpc":"2.0","id":2,"result":{"tools":[
          {"name":"read_file","description":"Read a text file",
           "inputSchema":{"type":"object","properties":{"path":{"type":"string"},"max":{"type":"integer"}},
                          "required":["path"]}},
          {"name":"list_dir","description":"List a directory",
           "inputSchema":{"type":"object","properties":{}}},
          {"description":"nameless — must be dropped"}
        ]}}
    """.trimIndent()

    @Test fun theHandshakeIsTheMessageTheSpecExpects() {
        val init = json.parseToJsonElement(McpCore.initializeBody()).jsonObject
        assertEquals("2.0", init["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("initialize", init["method"]?.jsonPrimitive?.content)
        val params = init["params"]!!.jsonObject
        assertEquals(McpCore.PROTOCOL_VERSION, params["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals(McpCore.CLIENT_NAME, params["clientInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("notifications/initialized",
            json.parseToJsonElement(McpCore.initializedNotification()).jsonObject["method"]?.jsonPrimitive?.content)
        assertEquals("tools/list",
            json.parseToJsonElement(McpCore.toolsListBody()).jsonObject["method"]?.jsonPrimitive?.content)
    }

    @Test fun aToolCallCarriesRealJsonArguments() {
        val body = json.parseToJsonElement(
            McpCore.toolsCallBody(7, "read_file", """{"path":"/sdcard/a.txt"}"""),
        ).jsonObject
        assertEquals("tools/call", body["method"]?.jsonPrimitive?.content)
        val params = body["params"]!!.jsonObject
        assertEquals("read_file", params["name"]?.jsonPrimitive?.content)
        assertEquals("/sdcard/a.txt", params["arguments"]!!.jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test fun brokenArgumentsBecomeAnEmptyObjectNotACrash() {
        val body = json.parseToJsonElement(McpCore.toolsCallBody(3, "x", "{not json")).jsonObject
        assertTrue(body["params"]!!.jsonObject["arguments"]!!.jsonObject.isEmpty())
    }

    @Test fun repliesArriveAsPlainJsonOrAsAnSseStream() {
        val plain = McpCore.parseRpc("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}""")
        assertNotNull(plain)
        assertTrue(plain!!.ok)

        val sse = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}

            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping"}]}}
        """.trimIndent()
        assertTrue(McpCore.looksLikeSse(sse))
        val fromSse = McpCore.parseRpc(sse)
        assertNotNull(fromSse)
        assertEquals(1, McpCore.parseTools(fromSse!!, server()).size)

        // A stream with only notifications is "no answer", not a crash and not a fake success.
        assertNull(McpCore.parseRpc("event: ping\ndata: {\"jsonrpc\":\"2.0\"}"))
        assertNull(McpCore.parseRpc(""))
        assertNull(McpCore.parseRpc("<html>not json</html>"))
    }

    @Test fun errorsAreReadNotSwallowed() {
        val r = McpCore.parseRpc("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"bad args"}}""")
        assertNotNull(r)
        assertFalse(r!!.ok)
        assertEquals(-32602, r.errorCode)
        assertEquals("bad args", r.errorMessage)
    }

    @Test fun toolsKeepTheirSchemaAndNamelessOnesAreDropped() {
        val reply = McpCore.parseRpc(toolsJson)!!
        val tools = McpCore.parseTools(reply, server())
        assertEquals(listOf("read_file", "list_dir"), tools.map { it.name })
        assertEquals(listOf("path", "max"), tools[0].props)
        assertEquals(listOf("path"), tools[0].required)
        assertTrue(McpCore.needsArguments(tools[0]))
        assertFalse(McpCore.needsArguments(tools[1]))
        assertEquals("files", tools[0].serverName)
    }

    @Test fun aSpokenSentenceBecomesTheToolsOwnArgument() {
        val tools = McpCore.parseTools(McpCore.parseRpc(toolsJson)!!, server())
        assertEquals("""{"path":"/sdcard/a.txt"}""", McpCore.argumentsFor(tools[0], """{"path":"/sdcard/a.txt"}"""))
        assertEquals("""{"path":"/sdcard/a.txt"}""", McpCore.argumentsFor(tools[0], "path=/sdcard/a.txt"))
        // Free speech goes into the first required property — never a made-up key.
        assertEquals("""{"path":"আমার ফাইল"}""", McpCore.argumentsFor(tools[0], "আমার ফাইল"))
        assertEquals("{}", McpCore.argumentsFor(tools[1], ""))

        val search = tools[0].copy(props = listOf("path", "q"), required = listOf("path"))
        assertEquals("""{"path":"hello"}""", McpCore.argumentsFor(search, "hello"))
        val queryOnly = tools[0].copy(props = listOf("query"), required = emptyList())
        assertEquals("""{"query":"আজকের খবর"}""", McpCore.argumentsFor(queryOnly, "আজকের খবর"))
    }

    @Test fun toolResultsAreReducedToSomethingSpeakable() {
        val text = McpCore.parseToolResult(
            McpCore.parseRpc("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"রিপোর্ট: ৩টি ইস্যু"}]}}""")!!,
        )
        assertTrue(text.ok)
        assertFalse(text.isError)
        assertEquals("রিপোর্ট: ৩টি ইস্যু", text.text)

        val failed = McpCore.parseToolResult(
            McpCore.parseRpc("""{"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text","text":"no such file"}]}}""")!!,
        )
        assertTrue(failed.ok)
        assertTrue(failed.isError)

        val imageOnly = McpCore.parseToolResult(
            McpCore.parseRpc("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"image","data":"…","mimeType":"image/png"}]}}""")!!,
        )
        assertTrue(imageOnly.text.contains("টেক্সট নয়"))

        val empty = McpCore.parseToolResult(
            McpCore.parseRpc("""{"jsonrpc":"2.0","id":3,"result":{"content":[]}}""")!!,
        )
        assertTrue(empty.text.contains("কিছু ফেরত দেয়নি"))

        // A transport-level failure is never dressed up as an answer.
        assertFalse(McpCore.parseToolResult(McpCore.RpcReply(3, null, -32602, "bad args")).ok)
    }

    @Test fun addressesAreCheckedAndSecretsNeverShown() {
        assertNull(McpCore.serverFrom("x", "not a url"))
        assertNull(McpCore.serverFrom("x", "ftp://example.test/mcp"))
        val made = McpCore.serverFrom("", "example.test/mcp")
        assertEquals("https://example.test/mcp", made!!.url)
        assertEquals("mcp", made.name)
        assertEquals("https-example-test-mcp", made.id)

        // Credentials in an address are dropped wherever they appear — a shown URL is never a carrier of secrets.
        assertEquals("https://example.test/mcp", McpCore.redact("https://user:pw@example.test/mcp"))
        assertEquals("https://example.test/mcp", McpCore.redact("https://user:pw@example.test/mcp?token=SEKRET"))
        assertEquals("https://example.test/mcp?page=2", McpCore.redact("https://example.test/mcp?page=2&key=SEKRET"))
        // The sanitizer keeps the list small and drops junk instead of asking the network about it.
        val many = (1..12).map { McpCore.serverFrom("s$it", "https://h$it.test/mcp")!! }
        assertEquals(8, McpCore.sanitize(many).size)
        assertTrue(McpCore.sanitize(listOf(McpCore.McpServer("a", "", "nope"))).isEmpty())
        assertTrue(McpCore.sanitize(listOf(made.copy(name = ""))).first().name.isNotBlank())
    }

    @Test fun theWordsAreHonestInBothLanguages() {
        assertTrue(McpCore.noServers(true).contains("MCP"))
        assertTrue(McpCore.toolsAnswer(emptyList(), false).contains("no tools"))
        val tools = McpCore.parseTools(McpCore.parseRpc(toolsJson)!!, server())
        val listed = McpCore.toolsAnswer(tools, true)
        assertTrue(listed, listed.contains("2টি"))
        assertTrue(listed, listed.contains("read_file"))
        assertTrue(McpCore.unknownTool("nope", tools, true).contains("nope"))
        assertTrue(McpCore.failure("http_401", true).contains("টোকেন"))
        assertTrue(McpCore.failure("nonet", false).contains("internet"))
        assertTrue(McpCore.failure("rpc_bad args", false).contains("bad args"))
        assertTrue(McpCore.failure("weird", true).contains("weird"))
    }
}

class McpCommandTest {

    @Test fun askingForToolsListsThem() {
        assertEquals(Intent.MCP_TOOLS, CommandParser.offline("MCP টুল দেখাও").intent)
        assertEquals(Intent.MCP_TOOLS, CommandParser.offline("what MCP tools do you have").intent)
    }

    @Test fun askingToRunOneKeepsTheWholeSentence() {
        val spec = CommandParser.offline("mcp দিয়ে read_file চালাও path=/sdcard/a.txt")
        assertEquals(Intent.MCP_RUN, spec.intent)
        assertTrue(spec.slot(Slot.TEXT).orEmpty().contains("read_file"))
    }

    @Test fun ordinarySentencesAreStillNotMcp() {
        assertEquals(Intent.CAL_LIST, CommandParser.offline("আজকের ক্যালেন্ডার দেখাও").intent)
        assertEquals(Intent.REMINDER_ADD, CommandParser.offline("কাল ৩টায় মনে করিয়ে দাও পানি খেতে").intent)
        assertEquals(Intent.MSG_DIGEST, CommandParser.offline("আজকের মেসেজের সারমর্ম").intent)
    }

    @Test fun runningAToolAlwaysConfirms() {
        assertTrue(CommandRegistry.isDestructive(Intent.MCP_RUN))
        assertFalse(CommandRegistry.isDestructive(Intent.MCP_TOOLS))
        assertTrue(CommandRegistry.isAvailable(Intent.MCP_TOOLS))
        assertTrue(CommandRegistry.isAvailable(Intent.MCP_RUN))
        assertTrue(CommandRegistry.help(Intent.MCP_RUN, true).isNotBlank())
        assertNotNull(CommandRegistry.meta(Intent.MCP_RUN))
    }
}

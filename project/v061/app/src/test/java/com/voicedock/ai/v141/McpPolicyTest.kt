package com.voicedock.ai.v141

import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.mcp.McpCore
import com.voicedock.ai.mcp.McpTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.1 — the confirmation policy for MCP tools.
 *
 * The rule being tested is the one that keeps a "coworker" honest: **only** a tool the server itself marks
 * read-only runs without a question. A write tool, and — just as important — a tool that says nothing about
 * itself, must confirm. Silence is not permission.
 */
class McpPolicyTest {

    private fun server(name: String = "github") = McpCore.McpServer(id = name, name = name, url = "https://h.test/mcp")

    private val toolsJson = """
        {"jsonrpc":"2.0","id":2,"result":{"tools":[
          {"name":"get_file_contents","description":"Read a file",
           "inputSchema":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]},
           "annotations":{"readOnlyHint":true,"title":"Read file"}},
          {"name":"create_or_update_file","description":"Commit a file",
           "inputSchema":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},
                          "required":["path","content"]},
           "annotations":{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":false}},
          {"name":"search_code","description":"Search",
           "inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]},
           "annotations":{"readOnlyHint":true}},
          {"name":"mystery_tool","description":"no annotations at all",
           "inputSchema":{"type":"object","properties":{}}}
        ]}}
    """.trimIndent()

    private fun tools() = McpCore.parseTools(McpCore.parseRpc(toolsJson)!!, server())

    @After fun cleanUp() = McpTools.clear()

    @Test fun annotationsAreReadAndTitlesAreUsed() {
        val t = tools().associateBy { it.name }
        assertTrue(t.getValue("get_file_contents").readOnly)
        assertFalse(t.getValue("get_file_contents").destructive)
        assertEquals("Read file", t.getValue("get_file_contents").title)
        assertTrue(t.getValue("create_or_update_file").destructive)
        assertFalse(t.getValue("create_or_update_file").readOnly)
        assertFalse(t.getValue("mystery_tool").readOnly)
        // No server title → "name — description", so a listed tool is never just a bare identifier.
        assertEquals("mystery_tool — no annotations at all", t.getValue("mystery_tool").title)
    }

    @Test fun onlyReadOnlyToolsSkipTheQuestion() {
        val t = tools().associateBy { it.name }
        assertFalse("a read-only tool runs straight through", t.getValue("get_file_contents").needsConfirm)
        assertFalse(t.getValue("search_code").needsConfirm)
        assertTrue("committing must confirm", t.getValue("create_or_update_file").needsConfirm)
        // The rule that matters: no annotations is *not* permission.
        assertTrue("silence is treated as dangerous", t.getValue("mystery_tool").needsConfirm)
    }

    @Test fun thePolicyIsVisibleInWords() {
        val t = tools().associateBy { it.name }
        assertTrue(McpCore.policyLabel(t.getValue("get_file_contents"), true).contains("পড়া-মাত্র"))
        assertTrue(McpCore.policyLabel(t.getValue("create_or_update_file"), true).contains("ধ্বংসকারী"))
        assertTrue(McpCore.policyLabel(t.getValue("mystery_tool"), true).contains("অজানা"))
        assertTrue(McpCore.policyLabel(t.getValue("mystery_tool"), false).contains("asks first"))
        val answer = McpCore.toolsAnswer(tools(), true)
        assertTrue(answer, answer.contains("পড়া-মাত্র"))
    }

    @Test fun theToolThatWasListedLastIsTheOneThatDecides() {
        McpTools.remember(tools())
        assertFalse(McpTools.needsConfirm("mcp দিয়ে get_file_contents চালাও path=/a.txt"))
        assertTrue(McpTools.needsConfirm("mcp দিয়ে create_or_update_file চালাও path=/a.txt"))
        assertTrue(McpTools.needsConfirm("mcp দিয়ে mystery_tool চালাও"))
        // An unknown name is unknown, therefore confirm.
        assertTrue(McpTools.needsConfirm("mcp দিয়ে delete_everything চালাও"))
        // Longest name wins, so "get_file_contents" is not shadowed by a shorter tool name.
        assertEquals("get_file_contents", McpTools.named("mcp দিয়ে get_file_contents চালাও")?.name)
    }

    @Test fun aStaleOrEmptyCacheNeverAllowsAnything() {
        val now = 1_000_000L
        McpTools.remember(tools(), at = now)
        assertFalse(McpTools.needsConfirm("run get_file_contents", now = now + 5 * 60_000L))
        // After the TTL the app knows nothing again → confirm.
        assertTrue(McpTools.needsConfirm("run get_file_contents", now = now + 30 * 60_000L))
        McpTools.clear()
        assertTrue(McpTools.needsConfirm("run get_file_contents", now = now))
    }

    @Test fun thePipelineConfirmsPerToolNotPerIntent() {
        McpTools.remember(tools())
        val spec: (String) -> CommandSpec = { line -> CommandParser.offline(line) }
        val policy: (Intent, CommandSpec) -> Boolean = { i, s ->
            if (i == Intent.MCP_RUN) McpTools.needsConfirm(s.slot(Slot.TEXT).orEmpty()) else CommandRegistry.isDestructive(i)
        }

        val read = spec("mcp দিয়ে get_file_contents চালাও path=/sdcard/a.txt")
        assertEquals(Intent.MCP_RUN, read.intent)
        assertTrue(
            "a read-only tool executes without a card",
            CommandPipeline.decide(read, bengali = true, needsConfirm = policy) is CommandDecision.Execute,
        )

        val write = spec("mcp দিয়ে create_or_update_file চালাও path=/a.txt")
        val d = CommandPipeline.decide(write, bengali = true, needsConfirm = policy)
        assertTrue("a write tool must show the confirmation card", d is CommandDecision.Confirm)

        // The registry default is unchanged for every other intent.
        assertTrue(
            CommandPipeline.decide(
                CommandParser.offline("কাল ৩টায় ডাক্তারের অ্যাপয়েন্টমেন্ট ক্যালেন্ডারে যোগ করো"),
                bengali = true,
            ) is CommandDecision.Confirm,
        )
    }

    @Test fun theGithubPresetPointsAtTheOfficialEndpoint() {
        // The address is the documented remote MCP endpoint; the token slot is a named SecretStore key.
        val made = McpCore.serverFrom("github", "https://api.githubcopilot.com/mcp/", tokenName = "mcp_token_github")
        assertTrue(made != null)
        assertEquals("https://api.githubcopilot.com/mcp", made!!.url)
        assertEquals("mcp_token_github", made.tokenName)
        assertEquals("github", made.name)
        // Re-adding it does not create a duplicate row.
        assertEquals(1, McpCore.sanitize(listOf(made, made)).size)
    }

    @Test fun theServerNameFromTheHandshakeIsRead() {
        val reply = McpCore.parseRpc(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},
               "serverInfo":{"name":"github-mcp-server","version":"0.9.1"}}}""",
        )
        val (name, version) = McpCore.serverInfo(reply)
        assertEquals("github-mcp-server", name)
        assertEquals("0.9.1", version)
        assertEquals("" to "", McpCore.serverInfo(null))
        assertEquals("" to "", McpCore.serverInfo(McpCore.parseRpc("""{"jsonrpc":"2.0","id":1,"result":{}}""")))
    }
}

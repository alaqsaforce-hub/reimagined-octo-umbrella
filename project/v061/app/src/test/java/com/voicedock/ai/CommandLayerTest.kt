package com.voicedock.ai

import com.voicedock.ai.appc.AppEntry
import com.voicedock.ai.appc.AppNames
import com.voicedock.ai.command.Choice
import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandJson
import com.voicedock.ai.command.CommandLog
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.memory.MemoryEntry
import com.voicedock.ai.memory.MemoryStore
import com.voicedock.ai.memory.MemoryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** v0.7.0 — the whole "does it understand me, and does it refuse to guess" layer. */
class CommandLayerTest {

    // ------------------------------------------------------------------ JSON contract

    @Test
    fun `plain json parses`() {
        val spec = CommandJson.parse("""{"intent":"app.launch","slots":{"app":"WhatsApp"},"confidence":0.93}""")
        assertNotNull(spec)
        assertEquals(Intent.APP_LAUNCH, spec!!.intent)
        assertEquals("WhatsApp", spec.slot(Slot.APP))
        assertEquals(0.93, spec.confidence, 0.0001)
    }

    @Test
    fun `markdown fences and prose are tolerated`() {
        val raw = "Sure!\n```json\n{\"intent\": \"memory.store\", \"slots\": {\"text\": \"কাল ৩টায় মিটিং\"}, \"confidence\": 0.9}\n```\nHope that helps."
        val spec = CommandJson.parse(raw)
        assertEquals(Intent.MEMORY_STORE, spec?.intent)
        assertEquals("কাল ৩টায় মিটিং", spec?.slot(Slot.TEXT))
    }

    @Test
    fun `flat slots and snake case intents are tolerated`() {
        val spec = CommandJson.parse("""{"intent":"CALL_PLACE","contact":"মা","confidence":0.8}""")
        assertEquals(Intent.CALL_PLACE, spec?.intent)
        assertEquals("মা", spec?.slot(Slot.CONTACT))
    }

    @Test
    fun `unknown or missing intent is rejected instead of guessed`() {
        assertNull(CommandJson.parse("""{"intent":"launch_the_missiles","confidence":0.99}"""))
        assertNull(CommandJson.parse("""{"slots":{"app":"x"}}"""))
        assertNull(CommandJson.parse("no json here at all"))
        assertNull(CommandJson.parse("""{"intent":"app.launch"""))
    }

    @Test
    fun `numbered intent aliases resolve`() {
        assertEquals(Intent.APP_LAUNCH, Intent.from("app.launch"))
        assertEquals(Intent.APP_LAUNCH, Intent.from("APP_LAUNCH"))
        assertEquals(Intent.CALL_ANSWER, Intent.from("call-answer"))
        assertEquals(Intent.UNKNOWN, Intent.from("nonsense"))
        assertEquals(Intent.UNKNOWN, Intent.from(null))
    }

    // ------------------------------------------------------------------ offline patterns

    @Test
    fun `bangla open app command`() {
        val spec = CommandParser.offline("হোয়াটসঅ্যাপ খোলো")
        assertEquals(Intent.APP_LAUNCH, spec.intent)
        assertTrue(spec.slot(Slot.APP)!!.contains("হোয়াটসঅ্যাপ"))
        assertTrue(CommandParser.confident(spec))
    }

    @Test
    fun `english open app command keeps the app name only`() {
        val spec = CommandParser.offline("open YouTube app please")
        assertEquals(Intent.APP_LAUNCH, spec.intent)
        assertEquals("YouTube", spec.slot(Slot.APP))
    }

    @Test
    fun `remember strips the trigger phrase`() {
        val spec = CommandParser.offline("মনে রাখো কাল ৩টায় মিটিং")
        assertEquals(Intent.MEMORY_STORE, spec.intent)
        assertEquals("কাল ৩টায় মিটিং", spec.slot(Slot.TEXT))
    }

    @Test
    fun `recall is not confused with remember`() {
        val spec = CommandParser.offline("আমি কাল কী মনে রাখতে বলেছিলাম?")
        assertEquals(Intent.MEMORY_RECALL, spec.intent)
        assertEquals(Intent.MEMORY_RECALL, CommandParser.offline("what did you remember").intent)
    }

    @Test
    fun `call and decline are recognised even though v0_7 cannot run them`() {
        val call = CommandParser.offline("মাকে ফোন কর")
        assertEquals(Intent.CALL_PLACE, call.intent)
        assertTrue(call.slot(Slot.CONTACT)!!.contains("মাকে"))
        assertEquals(Intent.CALL_DECLINE, CommandParser.offline("কেটে দাও").intent)
        assertEquals(Intent.CALL_ANSWER, CommandParser.offline("রিং হচ্ছে ধরো").intent)
        assertFalse(CommandRegistry.isAvailable(call.intent))
    }

    @Test
    fun `nonsense stays unknown`() {
        val spec = CommandParser.offline("উফ কি গরম আজ")
        assertEquals(Intent.UNKNOWN, spec.intent)
        assertFalse(CommandParser.confident(spec))
    }

    @Test
    fun `normalize fixes digits and punctuation`() {
        assertEquals("কাল 3 টায়", CommandParser.normalize("কাল ৩ টায়।"))
        assertEquals("hello world", CommandParser.normalize("  hello,   world!  "))
    }

    // ------------------------------------------------------------------ registry + decision

    @Test
    fun `missing slot is reported once`() {
        val spec = CommandSpec.of(Intent.APP_LAUNCH, "খোলো", 0.9)
        assertEquals(listOf(Slot.APP), CommandRegistry.missing(spec))
        assertEquals(listOf<Slot>(), CommandRegistry.missing(spec.copy(slots = mapOf(Slot.APP to "WhatsApp"))))
    }

    @Test
    fun `unknown sentence asks instead of acting`() {
        val d = CommandPipeline.decide(CommandSpec.unknown("উফ কি গরম"), bengali = false)
        assertTrue(d is CommandDecision.Clarify)
        val clarify = d as CommandDecision.Clarify
        assertTrue(clarify.choices.size in 2..4)
        assertTrue(clarify.choices.any { it.intent == Intent.APP_LAUNCH })
    }

    @Test
    fun `low confidence is treated as not understood`() {
        val d = CommandPipeline.decide(CommandSpec.of(Intent.APP_LAUNCH, "x", 0.5, Slot.APP to "WhatsApp"), bengali = true)
        assertTrue(d is CommandDecision.Clarify)
    }

    @Test
    fun `a later-phase command explains which version will do it`() {
        val d = CommandPipeline.decide(CommandSpec.of(Intent.CALL_PLACE, "call mom", 0.95, Slot.CONTACT to "মা"), bengali = false)
        assertTrue(d is CommandDecision.Clarify)
        val c = d as CommandDecision.Clarify
        assertTrue(c.question.contains("v0.9"))
        assertTrue(c.choices.any { it.intent == Intent.ASK_AI })
        assertTrue(c.choices.any { it.intent == Intent.UNKNOWN })
    }

    @Test
    fun `missing slot offers the candidates the phone knows`() {
        // No APP slot at all: only the phone can offer candidates.
        val spec = CommandSpec.of(Intent.APP_LAUNCH, "খোলো", 0.9)
        val d = CommandPipeline.decide(spec, bengali = true, candidates = mapOf(Slot.APP to listOf("WhatsApp", "WhatsApp Business")))
        assertTrue(d is CommandDecision.Clarify)
        val c = d as CommandDecision.Clarify
        assertEquals(listOf("WhatsApp", "WhatsApp Business"), c.choices.map { it.label })
        assertEquals("WhatsApp", c.choices.first().slots[Slot.APP])
    }

    @Test
    fun `destructive commands always confirm`() {
        val d = CommandPipeline.decide(
            CommandSpec.of(Intent.MSG_REPLY, "reply", 0.99, Slot.CONTACT to "রহিম", Slot.TEXT to "পরে ফোন করব"),
            bengali = false,
        )
        // MSG_REPLY is still unavailable in v0.7, so the unavailable branch wins first.
        assertTrue(d is CommandDecision.Clarify)
        assertTrue(CommandRegistry.isDestructive(Intent.MSG_REPLY))
        assertTrue(CommandRegistry.isDestructive(Intent.CALL_DECLINE))
        assertFalse(CommandRegistry.isDestructive(Intent.APP_LAUNCH))
    }

    @Test
    fun `a complete safe command executes`() {
        val d = CommandPipeline.decide(CommandSpec.of(Intent.APP_LAUNCH, "open whatsapp", 0.95, Slot.APP to "WhatsApp"), bengali = false)
        assertEquals(CommandDecision.Execute, d)
        assertEquals(
            CommandDecision.Execute,
            CommandPipeline.decide(CommandSpec.of(Intent.MEMORY_STORE, "remember x", 0.95, Slot.TEXT to "x"), bengali = true),
        )
    }

    // ------------------------------------------------------------------ app name matching

    private val apps = listOf(
        AppEntry("com.whatsapp", "WhatsApp", listOf("com.whatsapp")),
        AppEntry("com.whatsapp.w4b", "WhatsApp Business", listOf("com.whatsapp.w4b")),
        AppEntry("com.google.android.youtube", "YouTube", listOf("com.google.android.youtube")),
        AppEntry("com.facebook.katana", "Facebook", listOf("com.facebook.katana")),
        AppEntry("com.spotify.music", "Spotify", listOf("com.spotify.music")),
    )

    @Test
    fun `bangla alias finds whatsapp`() {
        val hits = AppNames.match("হোয়াটসঅ্যাপ", apps)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().pkg.startsWith("com.whatsapp"))
    }

    @Test
    fun `latin query matches label prefix and ranks exact first`() {
        assertEquals("WhatsApp", AppNames.match("WhatsApp", apps).first().label)
        assertTrue(AppNames.match("whats", apps).map { it.label }.contains("WhatsApp"))
        assertTrue(AppNames.match("youtube", apps).first().pkg.contains("youtube"))
    }

    @Test
    fun `no match returns empty so the caller must ask`() {
        assertTrue(AppNames.match("xyzzy", apps).isEmpty())
        assertTrue(AppNames.match("", apps).isEmpty())
    }

    @Test
    fun `sloppy speech spelling still matches`() {
        assertTrue(AppNames.match("whats app", apps).isNotEmpty())
        assertTrue(AppNames.match("watsapp", apps).isNotEmpty())
    }

    // ------------------------------------------------------------------ memory

    @Test
    fun `memory search ranks shared words and ignores filler`() {
        val now = 1_000L
        val entries = listOf(
            MemoryEntry("1", "কাল ৩টায় ডাক্তারের মিটিং", now),
            MemoryEntry("2", "বাজারে দুধ আনতে হবে", now - 10),
        )
        val found = MemoryText.search(entries, "মিটিং কখন")
        assertEquals(1, found.size)
        assertTrue(found.first().text.contains("মিটিং"))

        // Pure filler query returns the newest entries rather than nothing.
        assertEquals(2, MemoryText.search(entries, "আমি কী মনে রাখতে বলেছিলাম").size)
    }

    @Test
    fun `recall wording never invents content`() {
        val empty = MemoryText.recallAnswer(emptyList(), 0, bengali = false)
        assertTrue(empty.contains("Nothing"))
        val miss = MemoryText.recallAnswer(emptyList(), 3, bengali = false)
        assertTrue(miss.contains("3"))
    }

    @Test
    fun `memory store round trips and forgets`() {
        val dir = Files.createTempDirectory("vdmem").toFile()
        val store = MemoryStore(dir)
        assertTrue(store.all().isEmpty())
        val a = store.add("কাল ৩টায় মিটিং")
        val b = store.add("দুধ আনতে হবে")
        assertNotNull(a); assertNotNull(b)
        assertEquals(2, MemoryStore(dir).all().size) // survives a new instance = written to disk
        assertEquals("দুধ আনতে হবে", store.all().first().text) // newest first
        assertTrue(store.forget(a!!.id))
        assertEquals(1, store.all().size)
        assertFalse(store.forget("does-not-exist"))
        store.clear()
        assertTrue(store.all().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `blank memory is not stored`() {
        val dir = Files.createTempDirectory("vdmem2").toFile()
        assertNull(MemoryStore(dir).add("   "))
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ command log

    @Test
    fun `command log keeps newest first and is capped`() {
        val dir = Files.createTempDirectory("vdlog").toFile()
        val log = CommandLog(dir)
        log.record("খোলো", Intent.APP_LAUNCH, 0.9, "offline", "launcher", true, "WhatsApp opened")
        log.record("গরম", Intent.UNKNOWN, 0.0, "offline", "clarify", true, "asked")
        assertEquals(2, log.all().size)
        assertEquals(Intent.UNKNOWN.id, log.all().first().intent) // newest first
        for (i in 0 until CommandLog.MAX + 10) log.record("c$i", Intent.APP_LAUNCH, 0.9, "offline", "launcher", true)
        assertEquals(CommandLog.MAX, log.all().size)
        log.clear()
        assertTrue(log.all().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `describe is human readable for both languages`() {
        val spec = CommandSpec.of(Intent.APP_LAUNCH, "x", 1.0, Slot.APP to "WhatsApp")
        assertTrue(CommandPipeline.describe(spec, bengali = false).contains("WhatsApp"))
        assertTrue(CommandPipeline.describe(spec, bengali = true).contains("WhatsApp"))
    }

    @Test
    fun `choice defaults are empty slots`() {
        val c = Choice("বাতিল", Intent.UNKNOWN)
        assertTrue(c.slots.isEmpty())
    }
}

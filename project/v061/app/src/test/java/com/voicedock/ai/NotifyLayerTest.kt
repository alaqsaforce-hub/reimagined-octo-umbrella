package com.voicedock.ai

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.notify.Digest
import com.voicedock.ai.notify.NotifEntry
import com.voicedock.ai.notify.NotifStore
import com.voicedock.ai.notify.SenderMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * v0.8.0 — the notification layer's rules: group by sender, never invent hidden text, resolve a name
 * conservatively, and parse reply sentences without stealing them from the call parser.
 */
class NotifyLayerTest {

    private val dayStart = 1_000_000L

    private fun entry(
        sender: String,
        text: String,
        at: Long,
        key: String = "$sender-$at",
        pkg: String = "com.whatsapp",
        canReply: Boolean = true,
    ) = NotifEntry(key, pkg, "WhatsApp", sender, text, at, canReply)

    private val entries = listOf(
        entry("রহিম", "কাল দেখা হবে", dayStart + 300),
        entry("রহিম", "আমি বাজারে আছি", dayStart + 100),
        entry("Karim", "sent a photo", dayStart + 200),
        entry("রহিম", "গতকালের মেসেজ", dayStart - 500), // yesterday — must not count
    )

    // ---------------------------------------------------------------- digest

    @Test
    fun `groups today per sender with counts, newest first`() {
        val groups = Digest.group(entries, dayStart)
        assertEquals(2, groups.size)
        assertEquals("রহিম", groups[0].sender)
        assertEquals(2, groups[0].count)
        assertEquals("কাল দেখা হবে", groups[0].lastText)
        assertEquals("Karim", groups[1].sender)
    }

    @Test
    fun `yesterday is excluded`() {
        val today = Digest.group(entries, dayStart)
        assertEquals(2, today.size)
        assertEquals(2, today.first { it.sender == "রহিম" }.count) // yesterday's line not counted
        val sinceYesterday = Digest.group(entries, dayStart - 1000)
        assertEquals(3, sinceYesterday.first { it.sender == "রহিম" }.count)
    }

    @Test
    fun `summary names people and never invents hidden text`() {
        val hidden = listOf(entry("Sumi", "", dayStart + 50, canReply = true))
        val bn = Digest.summary(hidden, dayStart, bengali = true)
        assertTrue(bn.contains("Sumi"))
        assertTrue(bn.contains("preview বন্ধ"))
        assertFalse(bn.contains("কাল দেখা হবে"))

        val en = Digest.summary(hidden, dayStart, bengali = false)
        assertTrue(en.contains("hidden"))
    }

    @Test
    fun `summary states plainly when nothing arrived`() {
        val empty = Digest.summary(emptyList(), dayStart, bengali = false)
        assertTrue(empty.contains("No messages today"))
        val bn = Digest.summary(emptyList(), dayStart, bengali = true)
        assertTrue(bn.contains("কোনো মেসেজ আসেনি"))
    }

    @Test
    fun `summary caps the number of senders and says how many are left`() {
        val many = (1..9).map { entry("P$it", "hi $it", dayStart + it) }
        val text = Digest.summary(many, dayStart, bengali = false, maxSenders = 6)
        assertTrue(text.contains("P9"))
        assertTrue(text.contains("and 3 more"))
    }

    @Test
    fun `read aloud returns newest five with readable text only`() {
        val list = (1..8).map { entry("S$it", "m$it", dayStart + it) } + entry("Hidden", "", dayStart + 99)
        val spoken = Digest.readAloud(list, 5, bengali = false)
        assertTrue(spoken.contains("m8"))
        assertTrue(spoken.contains("m4"))
        assertFalse(spoken.contains("m3"))
        assertFalse(spoken.contains("Hidden"))
        assertEquals(5, Digest.lastMessages(list, 5).size)
    }

    @Test
    fun `nothing to read is said honestly`() {
        assertTrue(Digest.readAloud(emptyList(), 5, bengali = false).contains("nothing to read"))
        assertTrue(Digest.readAloud(emptyList(), 5, bengali = true).contains("পড়ার মতো মেসেজ নেই"))
    }

    @Test
    fun `ai prompt carries only what is known`() {
        val prompt = Digest.aiPrompt(Digest.group(entries, dayStart), bengali = false)
        assertTrue(prompt.contains("রহিম"))
        assertTrue(prompt.contains("Do not invent"))
    }

    // ---------------------------------------------------------------- sender matching

    @Test
    fun `bangla object marker is stripped and names still match`() {
        assertEquals("রহিম", SenderMatch.cleanName("রহিমকে"))
        assertTrue(SenderMatch.resolve("রহিমকে", listOf("রহিম", "Karim")).contains("রহিম"))
        assertFalse(SenderMatch.resolve("রহিমকে", listOf("Karim")).contains("Karim"))
    }

    @Test
    fun `partial latin names match, unknown names return nothing`() {
        assertTrue(SenderMatch.resolve("kar", listOf("Karim", "Rahim")).contains("Karim"))
        assertTrue(SenderMatch.resolve("nobody-here", listOf("Karim", "Rahim")).isEmpty())
        assertTrue(SenderMatch.resolve("", listOf("Karim")).isEmpty())
    }

    @Test
    fun `two similar senders both come back so the caller must ask`() {
        val hits = SenderMatch.resolve("রহিম", listOf("রহিম উদ্দিন", "রহিমা"))
        assertEquals(2, hits.size)
    }

    // ---------------------------------------------------------------- store

    @Test
    fun `store upserts by key, caps, and filters by day`() {
        val dir = Files.createTempDirectory("vdnotif").toFile()
        val store = NotifStore(dir)
        store.upsert(entry("রহিম", "এক", dayStart + 10, key = "k1"))
        store.upsert(entry("রহিম", "দুই", dayStart + 20, key = "k1")) // same key = edit
        assertEquals(1, store.all().size)
        assertEquals("দুই", store.all().first().text)

        store.upsert(entry("Karim", "old", dayStart - 5000, key = "k2"))
        assertEquals(1, store.today(dayStart).size)
        assertEquals(listOf("রহিম"), store.sendersSince(dayStart))

        for (i in 0 until NotifStore.MAX + 20) store.upsert(entry("S", "m$i", dayStart + 100 + i, key = "x$i"))
        assertEquals(NotifStore.MAX, store.all().size)

        // A fresh instance sees the same file.
        assertEquals(NotifStore.MAX, NotifStore(dir).all().size)
        store.clear()
        assertTrue(store.all().isEmpty())
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun `reply sentence is parsed as a reply, not as a call`() {
        val spec = CommandParser.offline("রহিমকে বলো আমি পরে ফোন করব")
        assertEquals(Intent.MSG_REPLY, spec.intent)
        assertEquals("রহিম", spec.slot(Slot.CONTACT))
        assertEquals("আমি পরে ফোন করব", spec.slot(Slot.TEXT))
    }

    @Test
    fun `english reply sentence works too`() {
        val spec = CommandParser.offline("tell Karim I will call later")
        assertEquals("parsed=$spec", Intent.MSG_REPLY, spec.intent)
        assertEquals("Karim", spec.slot(Slot.CONTACT))
        assertTrue(spec.slot(Slot.TEXT)!!.contains("call later"))
    }

    @Test
    fun `digest and read commands parse`() {
        assertEquals(Intent.MSG_DIGEST, CommandParser.offline("আজ কে কে মেসেজ দিয়েছে?").intent)
        assertEquals(Intent.MSG_DIGEST, CommandParser.offline("summarise my messages").intent)
        assertEquals(Intent.MSG_READ, CommandParser.offline("শেষ ৫টা মেসেজ পড়ো").intent)
    }

    @Test
    fun `a plain phone call is still a call`() {
        val spec = CommandParser.offline("মাকে ফোন কর")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        assertEquals(Intent.CALL_ANSWER, CommandParser.offline("রিং হচ্ছে ধরো").intent)
    }

    @Test
    fun `v0_8 intents are now available and reply is destructive`() {
        assertTrue(com.voicedock.ai.command.CommandRegistry.isAvailable(Intent.MSG_DIGEST))
        assertTrue(com.voicedock.ai.command.CommandRegistry.isAvailable(Intent.MSG_READ))
        assertTrue(com.voicedock.ai.command.CommandRegistry.isAvailable(Intent.MSG_REPLY))
        assertTrue(com.voicedock.ai.command.CommandRegistry.isDestructive(Intent.MSG_REPLY))
        assertFalse(com.voicedock.ai.command.CommandRegistry.isAvailable(Intent.CALL_PLACE))
    }

    @Test
    fun `reply confirmation shows the recipient and the text`() {
        val spec = com.voicedock.ai.command.CommandSpec.of(
            Intent.MSG_REPLY, "x", 0.95, Slot.CONTACT to "রহিম", Slot.TEXT to "পরে ফোন করব",
        )
        val d = com.voicedock.ai.command.CommandPipeline.decide(spec, bengali = false)
        assertTrue(d is com.voicedock.ai.command.CommandDecision.Confirm)
        val q = (d as com.voicedock.ai.command.CommandDecision.Confirm).question
        assertTrue(q.contains("রহিম"))
        assertTrue(q.contains("পরে ফোন করব"))
    }
}

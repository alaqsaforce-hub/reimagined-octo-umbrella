package com.voicedock.ai.v132

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.notify.ChatRollup
import com.voicedock.ai.notify.NotifEntry
import com.voicedock.ai.summaries.YtSummaryCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.2 — content summaries. The rule under test is the one this project has held since v0.8:
 * **never invent message or video content.** Both cores must say what the phone really has, and say plainly
 * what it does not have.
 */
class ChatRollupTest {

    private val now = 1_000_000L
    private fun msg(sender: String, text: String, at: Long = now, reply: Boolean = false, pkg: String = "com.whatsapp") =
        NotifEntry(key = "$sender-$at-$text", pkg = pkg, appLabel = "WhatsApp", sender = sender, text = text, at = at, canReply = reply)

    private val log = listOf(
        msg("রহিম", "কাল দেখা করব", at = 100),
        msg("রহিম", "১২টায় আসব", at = 200, reply = true),
        msg("রহিম", "", at = 300),                       // preview hidden by the app
        msg("করিম", "হিসাবটা পাঠাও", at = 150),
        msg("VoiceDock", "চলছে", at = 400, pkg = "com.voicedock.ai"),
    )

    @Test fun oneChatIsRolledUpNewestLastAndCounted() {
        val t = ChatRollup.find(log, 0, "রহিম")!!
        assertEquals(3, t.count)
        assertEquals(listOf("কাল দেখা করব", "১২টায় আসব"), t.messages)
        assertTrue("a hidden preview must be remembered as hidden", t.hiddenPreviews)
        assertTrue(t.canReply)
    }

    @Test fun theAnswerAdmitsHiddenPreviewsInsteadOfFillingThem() {
        val text = ChatRollup.text(ChatRollup.find(log, 0, "রহিম")!!, bengali = true)
        assertTrue(text, text.contains("রহিম"))
        assertTrue(text, text.contains("কাল দেখা করব"))
        assertTrue("must not invent the missing message", !text.contains("১২টায় আসব না"))
        assertTrue("must say some notifications had no text", text.contains("লেখা ছিল না"))
    }

    @Test fun aThreadWithNoReadableTextSaysSoAndSummarisesNothing() {
        val hiddenOnly = listOf(msg("সুমি", "", at = 10), msg("সুমি", "", at = 20))
        val text = ChatRollup.text(ChatRollup.find(hiddenOnly, 0, "সুমি")!!, bengali = true)
        assertTrue(text, text.contains("লেখা দেখা যাচ্ছে না"))
        assertTrue(text, text.contains("সারমর্ম বানানো হয়নি"))
    }

    @Test fun namesMatchTheWayPeopleSayThem() {
        assertEquals("রহিম", ChatRollup.find(log, 0, "রহিম")?.sender)
        assertEquals("রহিম", ChatRollup.find(log, 0, " রহিম ")?.sender)
        assertEquals("রহিম", ChatRollup.find(log, 0, "রহি")?.sender)   // substring, like the contact matcher
        assertNull(ChatRollup.find(log, 0, "কেউ নেই"))
        assertNull(ChatRollup.find(log, 0, ""))
    }

    @Test fun onlyTodaysMessagesCount() {
        // dayStart above every entry → nothing to summarise, and the honest empty line is different.
        assertNull(ChatRollup.find(log, 500, "রহিম"))
        assertTrue(ChatRollup.text(ChatRollup.threads(log, 500).firstOrNull() ?: ChatRollup.threads(log, 0).first(), true).isNotBlank())
        assertTrue(ChatRollup.empty(true).contains("নোটিফিকেশনে"))
    }

    @Test fun theAiPromptForbidsInventingMissingText() {
        val t = ChatRollup.find(log, 0, "রহিম")!!
        val p = ChatRollup.aiPrompt(t, bengali = true)
        assertTrue(p, p.contains("বানিয়ে বলো না"))
        assertTrue(ChatRollup.aiPrompt(t, bengali = false).contains("Never invent"))
        assertEquals("- কাল দেখা করব\n- ১২টায় আসব", ChatRollup.aiMessages(t))
    }
}

class YtSummaryTest {

    private val page = """
        <html><head>
        <meta property="og:title" content="কীভাবে দ্রুত বাংলা টাইপ করবেন">
        <meta property="og:description" content="এই ভিডিওতে বাংলা টাইপিং শেখানো হয়েছে।">
        <meta property="og:site_name" content="YouTube">
        <meta itemprop="duration" content="PT12M3S">
        </head><body>""".trimIndent()

    @Test fun everyShapeOfYoutubeLinkIsUnderstood() {
        assertEquals("dQw4w9WgXcQ", YtSummaryCore.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YtSummaryCore.videoId("https://youtu.be/dQw4w9WgXcQ?t=5"))
        assertEquals("dQw4w9WgXcQ", YtSummaryCore.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YtSummaryCore.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YtSummaryCore.videoId("dQw4w9WgXcQ"))
        assertNull(YtSummaryCore.videoId("ইউটিউবে গান খোঁজো"))
        assertNull(YtSummaryCore.videoId(""))
    }

    @Test fun metadataIsReadFromThePageAndNothingElseIsClaimed() {
        val info = YtSummaryCore.parse(page, "dQw4w9WgXcQ")!!
        assertEquals("কীভাবে দ্রুত বাংলা টাইপ করবেন", info.title)
        assertEquals("এই ভিডিওতে বাংলা টাইপিং শেখানো হয়েছে।", info.description)
        assertEquals(723, info.durationSec)
        assertEquals("12:03", YtSummaryCore.durationLabel(info.durationSec))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.url)
    }

    @Test fun theAnswerAlwaysDeclaresThatCaptionsWereNotRead() {
        val text = YtSummaryCore.text(YtSummaryCore.parse(page, "abc12345678")!!, bengali = true)
        assertTrue(text, text.contains("ক্যাপশন (সাবটাইটেল) পড়া যায়নি"))
        assertTrue(text, text.contains("▶"))
        assertTrue(YtSummaryCore.text(YtSummaryCore.parse(page, "abc12345678")!!, false).contains("Captions could not be read"))
    }

    @Test fun aConsentWallOrAnEmptyPageIsAFailureNotAGuess() {
        assertNull(YtSummaryCore.parse("", "x"))
        assertNull(YtSummaryCore.parse("<html><title>Before you continue to YouTube</title></html>", "x"))
        assertTrue(YtSummaryCore.failure("consent", true).contains("সম্মতি"))
        assertTrue(YtSummaryCore.failure("http", false).contains("network"))
        assertTrue(YtSummaryCore.failure("no_link", true).contains("লিংক"))
    }

    @Test fun durationsComeInBothShapes() {
        assertEquals(723, YtSummaryCore.durationSeconds("PT12M3S"))
        assertEquals(3723, YtSummaryCore.durationSeconds("PT1H2M3S"))
        assertEquals(45, YtSummaryCore.durationSeconds("45"))
        assertNull(YtSummaryCore.durationSeconds(""))
        assertNull(YtSummaryCore.durationSeconds("PT"))
    }

    @Test fun jsonFallbackWorksWhenOpenGraphIsMissing() {
        val json = """<script>var ytInitialPlayerResponse = {"videoDetails":{"title":"Fallback title",""" +
            """"author":"Some Channel","lengthSeconds":"95","shortDescription":"A description"}};</script>"""
        val info = YtSummaryCore.parse(json, "dQw4w9WgXcQ")!!
        assertEquals("Fallback title", info.title)
        assertEquals("Some Channel", info.channel)
        assertEquals(95, info.durationSec)
        assertEquals("A description", info.description)
    }

    @Test fun onlyLinkSummariesAreTreatedAsLinks() {
        assertTrue(YtSummaryCore.looksLikeLink("https://youtu.be/dQw4w9WgXcQ সারমর্ম বলো"))
        assertTrue(YtSummaryCore.isSummaryRequest("এই লিংকের সারমর্ম কী"))
        // A plain YouTube search must stay a search.
        assertFalse(YtSummaryCore.looksLikeLink("ইউটিউবে রবীন্দ্রসংগীত খোঁজো"))
    }
}

class SummaryCommandParsingTest {

    @Test fun aChatSummaryCarriesThePersonNotTheSentence() {
        val spec = CommandParser.offline("রহিমের মেসেজের সারমর্ম বলো")
        assertEquals(Intent.MSG_THREAD, spec.intent)
        assertEquals("রহিম", spec.slot(Slot.CONTACT))
    }

    @Test fun aYoutubeLinkBecomesASummariesRequestNotASearch() {
        val spec = CommandParser.offline("https://youtu.be/dQw4w9WgXcQ এর সারমর্ম বলো")
        assertEquals(Intent.YT_SUMMARY, spec.intent)
        assertTrue(spec.slot(Slot.QUERY).orEmpty().contains("dQw4w9WgXcQ"))
    }

    @Test fun theOlderRulesSurvive() {
        assertEquals(Intent.YT_SEARCH, CommandParser.offline("ইউটিউবে রবীন্দ্রসংগীত খোঁজো").intent)
        assertEquals(Intent.MSG_DIGEST, CommandParser.offline("আজকের মেসেজের সারমর্ম").intent)
        assertEquals(Intent.MSG_READ, CommandParser.offline("শেষ মেসেজগুলো পড়ো").intent)
    }

    @Test fun theNewIntentsAreRegisteredAndHarmless() {
        listOf(Intent.MSG_THREAD, Intent.YT_SUMMARY).forEach {
            assertTrue("$it registered", CommandRegistry.entries().contains(it))
            assertTrue("$it available", CommandRegistry.isAvailable(it))
            assertFalse("$it must not confirm", CommandRegistry.isDestructive(it))
            assertTrue("$it help", CommandRegistry.help(it, false).isNotBlank())
        }
    }
}

package com.voicedock.ai.v133

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.send.SendCore
import com.voicedock.ai.send.SendPlan
import com.voicedock.ai.send.SendResult
import com.voicedock.ai.send.SendScreen
import com.voicedock.ai.summaries.ScreenTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.3 — the accessibility pass. Two promises are under test:
 *
 *  1. a message is only ever sent through accessibility when the screen *proves* it is a chat with a send
 *     button, and "sent" is claimed only when the composer really emptied;
 *  2. the transcript reader works from the panel the user opened, and says how to open it when it is not there.
 */
class SendCoreTest {

    private fun chat(pkg: String = "com.whatsapp", field: Boolean = true, labels: List<String> = listOf("Send")) =
        SendScreen(pkg = pkg, appLabel = "WhatsApp", hasMessageField = field, sendLabels = labels)

    @Test fun aMessageBoxIsNotASearchBox() {
        assertTrue(SendCore.looksLikeMessageField("Type a message", "com.whatsapp:id/entry"))
        assertTrue(SendCore.looksLikeMessageField("বার্তা লিখুন", null))
        assertFalse("a search box must never receive a message", SendCore.looksLikeMessageField("Search", "id/search_bar"))
        assertFalse(SendCore.looksLikeMessageField("", null))
    }

    @Test fun sendButtonsAreRecognisedInTheLanguagesAppsShip() {
        for (label in listOf("Send", "পাঠান", "Enviar", "Senden", "Envoyer", "送信", "Отправить", "إرسال")) {
            assertTrue("‘$label’ must be a send button", SendCore.isSendLabel(label))
        }
        // Not every button is a send button.
        assertFalse(SendCore.isSendLabel("Send location"))
        assertFalse(SendCore.isSendLabel("Search"))
        assertFalse(SendCore.isSendLabel(null))
        assertFalse(SendCore.isSendLabel("A very long label that is clearly not a send button"))
    }

    @Test fun thePlanRefusesUnlessTheScreenProvesItself() {
        assertTrue(SendCore.plan(chat(), "com.whatsapp") is SendPlan.Proceed)
        // Wrong app in front: refuse, never type in whatever is open.
        assertEquals(SendResult.WRONG_APP, (SendCore.plan(chat(pkg = "com.facebook.orca"), "com.whatsapp") as SendPlan.Refuse).result)
        // No message box.
        assertEquals(SendResult.NO_FIELD, (SendCore.plan(chat(field = false), "com.whatsapp") as SendPlan.Refuse).result)
        // A field but no send button: do not type a message the user then cannot send.
        assertEquals(SendResult.NO_SEND_BUTTON, (SendCore.plan(chat(labels = emptyList()), null) as SendPlan.Refuse).result)
        // Unknown target package still needs both proofs.
        assertTrue(SendCore.plan(chat(), null) is SendPlan.Proceed)
        assertEquals(SendResult.NO_FIELD, (SendCore.plan(SendScreen(pkg = ""), null) as SendPlan.Refuse).result)
    }

    @Test fun everyOutcomeHasWordsInBothLanguages() {
        for (r in SendResult.entries) {
            assertTrue(r.name, r.bn.isNotBlank() && r.en.isNotBlank())
        }
        assertTrue(SendCore.resultText(SendResult.SENT, true, "রহিম").contains("রহিম"))
        assertTrue(SendCore.resultText(SendResult.NO_SEND_BUTTON, false).contains("send it yourself"))
        assertTrue(SendCore.helpText(true).contains("চ্যাটটা খুলে রাখুন"))
        assertTrue(SendCore.planNote(false, "WhatsApp").contains("Send"))
    }
}

class ScreenTranscriptTest {

    private val panel = """
        Show transcript
        Auto-scroll
        0:03
        আজ আমরা কথা বলব বাংলা টাইপিং নিয়ে।
        0:09
        প্রথমে কিবোর্ড ইনস্টল করতে হবে।
        0:15
        তারপর অভ্যাস করতে হবে প্রতিদিন।
        0:21
        ধন্যবাদ দেখার জন্য বন্ধুরা।
        Subscribe
        1:02:33
    """.trimIndent()

    @Test fun thePanelIsRecognisedAndCleaned() {
        assertTrue(ScreenTranscript.looksLikeTranscript(panel))
        val t = ScreenTranscript.clean(panel)
        assertTrue(t.confident)
        assertEquals(
            listOf(
                "আজ আমরা কথা বলব বাংলা টাইপিং নিয়ে।",
                "প্রথমে কিবোর্ড ইনস্টল করতে হবে।",
                "তারপর অভ্যাস করতে হবে প্রতিদিন।",
                "ধন্যবাদ দেখার জন্য বন্ধুরা।",
            ),
            t.lines,
        )
    }

    @Test fun chromeAndDuplicatesAreDropped() {
        val messy = "Transcript\n$panel\nআজ আমরা কথা বলব বাংলা টাইপিং নিয়ে।\n12:34\nHome\nLike"
        val t = ScreenTranscript.clean(messy)
        assertEquals(1, t.lines.count { it.contains("বাংলা টাইপিং") })
        assertFalse(t.lines.any { it == "Home" || it == "Like" || it.contains("Subscribe") })
    }

    @Test fun anOrdinaryScreenIsNotATranscript() {
        assertFalse(ScreenTranscript.looksLikeTranscript("হোয়াটসঅ্যাপ\nরহিম\nকাল দেখা করব"))
        val t = ScreenTranscript.clean("হোয়াটসঅ্যাপ\nরহিম\nকাল দেখা করব")
        assertFalse(t.confident)
        assertTrue(t.lines.isNotEmpty())
    }

    @Test fun withoutThePanelTheAnswerIsInstructionsNotASummary() {
        val bn = ScreenTranscript.missingPanel(true)
        assertTrue(bn.contains("Show transcript"))
        assertTrue(ScreenTranscript.missingPanel(false).contains("transcript"))
        assertTrue(ScreenTranscript.emptyTranscript(true).contains("ক্যাপশন"))
    }

    @Test fun theAnswerSaysWhereTheCaptionsCameFrom() {
        val t = ScreenTranscript.clean(panel)
        val withAi = ScreenTranscript.text(t, true, "ভিডিওটা বাংলা টাইপিং শেখায়।")
        assertTrue(withAi.contains("স্ক্রিনের ট্রান্সক্রিপ্ট"))
        assertTrue(withAi.contains("বাংলা টাইপিং শেখায়।"))
        // Without a model the lines themselves are shown — never a fabricated summary.
        val raw = ScreenTranscript.text(t, false, null)
        assertTrue(raw.contains("on-screen transcript"))
        assertTrue(raw.contains("প্রথমে কিবোর্ড ইনস্টল করতে হবে।"))
        assertTrue(ScreenTranscript.aiPrompt(true).contains("বানিয়ে বলো না"))
    }

    @Test fun aLongTranscriptIsCappedNotTruncatedSilently() {
        val long = (1..200).joinToString("\n") { "এটি লাইন নম্বর $it যা অনেক লম্বা ট্রান্সক্রিপ্টের অংশ" }
        val t = ScreenTranscript.clean(long)
        assertTrue(t.lines.size <= ScreenTranscript.MAX_LINES)
        assertTrue(t.lines.sumOf { it.length } <= ScreenTranscript.MAX_CHARS + 200)
    }
}

class TranscriptCommandParsingTest {

    @Test fun theTranscriptRequestIsItsOwnIntent() {
        assertEquals(Intent.YT_TRANSCRIPT, CommandParser.offline("ট্রান্সক্রিপ্ট পড়ে সারমর্ম দাও").intent)
        assertEquals(Intent.YT_TRANSCRIPT, CommandParser.offline("স্ক্রিনের ভিডিওর সারমর্ম বলো").intent)
        assertEquals(Intent.YT_TRANSCRIPT, CommandParser.offline("summarise the captions").intent)
    }

    @Test fun aLinkStillGoesToTheLinkRuleAndASearchStaysASearch() {
        assertEquals(Intent.YT_SUMMARY, CommandParser.offline("https://youtu.be/dQw4w9WgXcQ এর সারমর্ম বলো").intent)
        assertEquals(Intent.YT_SEARCH, CommandParser.offline("ইউটিউবে রবীন্দ্রসংগীত খোঁজো").intent)
        assertEquals(Intent.MSG_DIGEST, CommandParser.offline("আজকের মেসেজের সারমর্ম").intent)
    }

    @Test fun theIntentIsRegisteredAndHarmless() {
        assertTrue(CommandRegistry.entries().contains(Intent.YT_TRANSCRIPT))
        assertTrue(CommandRegistry.isAvailable(Intent.YT_TRANSCRIPT))
        assertFalse(CommandRegistry.isDestructive(Intent.YT_TRANSCRIPT))
        assertTrue(CommandRegistry.help(Intent.YT_TRANSCRIPT, true).isNotBlank())
    }
}

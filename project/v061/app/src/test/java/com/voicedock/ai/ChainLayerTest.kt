package com.voicedock.ai

import com.voicedock.ai.command.ChainOutcome
import com.voicedock.ai.command.ChainResolver
import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.0 — command chains: "শেষ যে মেসেজ দিয়েছে তাকে ফোন করো".
 *
 * A chain is two steps the assistant already had, joined: read the newest sender from the notification
 * log, then run the ordinary call/reply command with that real name in the contact slot. No new intent,
 * no guessing — and the confirmation card still shows exactly who will be called.
 */
class ChainLayerTest {

    private val senders = listOf("রহিম", "Karim", "Sultana")

    @Test fun recognizesBanglaAndEnglishPronouns() {
        assertTrue(ChainResolver.isPronoun("শেষ যে মেসেজ দিয়েছে তাকে"))
        assertTrue(ChainResolver.isPronoun("সর্বশেষ প্রেরক"))
        assertTrue(ChainResolver.isPronoun("the last person who messaged me"))
        assertTrue(ChainResolver.isPronoun("last sender"))
        assertFalse(ChainResolver.isPronoun("রহিম"))
        assertFalse(ChainResolver.isPronoun(""))
        assertFalse(ChainResolver.isPronoun(null))
    }

    @Test fun onlyCallAndReplyCanBeChained() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "শেষ যে মেসেজ দিয়েছে"), 0.9, "…")
        assertTrue(ChainResolver.isChain(call))
        val open = CommandSpec(Intent.APP_LAUNCH, mapOf(Slot.APP to "শেষ যে মেসেজ দিয়েছে"), 0.9, "…")
        assertFalse(ChainResolver.isChain(open))
        val named = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "রহিম"), 0.9, "…")
        assertFalse(ChainResolver.isChain(named))
    }

    @Test fun resolvesToTheNewestSenderAndFillsTheContactSlot() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "শেষ যে মেসেজ দিয়েছে তাকে"), 0.9, "…")
        val out = ChainResolver.resolve(call, senders)
        assertTrue(out is ChainOutcome.Resolved)
        out as ChainOutcome.Resolved
        assertEquals("রহিম", out.name)
        assertEquals("রহিম", out.spec.slot(Slot.CONTACT))
        assertEquals(Intent.CALL_PLACE, out.spec.intent)
        // Nothing else about the command changes.
        assertEquals(0.9, out.spec.confidence, 0.0)
    }

    @Test fun noCapturedMessagesIsAnHonestRefusal() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "last sender"), 0.9, "…")
        val out = ChainResolver.resolve(call, emptyList())
        assertTrue(out is ChainOutcome.NoSenders)
        assertEquals("no_senders", (out as ChainOutcome.NoSenders).reason)
    }

    @Test fun blankSendersAreIgnored() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "last sender"), 0.9, "…")
        val out = ChainResolver.resolve(call, listOf("", "  ", "Karim"))
        assertTrue(out is ChainOutcome.Resolved)
        assertEquals("Karim", (out as ChainOutcome.Resolved).name)
    }

    @Test fun ordinaryRecipientsAreNotTouched() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "রহিম"), 0.9, "…")
        assertEquals(ChainOutcome.NotAChain, ChainResolver.resolve(call, senders))
    }

    @Test fun announcementNamesThePersonItPicked() {
        val call = CommandSpec(Intent.CALL_PLACE, mapOf(Slot.CONTACT to "last sender"), 0.9, "…")
        assertTrue(ChainResolver.announced(call, "রহিম", true).contains("রহিম"))
        assertTrue(ChainResolver.announced(call, "Rahim", false).contains("Rahim"))
    }

    @Test fun parserProducesAChainableCallFromBangla() {
        val spec = CommandParser.offline("শেষ যে মেসেজ দিয়েছে তাকে ফোন করো")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        assertTrue(ChainResolver.isChain(spec))
    }

    @Test fun parserProducesAChainableCallFromEnglish() {
        val spec = CommandParser.offline("call the last person who messaged me")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        assertTrue(ChainResolver.isChain(spec))
    }

    @Test fun endToEndTheConfirmationShowsTheResolvedName() {
        val spec = CommandParser.offline("call the last person who messaged me")
        val out = ChainResolver.resolve(spec, senders) as ChainOutcome.Resolved
        val decision = CommandPipeline.decide(out.spec, bengali = false)
        assertTrue(decision is CommandDecision.Confirm) // placing a call always confirms
        decision as CommandDecision.Confirm
        assertTrue(decision.question.contains("রহিম"))
        assertNull(decision.confirm.slots[Slot.CONTACT]?.takeIf { it == spec.slot(Slot.CONTACT) })
    }

    @Test fun aNamedPersonIsNeverReplacedByTheChain() {
        val spec = CommandParser.offline("রহিমকে ফোন করো")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        assertFalse(ChainResolver.isChain(spec))
        assertEquals(ChainOutcome.NotAChain, ChainResolver.resolve(spec, senders))
    }
}

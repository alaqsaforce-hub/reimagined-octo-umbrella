package com.voicedock.ai.v131

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.draft.DraftCore
import com.voicedock.ai.draft.DraftRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.1 — the draft half of voice-to-voice. The rule under test is the project's standing promise:
 * **the assistant composes and shows, the user decides.** A draft may be tidied, described and inserted, but
 * never on its own, and the description of *where* it will land must match what will really happen.
 */
class DraftCoreTest {

    @Test fun aDraftKeepsTheUsersWordsAndOnlyTidiesTheEdges() {
        assertEquals("আমি কাল আসব", DraftCore.tidy("  আমি কাল আসব  "))
        assertEquals("আমি কাল আসব", DraftCore.tidy("আমি\nকাল\tআসব"))
        // The wake word is the one thing that is not part of what the user wants to say.
        assertEquals("ওয়াটসঅ্যাপ খোলো", DraftCore.tidy("শাহরুখ, ওয়াটসঅ্যাপ খোলো"))
        assertEquals("kal asbo", DraftCore.tidy("Shahrukh kal asbo"))
        // Wording is never rewritten — this is a draft, not a paraphrase.
        assertEquals("আমি কাল আসব না", DraftCore.tidy("আমি কাল আসব না"))
    }

    @Test fun aDraftIsNeverAutoinserted() {
        assertTrue(DraftCore.plan("হ্যালো", hasAccessibility = true).requiresExplicitInsert)
        assertTrue(DraftCore.plan("হ্যালো", hasAccessibility = false).requiresExplicitInsert)
        // There is no argument anywhere that turns insertion on by itself.
        assertFalse(DraftCore.plan("", hasAccessibility = true).requiresExplicitInsert && DraftCore.presentable(""))
    }

    @Test fun theRouteDescribesWhatWillReallyHappen() {
        val a11y = DraftCore.plan("হ্যালো", hasAccessibility = true)
        assertEquals(DraftRoute.ACCESSIBILITY, a11y.route)
        assertTrue(a11y.noteBn, a11y.noteBn.contains("বসবে"))

        val clip = DraftCore.plan("হ্যালো", hasAccessibility = false)
        assertEquals(DraftRoute.CLIPBOARD, clip.route)
        assertTrue(clip.noteBn, clip.noteBn.contains("Accessibility"))
        assertTrue(clip.noteEn, clip.noteEn.contains("copied"))
    }

    @Test fun theTargetIsNamedOnlyWhenItIsKnown() {
        val known = DraftCore.plan("হ্যালো", hasAccessibility = true, targetLabel = "WhatsApp Business")
        assertEquals("WhatsApp Business", known.targetLabel)
        assertTrue(known.noteBn, known.noteBn.contains("WhatsApp Business"))
        // No target known → the note must not invent one.
        val unknown = DraftCore.plan("হ্যালো", hasAccessibility = true)
        assertEquals("", unknown.targetLabel)
        assertTrue(unknown.noteBn, unknown.noteBn.contains("কার্সর"))
    }

    @Test fun aTwoLetterAccidentIsNotADraft() {
        assertFalse(DraftCore.presentable(""))
        assertFalse(DraftCore.presentable(" হ "))
        assertTrue(DraftCore.presentable("ঠিক আছে"))
    }

    @Test fun veryLongSpeechIsCappedNotTruncatedMidWordSilently() {
        val long = "ক".repeat(DraftCore.MAX_DRAFT + 500)
        assertEquals(DraftCore.MAX_DRAFT, DraftCore.tidy(long).length)
    }

    @Test fun spokenControlOfTheCardIsExactNotFuzzy() {
        for (s in listOf("ঢোকাও", "ঢুকিয়ে দাও", "বসাও", "বসিয়ে দাও", "লিখে দাও", "insert", "insert it", "paste it")) {
            assertTrue("‘$s’ must insert", DraftCore.isInsertCommand(s))
        }
        for (s in listOf("খসড়া বাতিল", "ড্রাফট বাতিল", "discard draft", "discard it", "throw it away")) {
            assertTrue("‘$s’ must discard", DraftCore.isDiscardCommand(s))
        }
        // A sentence that merely mentions inserting is a different request — it must not fire an insertion.
        assertFalse(DraftCore.isInsertCommand("ওখানে বসাও না, পরে করব"))
        assertFalse(DraftCore.isInsertCommand("হোয়াটসঅ্যাপ খোলো"))
        assertFalse(DraftCore.isDiscardCommand("হোয়াটসঅ্যাপ খোলো"))
    }

    @Test fun theLabelsExistInBothLanguages() {
        assertTrue(DraftCore.title(true).isNotBlank())
        assertTrue(DraftCore.insertLabel(false).isNotBlank())
        assertTrue(DraftCore.voiceHint(true).isNotBlank())
        assertTrue(DraftCore.voiceEmpty(false).isNotBlank())
        assertTrue(DraftCore.nothingText(true).contains("খসড়া"))
    }
}

/** The sentences that control the card, through the real offline parser. */
class DraftCommandParsingTest {

    @Test fun insertAndDiscardAreTheirOwnIntents() {
        assertEquals(Intent.DRAFT_INSERT, CommandParser.offline("ঢোকাও").intent)
        assertEquals(Intent.DRAFT_INSERT, CommandParser.offline("বসাও").intent)
        assertEquals(Intent.DRAFT_DISCARD, CommandParser.offline("খসড়া বাতিল").intent)
        assertEquals(Intent.VOICE_OPEN, CommandParser.offline("ভয়েস মোড খোলো").intent)
    }

    @Test fun theCardCommandsDoNotStealTheOlderOnes() {
        // Regression guard: the draft rules run first in the offline parser, so these must be untouched.
        assertEquals(Intent.APP_LAUNCH, CommandParser.offline("হোয়াটসঅ্যাপ খোলো").intent)
        assertEquals(Intent.CALL_PLACE, CommandParser.offline("রহিমকে ফোন করো").intent)
        assertEquals(Intent.WEB_SEARCH, CommandParser.offline("গুগলে আজকের আবহাওয়া খোঁজো").intent)
        assertEquals("2", CommandParser.offline("সিম ২ দিয়ে রহিমকে ফোন করো").slot(Slot.SIM))
    }

    @Test fun theNewIntentsAreRegisteredAndHarmless() {
        listOf(Intent.DRAFT_INSERT, Intent.DRAFT_DISCARD, Intent.VOICE_OPEN).forEach {
            assertTrue("$it registered", CommandRegistry.entries().contains(it))
            assertTrue("$it available", CommandRegistry.isAvailable(it))
            assertTrue("$it help", CommandRegistry.help(it, true).isNotBlank())
            // None of them can destroy anything, so none of them may pop a confirmation card.
            assertFalse("$it must not confirm", CommandRegistry.isDestructive(it))
        }
    }
}

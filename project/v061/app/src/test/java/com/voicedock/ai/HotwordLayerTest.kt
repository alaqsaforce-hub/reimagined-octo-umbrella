package com.voicedock.ai

import com.voicedock.ai.hotword.HotwordGate
import com.voicedock.ai.hotword.WakePhrase
import com.voicedock.ai.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.0 — the wake word "শাহরুখ" (after Shahrukh Khan), without a microphone.
 *
 * The service can only be exercised on a phone, but the two decisions that make or break a wake word are
 * pure and tested here: *did the recogniser just say the wake word?* and *may the microphone be open now?*
 * A false negative makes the assistant deaf; a false positive makes it listen to your conversations.
 */
class HotwordLayerTest {

    // ---------------------------------------------------------------- the phrase itself

    @Test fun theWakeWordIsShahrukh() {
        assertEquals("শাহরুখ", WakePhrase.BN)
        assertEquals("Shahrukh", WakePhrase.EN)
    }

    @Test fun banglaSpellingsAndPunctuation() {
        assertTrue(WakePhrase.matches("শাহরুখ"))
        assertTrue(WakePhrase.matches("শাহরুখ!"))
        assertTrue(WakePhrase.matches("হেই শাহরুখ"))
        assertTrue(WakePhrase.matches("  শাহরুখ। "))
        assertTrue(WakePhrase.matches("শাহরুখ খান"))
    }

    @Test fun englishSpellings() {
        assertTrue(WakePhrase.matches("shahrukh"))
        assertTrue(WakePhrase.matches("Shah Rukh"))
        assertTrue(WakePhrase.matches("SHAHRUKH"))
        assertTrue(WakePhrase.matches("shah rukh khan"))
    }

    @Test fun misheardVariantsStillWork() {
        // What phone recognisers actually return for the name.
        assertTrue(WakePhrase.matches("শারুক"))
        assertTrue(WakePhrase.matches("শাহরুক"))
        assertTrue(WakePhrase.matches("sharukh"))
        assertTrue(WakePhrase.matches("sahrukh"))
        assertTrue(WakePhrase.matches("shahruk"))
    }

    @Test fun oneEditAwayIsTolerated() {
        // "শাহরখ" (the vowel sign dropped) is one edit from "শাহরুখ".
        assertTrue(WakePhrase.matches("শাহরখ"))
        assertTrue(WakePhrase.matches("shahruh"))
        // …but a mere prefix is not the wake word.
        assertFalse(WakePhrase.matches("শাহ"))
    }

    @Test fun theStreetIsNotEmptyIfThePhraseIsNotThere() {
        assertFalse("a movie request is not a wake word", WakePhrase.matches("শাহরুখের সিনেমা দেখতে চাই"))
        assertFalse(WakePhrase.matches("শাহরুখের"))
        assertFalse(WakePhrase.matches("আজ আবহাওয়া কেমন"))
        assertFalse(WakePhrase.matches("what is the weather"))
        assertFalse(WakePhrase.matches(""))
        assertFalse(WakePhrase.matches("   "))
    }

    @Test fun aCommandInTheSameBreathIsSplitOff() {
        assertEquals("হোয়াটসঅ্যাপ খোলো", WakePhrase.remainder("শাহরুখ, হোয়াটসঅ্যাপ খোলো"))
        // "খান" belongs to the phrase itself, so nothing is left to run as a command.
        assertEquals("", WakePhrase.remainder("শাহরুখ খান"))
        assertEquals("open whatsapp", WakePhrase.remainder("shahrukh open whatsapp"))
        // Only the wake word -> nothing left, so the service starts listening instead of running a command.
        assertEquals("", WakePhrase.remainder("শাহরুখ"))
        assertEquals("", WakePhrase.remainder("শাহরুখ!"))
    }

    @Test fun normalisationKeepsIndexesAligned() {
        // Length-preserving normalisation is what makes [WakePhrase.remainder] cut the ORIGINAL string.
        listOf("শাহরুখ, ওয়াটসঅ্যাপ", "SHAHRUKH! ১২৩", "  shah rukh  ").forEach {
            assertEquals(it, it.length, WakePhrase.softNormalize(it).length)
        }
        val n = WakePhrase.softNormalize("গান! ১২৩")
        assertTrue(n.contains("123"))          // Bengali digits become ASCII
        assertFalse(n.contains("!"))           // punctuation becomes a space
        assertEquals("গান! ১২৩".length, n.length)
    }

    @Test fun theHitCarriesItsPosition() {
        val h: WakePhrase.Hit? = WakePhrase.hit("শাহরুখ, ওয়াটসঅ্যাপ খোলো")
        assertNotNull(h)
        assertEquals(0, h!!.index)
        assertEquals("শাহরুখ".length, h.length)
        assertTrue(h.exact)
    }

    // ---------------------------------------------------------------- may the mic be open?

    @Test fun offByDefaultAndScreenGated() {
        assertFalse(HotwordGate.shouldListen(enabled = false, alwaysOn = true, screenOn = true, dictationBusy = false))
        assertTrue(HotwordGate.shouldListen(enabled = true, alwaysOn = false, screenOn = true, dictationBusy = false))
        assertFalse("screen off, always-on not opted in", HotwordGate.shouldListen(true, false, false, false))
        assertTrue(HotwordGate.shouldListen(true, true, false, false))
    }

    @Test fun neverWhileDictationOwnsTheMicrophone() {
        assertFalse(HotwordGate.shouldListen(true, true, true, dictationBusy = true))
        assertTrue(HotwordGate.dictationBusy("LISTENING"))
        assertTrue(HotwordGate.dictationBusy("PROCESSING"))
        assertFalse(HotwordGate.dictationBusy("REVIEW"))
        assertFalse(HotwordGate.dictationBusy("IDLE"))
        assertFalse(HotwordGate.dictationBusy(null))
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(400L, HotwordGate.backoffMs(0))
        assertEquals(1_000L, HotwordGate.backoffMs(1))
        assertEquals(5_000L, HotwordGate.backoffMs(999))
        assertTrue(HotwordGate.backoffMs(3) > HotwordGate.backoffMs(1))
    }

    @Test fun statusIsHonestInBothLanguages() {
        val off = HotwordGate.status(enabled = false, alwaysOn = false, screenOn = true, bengali = true)
        assertTrue(off.contains("বন্ধ"))
        val paused = HotwordGate.status(enabled = true, alwaysOn = false, screenOn = false, bengali = true)
        assertTrue(paused.contains("স্ক্রিন"))
        val on = HotwordGate.status(enabled = true, alwaysOn = false, screenOn = true, bengali = true)
        assertTrue(on.contains("চালু"))
        assertTrue(HotwordGate.status(false, false, true, bengali = false).contains("Off"))
    }

    @Test fun settingsDefaultsKeepTheMicrophoneClosed() {
        val s = AppSettings()
        assertFalse("the wake word must be opt-in", s.hotwordEnabled)
        assertFalse("always-on is a second, separate opt-in", s.hotwordAlwaysOn)
        assertFalse("the guide runs on first launch", s.onboardingDone)
    }

    @Test fun everyWakeWordCandidateIsNullOrMatched() {
        // Nothing about the matcher may throw on odd input: it runs on every partial recognition result.
        listOf("", " ", "শাহ", "শাহরুখখ", "12345", "😀", "শাহরুখ 😀", "sha").forEach { WakePhrase.matches(it) }
        assertNull(WakePhrase.hit(""))
    }
}

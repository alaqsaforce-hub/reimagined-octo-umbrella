package com.voicedock.ai

import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.media.MediaAction
import com.voicedock.ai.media.MediaCandidate
import com.voicedock.ai.media.MediaPick
import com.voicedock.ai.media.MediaText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.0 — the media layer without a phone.
 *
 * The Android half ([com.voicedock.ai.media.MediaControl]) can only be exercised on a device with a real
 * player; everything that decides *what* to send — the spoken word, the session to pick, the inverse for
 * undo — is pure and tested here.
 */
class MediaLayerTest {

    // ---------------------------------------------------------------- spoken → action

    @Test fun banglaNextAndPrevious() {
        assertEquals(MediaAction.NEXT, MediaText.action("পরের গান দাও"))
        assertEquals(MediaAction.NEXT, MediaText.action("পরের ট্র্যাক"))
        assertEquals(MediaAction.PREVIOUS, MediaText.action("আগের গান"))
    }

    @Test fun banglaPlayPauseAndStopAreDifferentActions() {
        assertEquals(MediaAction.PLAY, MediaText.action("গান চালাও"))
        assertEquals(MediaAction.PAUSE, MediaText.action("গান থামাও"))
        assertEquals(MediaAction.STOP, MediaText.action("গান বন্ধ করো"))
        // The distinction matters: pausing keeps the queue, stopping ends it.
        assertTrue(MediaText.action("গান থামাও") != MediaText.action("গান বন্ধ করো"))
    }

    @Test fun englishPhrasings() {
        assertEquals(MediaAction.NEXT, MediaText.action("next song"))
        assertEquals(MediaAction.PREVIOUS, MediaText.action("previous track"))
        assertEquals(MediaAction.PAUSE, MediaText.action("pause the music"))
        assertEquals(MediaAction.PLAY, MediaText.action("play music"))
        assertEquals(MediaAction.STOP, MediaText.action("stop the music"))
    }

    @Test fun vagueRequestIsNotGuessed() {
        // "গান" / "music" alone could mean play, pause or stop — the assistant must ask.
        assertNull(MediaText.action("গান"))
        assertNull(MediaText.action("মিউজিক"))
        assertNull(MediaText.action("song"))
        assertTrue(MediaText.looksLikeMedia("গান"))
        assertTrue(MediaText.looksLikeMedia("music"))
        assertFalse(MediaText.looksLikeMedia("হোয়াটসঅ্যাপ খোলো"))
    }

    @Test fun offeredChoicesAreAllUnderstood() {
        val choices = MediaText.choices()
        assertEquals(4, choices.size)
        val actions = choices.map { MediaText.action(it) }
        assertTrue(actions.all { it != null })
        // Four buttons must not collapse into one action.
        assertEquals(4, actions.toSet().size)
    }

    // ---------------------------------------------------------------- undo inverse

    @Test fun inverseOnlyWhereItIsReal() {
        assertEquals(MediaAction.PAUSE, MediaText.inverse(MediaAction.PLAY))
        assertEquals(MediaAction.PLAY, MediaText.inverse(MediaAction.PAUSE))
        assertEquals(MediaAction.NEXT, MediaText.inverse(MediaAction.PREVIOUS))
        // Skipping forward cannot be undone: the previous queue position is gone.
        assertNull(MediaText.inverse(MediaAction.NEXT))
        assertNull(MediaText.inverse(MediaAction.STOP))
    }

    // ---------------------------------------------------------------- wording

    @Test fun doneMessageShowsTheTrackOnlyWhenThePlayerSaidIt() {
        assertTrue(MediaText.done(MediaAction.NEXT, true, "Amar Shohor").contains("Amar Shohor"))
        assertEquals("চালু হলো ✓", MediaText.done(MediaAction.PLAY, true, ""))
        assertEquals("paused ✓", MediaText.done(MediaAction.PAUSE, false, ""))
    }

    @Test fun failureMessagesNameTheRealProblem() {
        assertTrue(MediaText.failed(true, "no_access").contains("নোটিফিকেশন"))
        assertTrue(MediaText.failed(false, "no_access").contains("notification access"))
        assertTrue(MediaText.failed(true, "no_player").contains("প্লেয়ার"))
        assertTrue(MediaText.failed(false, "com.spotify.music").contains("com.spotify.music"))
    }

    // ---------------------------------------------------------------- session picking

    @Test fun pickingPrefersWhatIsPlaying() {
        val list = listOf(
            MediaCandidate("a", playing = false, title = ""),
            MediaCandidate("b", playing = true, title = "Song"),
            MediaCandidate("c", playing = false, title = "Other"),
        )
        assertEquals(1, MediaPick.best(list))
    }

    @Test fun pickingFallsBackToATitledThenTheFirstSession() {
        assertEquals(2, MediaPick.best(listOf(
            MediaCandidate("a", false, ""), MediaCandidate("b", false, ""), MediaCandidate("c", false, "T"),
        )))
        assertEquals(0, MediaPick.best(listOf(MediaCandidate("a", false, ""), MediaCandidate("b", false, ""))))
        assertEquals(-1, MediaPick.best(emptyList()))
    }

    // ---------------------------------------------------------------- command layer

    @Test fun parserUnderstandsMediaCommandsOffline() {
        val spec = CommandParser.offline("পরের গান দাও")
        assertEquals(Intent.MEDIA, spec.intent)
        assertEquals("next", spec.slot(Slot.MEDIA))
        assertTrue(CommandParser.confident(spec))
        assertEquals(MediaAction.NEXT, MediaText.action(spec.slot(Slot.MEDIA).orEmpty()))
    }

    @Test fun mediaIsAvailableAndNotDestructive() {
        assertTrue(CommandRegistry.isAvailable(Intent.MEDIA))
        assertFalse(CommandRegistry.isDestructive(Intent.MEDIA))
    }

    @Test fun aFullySpecifiedMediaCommandExecutes() {
        val spec = CommandParser.offline("গান থামাও")
        assertEquals(CommandDecision.Execute, CommandPipeline.decide(spec, bengali = true))
    }

    @Test fun mediaWithoutAnActionAsksInsteadOfActing() {
        val spec = CommandParser.offline("গান")
        assertEquals(Intent.MEDIA, spec.intent)
        assertNull(spec.slot(Slot.MEDIA))
        val d = CommandPipeline.decide(spec, bengali = true, candidates = mapOf(Slot.MEDIA to MediaText.choices()))
        assertTrue("expected a clarification, got $d", d is CommandDecision.Clarify)
        d as CommandDecision.Clarify
        assertTrue(d.question.contains("গান"))
        assertEquals(4, d.choices.size)
        assertNotNull(MediaText.action(d.choices.first().slots[Slot.MEDIA].orEmpty()))
    }

    @Test fun exactSlotMissingIsReportedForMedia() {
        val spec = CommandSpec(Intent.MEDIA, mapOf(Slot.QUERY to "গান"), 0.9, "গান")
        assertEquals(listOf(Slot.MEDIA), CommandRegistry.missing(spec))
    }
}

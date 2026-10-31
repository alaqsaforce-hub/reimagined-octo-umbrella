package com.voicedock.ai

import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.UndoEntry
import com.voicedock.ai.command.UndoKind
import com.voicedock.ai.command.UndoLog
import com.voicedock.ai.command.UndoText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.0 — one honest step back.
 *
 * The undo offer only appears for actions that can genuinely be reversed (a saved memory, a media
 * transport command). The bookkeeping is pure and tested here; the revert itself lives in the service.
 */
class UndoLayerTest {

    private fun entry(at: Long, label: String = "মনে রাখা: কাল ৩টায় মিটিং") =
        UndoEntry("u1", label, at, UndoKind.MEMORY_ADD)

    @Test fun aFreshEntryCanBeTakenOnce() {
        var now = 1_000L
        val log = UndoLog(ttlMs = 60_000, clock = { now })
        log.push(entry(now))
        now += 30_000
        assertEquals("u1", log.peek()?.id)
        assertEquals("u1", log.take()?.id)
        assertNull(log.take())          // consuming once is the contract
    }

    @Test fun anExpiredEntryDisappears() {
        var now = 1_000L
        val log = UndoLog(ttlMs = 60_000, clock = { now })
        log.push(entry(now))
        now += 60_001
        assertNull(log.peek())
        assertNull(log.take())          // and it is dropped, not kept for later
    }

    @Test fun pushingAgainReplacesTheOlderOffer() {
        var now = 1_000L
        val log = UndoLog(ttlMs = 60_000, clock = { now })
        log.push(entry(now, "first"))
        now += 5_000
        log.push(entry(now, "second"))
        assertEquals("second", log.take()?.label)
        assertNull(log.take())
    }

    @Test fun clearingRemovesTheOffer() {
        val log = UndoLog(clock = { 0L })
        log.push(entry(0))
        log.clear()
        assertNull(log.peek())
    }

    @Test fun wordingIsPlainAndHonest() {
        assertTrue(UndoText.chip(true).contains("ফিরিয়ে"))
        assertTrue(UndoText.chip(false).contains("Undo"))
        assertTrue(UndoText.nothing(true).contains("কিছু নেই"))
        assertTrue(UndoText.nothing(false).contains("Nothing to undo"))
        assertTrue(UndoText.done(entry(0, "মনে রাখা: মিটিং"), true).contains("মনে রাখা: মিটিং"))
    }

    // ---------------------------------------------------------------- command layer

    @Test fun parserUnderstandsUndo() {
        assertEquals(Intent.UNDO, CommandParser.offline("আনডু করো").intent)
        assertEquals(Intent.UNDO, CommandParser.offline("undo that").intent)
        assertEquals(Intent.UNDO, CommandParser.offline("ফিরিয়ে নাও").intent)
        assertTrue(CommandParser.confident(CommandParser.offline("আনডু")))
    }

    @Test fun undoDoesNotSwallowOtherCommands() {
        assertEquals(Intent.MEMORY_STORE, CommandParser.offline("মনে রাখো কাল ৩টায় মিটিং").intent)
        assertEquals(Intent.CALL_ANSWER, CommandParser.offline("ধরো").intent)
    }

    @Test fun undoIsAvailableAndNotDestructive() {
        assertTrue(CommandRegistry.isAvailable(Intent.UNDO))
        assertFalse(CommandRegistry.isDestructive(Intent.UNDO))
        assertEquals(emptyList<Any>(), CommandRegistry.missing(CommandParser.offline("আনডু")))
        assertEquals(CommandDecision.Execute, CommandPipeline.decide(CommandParser.offline("আনডু"), bengali = true))
    }

    @Test fun undoIsNotOfferedAsAClarificationChoice() {
        // The clarification buttons stay the four simple commands — undo is a follow-up, not a menu entry.
        assertFalse(CommandRegistry.offered(bengali = true).contains(Intent.UNDO))
    }
}

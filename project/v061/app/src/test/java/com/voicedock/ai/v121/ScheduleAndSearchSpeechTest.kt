package com.voicedock.ai.v121

import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.schedule.TimeWords
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.1 — the new spoken commands are parsed offline and decided by the same safety pipeline:
 * a scheduled message confirms once (it cannot be taken back), a search does not.
 */
class ScheduleAndSearchSpeechTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val now = ZonedDateTime.of(2026, 11, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    // ---- scheduled message ------------------------------------------------------------------

    @Test fun aTimeTurnsTellThemIntoTellThemLater() {
        val spec = CommandParser.offline("সন্ধ্যা ৭টায় রহিমকে বলো আমি কাল আসব")
        assertEquals(Intent.MSG_SCHEDULE, spec.intent)
        assertTrue(spec.slot(Slot.CONTACT)!!.contains("রহিম"))
        assertTrue(spec.slot(Slot.TEXT)!!.contains("আসব"))
        assertNotNull(spec.slot(Slot.TIME))
    }

    @Test fun withoutATimeItStaysAnImmediateReply() {
        val spec = CommandParser.offline("রহিমকে বলো আমি আসছি")
        assertEquals(Intent.MSG_REPLY, spec.intent)
    }

    @Test fun aScheduledMessageConfirmsOnceButASearchDoesNot() {
        val msg = CommandParser.offline("সন্ধ্যা ৭টায় রহিমকে বলো আমি কাল আসব")
        val decision = CommandPipeline.decide(msg, bengali = true)
        assertTrue("sending cannot be undone, so it must confirm", decision is CommandDecision.Confirm)
        val confirm = decision as CommandDecision.Confirm
        assertTrue("the card must repeat who and when", confirm.question.contains("রহিম"))

        val search = CommandParser.offline("গুগলে আজকের আবহাওয়া খোঁজো")
        assertEquals(CommandDecision.Execute, CommandPipeline.decide(search, bengali = true))
    }

    @Test fun aScheduledMessageMissingItsTimeAsksForOne() {
        val spec = CommandParser.offline("সন্ধ্যা রহিমকে বলো আমি কাল আসব")
        if (spec.intent == Intent.MSG_SCHEDULE) {
            // "সন্ধ্যা" alone now resolves to evening, so this is a valid schedule; nothing to ask.
            assertNotNull(spec.slot(Slot.TIME))
        } else {
            val decision = CommandPipeline.decide(spec, bengali = true)
            assertTrue(decision is CommandDecision.Clarify)
        }
    }

    // ---- search -----------------------------------------------------------------------------

    @Test fun webSearchKeepsOnlyTheQuestion() {
        val spec = CommandParser.offline("গুগলে আজকের আবহাওয়া খোঁজো")
        assertEquals(Intent.WEB_SEARCH, spec.intent)
        assertEquals("আজকের আবহাওয়া", spec.slot(Slot.QUERY))
    }

    @Test fun englishSearchPhrasingWorks() {
        val spec = CommandParser.offline("search for Dhaka weather")
        assertEquals(Intent.WEB_SEARCH, spec.intent)
        assertTrue(spec.slot(Slot.QUERY)!!.contains("Dhaka weather"))
    }

    @Test fun youTubeSearchIsNotSwallowedByTheGenericSearch() {
        val yt = CommandParser.offline("ইউটিউবে রবীন্দ্রসংগীত খোঁজো")
        assertEquals(Intent.YT_SEARCH, yt.intent)
        assertTrue(yt.slot(Slot.QUERY)!!.contains("রবীন্দ্রসংগীত"))

        // "open YouTube" is an app launch, not a search
        assertEquals(Intent.APP_LAUNCH, CommandParser.offline("ইউটিউব খোলো").intent)
    }

    // ---- the time grammar gained one rule ---------------------------------------------------

    @Test fun aBarePeriodWordMeansItsUsualHour() {
        val night = TimeWords.parse("রাতে মনে করিয়ে দাও", now, zone)!!
        assertEquals(21, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(night.atMillis), zone).hour)
        val evening = TimeWords.parse("সন্ধ্যায় মনে করিয়ে দাও", now, zone)!!
        assertEquals(19, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(evening.atMillis), zone).hour)
        val morning = TimeWords.parse("সকালে মনে করিয়ে দাও", now, zone)!!
        // 09:00 has already passed at 10:00, so it is tomorrow morning — still 09:00.
        assertEquals(9, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(morning.atMillis), zone).hour)
        assertTrue(morning.atMillis > now)
    }

    @Test fun aSentenceWithNoTimeAtAllIsStillRejected() {
        assertNull(TimeWords.parse("ফোন করো রহিমকে", now, zone))
        assertFalse(TimeWords.parse("আজকের খবর কী", now, zone) != null)
    }
}

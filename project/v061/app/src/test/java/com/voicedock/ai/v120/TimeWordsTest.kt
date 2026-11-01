package com.voicedock.ai.v120

import com.voicedock.ai.schedule.AlarmPlanner
import com.voicedock.ai.schedule.TimeWords
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 — the time grammar behind "৩টায় মনে করিয়ে দাও".
 *
 * Every case uses a fixed `now` (Saturday 10:00 in Dhaka) so the expectations are exact instants, not
 * "somewhere in the next 24 hours".
 */
class TimeWordsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 1, 10, 0) // Sat 10:00

    private fun parse(text: String, nowMs: Long = now) = TimeWords.parse(text, nowMs, zone)

    @Test fun bareClockInTheAfternoonMeansTheNextOccurrence() {
        val g = parse("৩টায় মনে করিয়ে দাও পানি খেতে")!!
        assertEquals("15:00 same day", at(2026, 11, 1, 15, 0), g.atMillis)
    }

    @Test fun lateAtNightABareClockRollsToTomorrowMorning() {
        val evening = at(2026, 11, 1, 22, 0)
        val g = TimeWords.parse("৩টায় মনে করিয়ে দাও", evening, zone)!!
        // 03:00 today is long gone and 15:00 today is also gone → tomorrow 03:00.
        assertEquals(at(2026, 11, 2, 3, 0), g.atMillis)
    }

    @Test fun periodWordsAreExact() {
        assertEquals(at(2026, 11, 1, 19, 0), parse("সন্ধ্যা ৭টায় মনে করিয়ে দাও")!!.atMillis)
        assertEquals(at(2026, 11, 1, 21, 0), parse("রাত ৯টায় মনে করিয়ে দাও")!!.atMillis)
        assertEquals(at(2026, 11, 1, 12, 0), parse("দুপুর ১২টায় মনে করিয়ে দাও")!!.atMillis)
        // রাত ১২টা is midnight, not noon.
        assertEquals(at(2026, 11, 2, 0, 0), parse("রাত ১২টায় মনে করিয়ে দাও")!!.atMillis)
    }

    @Test fun tomorrowAndMorning() {
        val g = parse("কাল সকাল ৯টায় মনে করিয়ে দাও")!!
        assertEquals(at(2026, 11, 2, 9, 0), g.atMillis)
        assertTrue(g.explicitDay)
        assertEquals(at(2026, 11, 1, 20, 0), parse("আজ রাত ৮টায় মনে করিয়ে দাও")!!.atMillis)
    }

    @Test fun relativePhrasesWinOverClockReadings() {
        assertEquals(now + 20 * 60_000L, parse("২০ মিনিট পরে মনে করিয়ে দাও")!!.atMillis)
        assertEquals(now + 2 * 3_600_000L, parse("২ ঘণ্টা পরে মনে করিয়ে দাও")!!.atMillis)
        assertEquals(now + 15 * 60_000L, parse("in 15 minutes remind me to stretch")!!.atMillis)
        assertEquals(now + 30 * 1_000L, parse("৩০ সেকেন্ড পরে মনে করিয়ে দাও")!!.atMillis)
    }

    @Test fun englishAmPmAndHalfHours() {
        assertEquals(at(2026, 11, 1, 15, 30), parse("remind me at 3:30 pm")!!.atMillis)
        // 09:30 has already passed at 10:00, so the next reading (21:30) is meant.
        assertEquals(at(2026, 11, 1, 21, 30), parse("remind me at 9:30")!!.atMillis)
        assertEquals(at(2026, 11, 2, 8, 0), parse("remind me tomorrow at 8 am")!!.atMillis)
    }

    @Test fun bengaliDigitsBecomeAscii() {
        assertEquals("2026 11 01 03:30", TimeWords.asciiDigits("২০২৬ ১১ ০১ ০৩:৩০"))
        assertEquals("৯", TimeWords.bn("9"))
    }

    @Test fun noTimeMeansNoGuess() {
        assertNull(parse("মনে করিয়ে দাও"))
        assertNull(parse("আজকের আবহাওয়া কেমন"))
        assertNull(parse(""))
    }

    @Test fun aResultIsNeverInThePast() {
        // 09:00 already today; explicit period morning → must roll to tomorrow rather than fire immediately.
        val g = parse("সকাল ৯টায় মনে করিয়ে দাও")!!
        assertTrue(g.atMillis > now)
        assertEquals(at(2026, 11, 2, 9, 0), g.atMillis)
    }

    @Test fun bengaliLabelReadsLikeAPersonWroteIt() {
        assertEquals("আজ বিকাল ৩:০০", TimeWords.label(at(2026, 11, 1, 15, 0), now, bengali = true, zone = zone))
        assertEquals("কাল সকাল ৯:০০", TimeWords.label(at(2026, 11, 2, 9, 0), now, bengali = true, zone = zone))
        assertEquals("today 15:00", TimeWords.label(at(2026, 11, 1, 15, 0), now, bengali = false, zone = zone))
        assertEquals("tomorrow 09:00", TimeWords.label(at(2026, 11, 2, 9, 0), now, bengali = false, zone = zone))
    }

    @Test fun alarmPlannerIsHonestAboutExactness() {
        val plan = AlarmPlanner.plan(now, now + 60_000, exactAllowed = true)!!
        assertTrue(plan.exact)
        assertEquals(0, plan.lateByMinutes)
        val loose = AlarmPlanner.plan(now, now + 60_000, exactAllowed = false)!!
        assertEquals(false, loose.exact)
        assertTrue(loose.lateByMinutes > 0)
        // Too far in the past: nothing to schedule.
        assertNull(AlarmPlanner.plan(now, now - 10 * 60_000, exactAllowed = true))
        // A moment that passed a couple of seconds ago still deserves one notification.
        assertTrue(AlarmPlanner.plan(now, now - 5_000, exactAllowed = true) != null)
        assertEquals(now + 10 * 60_000, AlarmPlanner.snooze(now, 10))
    }
}

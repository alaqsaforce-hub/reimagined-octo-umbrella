package com.voicedock.ai.v134

import com.voicedock.ai.calendar.CalEvent
import com.voicedock.ai.calendar.CalRange
import com.voicedock.ai.calendar.CalendarCore
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.4 — the calendar. Pure decisions only: which slice of time a question means, what the API's JSON really
 * says, what request body is sent, and — the rule that matters most for a shared calendar — that nothing is
 * booked at a time the phone did not understand, and nothing is ever reported as added without an id back.
 */
class CalendarCoreTest {

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val now: Long = LocalDateTime.of(2026, 11, 1, 9, 30).atZone(zone).toInstant().toEpochMilli()

    @Test fun everyRangeBecomesAHalfOpenWindow() {
        val (from, to) = CalendarCore.window(CalRange.TODAY, now, zone)
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0).atZone(zone).toInstant().toEpochMilli(), from)
        assertEquals(24L * 60 * 60 * 1000, to - from)

        val (f2, t2) = CalendarCore.window(CalRange.TOMORROW, now, zone)
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0).atZone(zone).toInstant().toEpochMilli(), f2)
        assertEquals(24L * 60 * 60 * 1000, t2 - f2)

        val (f3, t3) = CalendarCore.window(CalRange.WEEK, now, zone)
        assertEquals(from, f3)
        assertEquals(7 * 24L * 60 * 60 * 1000, t3 - f3)
    }

    @Test fun theSpokenRangeIsUnderstoodInBothLanguages() {
        assertEquals(CalRange.TODAY, CalendarCore.range("আজকের ক্যালেন্ডার"))
        assertEquals(CalRange.TOMORROW, CalendarCore.range("কালকের ক্যালেন্ডার"))
        assertEquals(CalRange.TOMORROW, CalendarCore.range("আগামীকাল"))
        assertEquals(CalRange.WEEK, CalendarCore.range("এই সপ্তাহের ক্যালেন্ডার"))
        assertEquals(CalRange.TODAY, CalendarCore.range("what's on my calendar today"))
        assertEquals(CalRange.WEEK, CalendarCore.range("my calendar this week"))
        assertEquals(CalRange.TOMORROW, CalendarCore.range("কালকের ক্যালেন্ডার"))
        assertEquals(CalRange.NAMED_DAY, CalendarCore.range("শুক্রবারের ক্যালেন্ডার"))
        assertEquals(java.time.DayOfWeek.FRIDAY, CalendarCore.weekday("শুক্রবারের ক্যালেন্ডার"))
    }

    @Test fun anamedWeekdayIsTheNextTimeThatDayHappens() {
        // 2026-11-01 is a Sunday; the next Friday is the 6th, and "রবিবার" is today.
        val (fFrom, fTo) = CalendarCore.window(CalRange.NAMED_DAY, now, zone, word = "শুক্রবার")
        assertEquals(LocalDateTime.of(2026, 11, 6, 0, 0).atZone(zone).toInstant().toEpochMilli(), fFrom)
        assertEquals(24L * 60 * 60 * 1000, fTo - fFrom)
        val (sFrom, _) = CalendarCore.window(CalRange.NAMED_DAY, now, zone, word = "রবিবার")
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0).atZone(zone).toInstant().toEpochMilli(), sFrom)
        assertTrue(CalendarCore.rangeLabel(CalRange.NAMED_DAY, now, true, zone, "শুক্রবার").contains("শুক্রবার"))
    }

    @Test fun theApiJsonIsReadWithoutInventingAnything() {
        val json = """
            {"items":[
              {"id":"a1","summary":"দাঁতের ডাক্তার",
               "start":{"dateTime":"2026-11-01T15:00:00+06:00"},"end":{"dateTime":"2026-11-01T16:00:00+06:00"},
               "location":"ধানমন্ডি"},
              {"id":"a2","summary":"ঈদ holidays","start":{"date":"2026-11-05"},"end":{"date":"2026-11-06"}},
              {"id":"a3","start":{"dateTime":"2026-11-01T10:00:00+06:00"}},
              {"id":"a4","summary":"শুধু শুরু","start":{"dateTime":"2026-11-01T11:00:00+06:00"}}
            ]}
        """.trimIndent()
        val events = CalendarCore.parseEvents(json)
        // The third item has no title: it is dropped, never shown as "(no title)".
        assertEquals(listOf("দাঁতের ডাক্তার", "ঈদ holidays", "শুধু শুরু"), events.map { it.title })
        assertEquals("ধানমন্ডি", events[0].location)
        assertFalse(events[0].allDay)
        assertTrue(events[1].allDay)
        // Missing end → one hour, which is the honest default rather than "zero-length".
        assertEquals(60 * 60 * 1000L, events[2].endMillis - events[2].startMillis)
        assertTrue(CalendarCore.parseEvents("not json").isEmpty())
        assertTrue(CalendarCore.parseEvents("{}").isEmpty())
    }

    @Test fun theListAnswerIsHonestWhenTheDayIsEmpty() {
        val empty = CalendarCore.list(emptyList(), "আজ", bengali = true)
        assertTrue(empty, empty.contains("কোনো ইভেন্ট নেই"))
        val one = CalendarCore.list(
            listOf(CalEvent("1", "মিটিং", now, now + 3_600_000, location = "অফিস")),
            "আজ", bengali = true, zone = zone,
        )
        assertTrue(one, one.contains("১টা ইভেন্ট"))
        assertTrue(one, one.contains("মিটিং"))
        assertTrue(one, one.contains("১ ঘণ্টা"))
        assertTrue(one, one.contains("অফিস"))
    }

    @Test fun theRequestBodyCarriesThePhonesZoneAndEscapesText() {
        val body = CalendarCore.createBody("মিটিং \"গুরুত্বপূর্ণ\"", now, now + 3_600_000, zone)
        assertTrue(body, body.contains("\"summary\":\"মিটিং \\\"গুরুত্বপূর্ণ\\\"\""))
        assertTrue(body, body.contains("\"timeZone\":\"Asia/Dhaka\""))
        assertTrue(body, body.contains("\"dateTime\":\"2026-11-01T09:30:00\""))
        assertTrue(body, body.contains("\"end\""))
        // No location/description keys when there is nothing to say.
        assertFalse(body.contains("\"location\""))
    }

    @Test fun theConfirmationSaysExactlyWhatWillBeBooked() {
        val text = CalendarCore.draftText("ডাক্তার", now, now + 3_600_000, bengali = true, zone = zone)
        assertTrue(text, text.contains("ডাক্তার"))
        assertTrue(text, text.contains("০৯:৩০–১০:৩০"))
        assertTrue(text, text.contains("নভেম্বর"))
        assertTrue(CalendarCore.noTime(true).contains("সময় বলুন"))
        assertTrue(CalendarCore.noTitle(false).contains("title"))
        assertTrue(CalendarCore.added("X", true).startsWith("✓"))
    }

    @Test fun failuresAreSentencesNotSilence() {
        assertTrue(CalendarCore.failure("no_token", true).contains("কানেক্টর"))
        assertTrue(CalendarCore.failure("http_401", false).contains("401"))
        assertTrue(CalendarCore.failure("http_429", true).contains("কোটা"))
        assertTrue(CalendarCore.failure("nonet", false).contains("internet"))
        assertTrue(CalendarCore.failure("weird", true).contains("weird"))
    }

    @Test fun durationsReadNaturally() {
        assertEquals("১ ঘণ্টা", CalendarCore.durationLabel(3_600_000, true))
        assertEquals("১ ঘণ্টা ৩০ মিনিট", CalendarCore.durationLabel(5_400_000, true))
        assertEquals("৪৫ মিনিট", CalendarCore.durationLabel(2_700_000, true))
        assertEquals("1h 30m", CalendarCore.durationLabel(5_400_000, false))
    }
}

class CalendarCommandParsingTest {

    @Test fun readingTheCalendarNeedsNoTime() {
        val spec = CommandParser.offline("আজকের ক্যালেন্ডার দেখাও")
        assertEquals(Intent.CAL_LIST, spec.intent)
        assertTrue(CommandRegistry.missing(spec).isEmpty())
    }

    @Test fun addingAnEventKeepsTheTitleAndTheTime() {
        val spec = CommandParser.offline("কাল ৩টায় ডাক্তারের অ্যাপয়েন্টমেন্ট ক্যালেন্ডারে যোগ করো")
        assertEquals(Intent.CAL_ADD, spec.intent)
        val title = spec.slot(Slot.TEXT).orEmpty()
        assertTrue(title, title.contains("ডাক্তারের") && title.contains("অ্যাপয়েন্টমেন্ট"))
        assertFalse("the calendar words are not part of the title", title.contains("ক্যালেন্ডার"))
        assertTrue(spec.slot(Slot.TIME).orEmpty().contains("৩টায়"))
    }

    @Test fun aReminderIsStillAReminderNotACalendarEvent() {
        assertEquals(Intent.REMINDER_ADD, CommandParser.offline("কাল ৩টায় মনে করিয়ে দাও পানি খেতে").intent)
        assertEquals(Intent.REMINDER_LIST, CommandParser.offline("রিমাইন্ডারের তালিকা").intent)
    }

    @Test fun theCalendarIntentsAreRegisteredAndWritingConfirms() {
        listOf(Intent.CAL_LIST, Intent.CAL_ADD).forEach {
            assertTrue("$it registered", CommandRegistry.entries().contains(it))
            assertTrue("$it available", CommandRegistry.isAvailable(it))
            assertTrue("$it help", CommandRegistry.help(it, true).isNotBlank())
        }
        assertTrue("adding writes to the user's calendar, so it must confirm", CommandRegistry.isDestructive(Intent.CAL_ADD))
        assertFalse(CommandRegistry.isDestructive(Intent.CAL_LIST))
    }
}

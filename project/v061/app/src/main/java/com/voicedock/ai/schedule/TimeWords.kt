package com.voicedock.ai.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v1.2.0 — "৩টায় মনে করিয়ে দাও", "in 20 minutes", "কাল সকাল ৯টায়" → an exact instant.
 *
 * Everything here is pure java.time + regex, no Android, so the whole time grammar is unit-tested. The
 * rules follow how the words are actually used in Bengali and English:
 *
 *  * a **relative** phrase ("২০ মিনিট পরে", "in 20 minutes") always wins over a clock reading;
 *  * a bare clock ("৩টায়") has no am/pm, so the *next* occurrence wins (10:00 → 15:00, 16:00 → 03:00
 *    tomorrow) instead of silently scheduling something 9 hours in the past;
 *  * a period word (সকাল/দুপুর/বিকাল/সন্ধ্যা/রাত, am/pm) is honoured exactly, including the two traps
 *    রাত ১২টা = 00:00 and দুপুর ১২টা = 12:00;
 *  * "কাল" is tomorrow (that is what someone setting a reminder means), "আজ" is today.
 *
 * When the result would already be in the past, the day is rolled forward once — a reminder must never be
 * created in the past.
 */
data class TimeGuess(
    val atMillis: Long,
    /** A day word (আজ/কাল/…) was spoken. */
    val explicitDay: Boolean,
    /** A period word (সকাল/রাত/pm/…) was spoken. */
    val explicitPeriod: Boolean,
    /** The part of the sentence that was understood, for the confirmation line. */
    val matched: String,
)

object TimeWords {

    private val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    /** Bengali digits → ASCII, so one regex covers both scripts. */
    fun asciiDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            val i = bnDigits.indexOf(c)
            append(if (i >= 0) ('0' + i) else c)
        }
    }

    fun normalise(s: String): String = asciiDigits(s.lowercase())
        .replace('।', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private val laterMarker = Regex("পরে|পর\\b|later|from now|after|এর মধ্যে")
    // No trailing \b after a Bengali alternative: the final matra ("া") is not a word character in every
    // regex engine, so "২ ঘণ্টা" matched nothing while "২০ মিনিট" did. The ASCII forms keep their \b.
    private val relativeMin = Regex("(\\d{1,3})\\s*(?:মিনিট|minutes?\\b|mins?\\b|minute\\b)")
    private val relativeMinEn = Regex("in\\s+(\\d{1,3})\\s*(?:minutes?|mins?)")
    private val relativeHour = Regex("(\\d{1,2})\\s*(?:ঘণ্টা|ঘন্টা|hours?\\b|hrs?\\b)")
    private val relativeSec = Regex("(\\d{1,3})\\s*(?:সেকেন্ড|seconds?\\b|secs?\\b)")

    private val clockHm = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})")
    private val clockBn = Regex("(\\d{1,2})\\s*(?:টা|টায়|টার|টায়|টায়|baje|o'?clock)")
    /** "৩টায়" can arrive with the vowel sign separated by the recogniser. */
    private val clockBnLoose = Regex("(\\d{1,2})\\s*ট\\S*")
    /** English "8 am" / "7pm" carry no clock word at all. */
    private val clockEn = Regex("(\\d{1,2})\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)")

    private val dayTomorrow = Regex("আগামীকাল|কালকে|আগামী কাল|tomorrow")
    private val dayAfter = Regex("পরশু|day after tomorrow")
    // No \\b around Bengali words: Java's word boundary is ASCII-only, so "\\bআজ\\b" never matches.
    private val dayToday = Regex("আজ|today")
    private val dayPlain = Regex("কাল")

    private val periodBn = mapOf(
        "ভোর" to Period.EARLY,
        "সকাল" to Period.MORNING,
        "দুপুর" to Period.NOON,
        "বিকাল" to Period.AFTERNOON,
        "বিকেল" to Period.AFTERNOON,
        "সন্ধ্যা" to Period.EVENING,
        "রাত" to Period.NIGHT,
    )
    private val periodEn = listOf("morning" to Period.MORNING, "noon" to Period.NOON, "afternoon" to Period.AFTERNOON,
        "evening" to Period.EVENING, "night" to Period.NIGHT)

    enum class Period { EARLY, MORNING, NOON, AFTERNOON, EVENING, NIGHT }

    /** null when the sentence carries no usable time. */
    fun parse(text: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): TimeGuess? {
        val t = normalise(text)
        if (t.isBlank()) return null

        // 1 — relative phrasing beats any clock reading
        if (laterMarker.containsMatchIn(t) || t.contains(" in ") || t.startsWith("in ")) {
            relativeSec.find(t)?.let { return relative(nowMillis, it.groupValues[1].toInt() * 1000L, it.value) }
            (relativeMin.find(t) ?: relativeMinEn.find(t))?.let {
                return relative(nowMillis, it.groupValues[1].toLong() * 60_000L, it.value)
            }
            relativeHour.find(t)?.let { return relative(nowMillis, it.groupValues[1].toLong() * 3_600_000L, it.value) }
        }
        (relativeMinEn.find(t))?.let { return relative(nowMillis, it.groupValues[1].toLong() * 60_000L, it.value) }

        // 2 — day words
        val dayOffset = when {
            dayAfter.containsMatchIn(t) -> 2
            dayTomorrow.containsMatchIn(t) -> 1
            dayToday.containsMatchIn(t) -> 0
            dayPlain.containsMatchIn(t) -> 1
            else -> 0
        }
        val explicitDay = dayAfter.containsMatchIn(t) || dayTomorrow.containsMatchIn(t) ||
            dayToday.containsMatchIn(t) || dayPlain.containsMatchIn(t)

        // 3 — period word
        val period = periodBn.entries.firstOrNull { t.contains(it.key) }?.value
            ?: periodEn.firstOrNull { t.contains(it.first) }?.second
        val explicitPeriod = period != null || t.contains("am") || t.contains("pm") || t.contains("রাতে")

        // 4 — clock. "রাতে" / "সন্ধ্যায়" without a number means the usual hour for that period; the
        // answer always prints the resolved time, so the convention is stated, never hidden.
        val clockHit = clock(t)
        if (clockHit == null && period == null) return null
        val (rawHour, minute) = clockHit ?: (defaultHour(period!!) to 0)
        val resolved = resolveHour(rawHour, minute, period, am = t.contains("am"), pm = t.contains("pm"))
        val nowZ = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val day = nowZ.toLocalDate().plusDays(dayOffset.toLong())

        var candidate = day.atTime(resolved.hour, resolved.minute).atZone(zone)
        // No day word + no period: "৩টায়" at 16:00 means 03:00 tomorrow, not 15:00 today.
        if (!explicitDay && !explicitPeriod && candidate.toInstant().toEpochMilli() <= nowMillis) {
            val plus12 = candidate.plusHours(12)
            candidate = if (plus12.toInstant().toEpochMilli() > nowMillis) plus12 else candidate.plusDays(1)
        }
        // Never in the past.
        while (candidate.toInstant().toEpochMilli() <= nowMillis + 1_000) candidate = candidate.plusDays(1)
        return TimeGuess(candidate.toInstant().toEpochMilli(), explicitDay, explicitPeriod, matched(text))
    }

    private data class Resolved(val hour: Int, val minute: Int)

    private fun clock(t: String): Pair<Int, Int>? {
        clockHm.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) return h to min
        }
        (clockBn.find(t) ?: clockBnLoose.find(t) ?: clockEn.find(t))?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            if (h in 0..23) return h to 0
        }
        return null
    }

    private fun resolveHour(raw: Int, minute: Int, period: Period?, am: Boolean, pm: Boolean): Resolved {
        val h = ((raw % 24) + 24) % 24
        val out = when (period) {
            Period.EARLY -> if (h == 12) 0 else h
            Period.MORNING -> if (h == 12) 0 else h
            Period.NOON -> if (h in 1..11) h + 12 else h
            Period.AFTERNOON -> if (h in 1..11) h + 12 else h
            Period.EVENING -> if (h in 1..11) h + 12 else h
            Period.NIGHT -> when (h) {
                12 -> 0
                in 6..11 -> h + 12
                else -> h
            }
            null -> when {
                am && h == 12 -> 0
                pm && h in 1..11 -> h + 12
                else -> h
            }
        }
        return Resolved(out, minute.coerceIn(0, 59))
    }

    /** The hour a bare period word means, in the way people actually use it. */
    fun defaultHour(period: Period?): Int = when (period) {
        Period.EARLY -> 5
        Period.MORNING -> 9
        Period.NOON -> 13
        Period.AFTERNOON -> 16
        Period.EVENING -> 19
        Period.NIGHT -> 21
        null -> 9
    }

    private fun relative(nowMillis: Long, delta: Long, matched: String): TimeGuess? {
        if (delta <= 0) return null
        return TimeGuess(nowMillis + delta, explicitDay = false, explicitPeriod = true, matched = matched)
    }

    /** The part of the original sentence that carried the time, for "…৩টায় — ঠিক আছে?" lines. */
    private fun matched(original: String): String {
        val t = normalise(original)
        val words = listOf("আগামীকাল", "পরশু", "আজকে", "আজ", "ভোর", "সকাল", "দুপুর", "বিকাল", "বিকেল", "সন্ধ্যা", "রাত",
            "টায়", "টা", "minutes", "minute", "hours", "hour", "pm", "am", "tomorrow", "today", "পরশু")
        val hit = words.firstOrNull { t.contains(it) }
        return hit ?: original.trim().take(20)
    }

    /** Bengali clock label: "আজ বিকাল ৩:০০", "কাল সকাল ৯:০০". English: "today 15:00". */
    fun label(atMillis: Long, nowMillis: Long, bengali: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        if (!bengali) {
            val day = when (days) {
                0L -> "today"
                1L -> "tomorrow"
                else -> "in $days days"
            }
            return "%s %02d:%02d".format(day, at.hour, at.minute)
        }
        val day = when (days) {
            0L -> "আজ"
            1L -> "কাল"
            2L -> "পরশু"
            else -> "আর $days দিন পর"
        }
        // Bengali clock convention: 3pm is বিকাল, 7pm is সন্ধ্যা, 9pm is রাত.
        val period = when (at.hour) {
            in 4..5 -> "ভোর"
            in 6..11 -> "সকাল"
            in 12..14 -> "দুপুর"
            in 15..17 -> "বিকাল"
            in 18..19 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val h12 = when (val h = at.hour % 12) { 0 -> 12; else -> h }
        return "$day $period ${bn(h12.toString())}:${bn(at.minute.toString().padStart(2, '0'))}"
    }

    /** ASCII → Bengali digits (labels and confirmations). */
    fun bn(s: String): String = buildString(s.length) {
        for (c in s) {
            if (c in '0'..'9') append(bnDigits[c - '0']) else append(c)
        }
    }
}

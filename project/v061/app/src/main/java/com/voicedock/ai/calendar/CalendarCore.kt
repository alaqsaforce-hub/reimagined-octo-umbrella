package com.voicedock.ai.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.3.4 — the calendar, split the way this project splits everything: the decisions and the words are pure and
 * tested, the network and the OAuth token live in [CalendarClient].
 *
 * Two things this file deliberately does **not** do:
 *  * invent a time. "কাল ৩টায়" is resolved by the same `TimeWords` grammar the reminders use; if no time was
 *    understood the answer asks instead of booking something at a made-up hour;
 *  * claim an event was added. [CalendarCore] only builds the request; the executor reports what the API said.
 */
data class CalEvent(
    val id: String,
    val title: String,
    /** Start in epoch millis; for an all-day event this is the start of that day. */
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val location: String = "",
    val calendar: String = "",
)

/** Which slice of time a question is about. */
enum class CalRange { TODAY, TOMORROW, WEEK, NAMED_DAY }

object CalendarCore {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val DAY = 24L * 60 * 60 * 1000

    /** Bengali and English weekday names, in [java.time.DayOfWeek] order (Monday first). */
    private val weekdays = listOf(
        java.time.DayOfWeek.MONDAY to listOf("সোমবার", "monday"),
        java.time.DayOfWeek.TUESDAY to listOf("মঙ্গলবার", "tuesday"),
        java.time.DayOfWeek.WEDNESDAY to listOf("বুধবার", "wednesday"),
        java.time.DayOfWeek.THURSDAY to listOf("বৃহস্পতিবার", "thursday"),
        java.time.DayOfWeek.FRIDAY to listOf("শুক্রবার", "friday"),
        java.time.DayOfWeek.SATURDAY to listOf("শনিবার", "saturday"),
        java.time.DayOfWeek.SUNDAY to listOf("রবিবার", "sunday"),
    )

    /** The weekday a sentence names, or null. Used so "শুক্রবারের ক্যালেন্ডার" is not silently read as today. */
    fun weekday(word: String): java.time.DayOfWeek? {
        val w = word.lowercase()
        return weekdays.firstOrNull { (_, names) -> names.any { w.contains(it) } }?.first
    }

    fun range(word: String): CalRange {
        val w = word.lowercase()
        return when {
            w.contains("আজ") || w.contains("today") -> CalRange.TODAY
            w.contains("সপ্তাহ") || w.contains("week") -> CalRange.WEEK
            w.contains("আগামীকাল") || w.contains("কালকে") || w.contains("কালক") || w.contains("কাল") ||
                w.contains("tomorrow") -> CalRange.TOMORROW
            weekday(w) != null -> CalRange.NAMED_DAY
            w.contains("পরশু") -> CalRange.NAMED_DAY
            else -> CalRange.TODAY
        }
    }

    /** Half-open window `[from, to)` in millis for a range. */
    fun window(
        range: CalRange,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        word: String = "",
    ): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from: LocalDate = when (range) {
            CalRange.TODAY -> today
            CalRange.TOMORROW -> today.plusDays(1)
            // A named weekday means its next occurrence, today included; "পরশু" (no weekday) means the day after.
            CalRange.NAMED_DAY -> {
                val day = weekday(word)
                if (day == null) {
                    today.plusDays(1)
                } else {
                    var d = today
                    var guard = 0
                    while (d.dayOfWeek != day && guard < 7) { d = d.plusDays(1); guard++ }
                    d
                }
            }
            CalRange.WEEK -> today
        }
        val days = if (range == CalRange.WEEK) 7L else 1L
        val startMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        return startMillis to startMillis + days * DAY
    }

    fun rangeLabel(
        range: CalRange,
        nowMillis: Long,
        bengali: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        word: String = "",
    ): String {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (range) {
            CalRange.TODAY -> (if (bengali) "আজ" else "Today") + " (" + dateLabel(today, bengali) + ")"
            CalRange.TOMORROW -> (if (bengali) "আগামীকাল" else "Tomorrow") + " (" + dateLabel(today.plusDays(1), bengali) + ")"
            CalRange.WEEK -> if (bengali) "আগামী ৭ দিনে" else "in the next 7 days"
            // A named weekday says the weekday back, because that is what the user asked about.
            CalRange.NAMED_DAY -> {
                val day = weekday(word)
                val date = if (day == null) {
                    today.plusDays(1)
                } else {
                    var d = today
                    var guard = 0
                    while (d.dayOfWeek != day && guard < 7) { d = d.plusDays(1); guard++ }
                    d
                }
                val name = weekdays.firstOrNull { it.first == date.dayOfWeek }?.second?.first().orEmpty()
                (if (bengali) name else name.replaceFirstChar { it.uppercase() }) + " (" + dateLabel(date, bengali) + ")"
            }
        }
    }

    fun dateLabel(d: LocalDate, bengali: Boolean): String = if (bengali) {
        "${bn(d.dayOfMonth)} ${monthBn(d.monthValue)}"
    } else {
        "${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
    }

    private fun monthBn(m: Int): String =
        listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")
            .getOrElse(m - 1) { "" }

    fun bn(v: Int): String = buildString {
        for (c in v.toString()) if (c in '0'..'9') append("০১২৩৪৫৬৭৮৯"[c - '0']) else append(c)
    }

    /** "সকাল ১০:০০ — মিটিং (১ ঘণ্টা, অফিস)" */
    fun line(e: CalEvent, bengali: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(e.startMillis).atZone(zone)
        val time = if (e.allDay) {
            if (bengali) "সারাদিন" else "all day"
        } else {
            clock(start.hour, start.minute, bengali)
        }
        val dur = if (e.allDay) "" else " (" + durationLabel(e.endMillis - e.startMillis, bengali) + ")"
        val where = if (e.location.isNotBlank()) ", ${e.location}" else ""
        return "• $time — ${e.title}$dur$where"
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** "09:30" in Bengali shows as "০৯:৩০" — the rest of the app speaks Bengali numerals, so times follow. */
    private fun clock(hour: Int, minute: Int, bengali: Boolean): String {
        val t = two(hour) + ":" + two(minute)
        if (!bengali) return t
        return t.map { if (it in '0'..'9') "০১২৩৪৫৬৭৮৯"[it - '0'] else it }.joinToString("")
    }

    fun durationLabel(ms: Long, bengali: Boolean): String {
        val min = (ms / 60000).coerceAtLeast(1)
        val h = min / 60
        val m = min % 60
        fun bn2(v: Long) = bn(v.toInt())
        return when {
            bengali && h > 0 && m > 0 -> "${bn2(h)} ঘণ্টা ${bn2(m)} মিনিট"
            bengali && h > 0 -> "${bn2(h)} ঘণ্টা"
            bengali -> "${bn2(m)} মিনিট"
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** The list answer. Empty is a real answer, not a failure. */
    fun list(events: List<CalEvent>, rangeLabel: String, bengali: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        fun bnText(b: String, e: String) = if (bengali) b else e
        if (events.isEmpty()) return bnText("$rangeLabel কোনো ইভেন্ট নেই।", "No events $rangeLabel.")
        val head = bnText(
            "$rangeLabel ${bn(events.size)}টা ইভেন্ট:",
            "${events.size} event(s) $rangeLabel:",
        )
        return head + "\n" + events.sortedBy { it.startMillis }.take(12).joinToString("\n") { line(it, bengali, zone) }
    }

    // ---- reading the API ---------------------------------------------------------------------------

    /** Parses `events.list` JSON. Missing end → start + 1 hour (Google omits it for some all-day cases). */
    fun parseEvents(json: String): List<CalEvent> = runCatching {
        val root = lenient.parseToJsonElement(json).jsonObject
        (root["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o: JsonObject = e.jsonObject
            val title = o["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val startObj = o["start"]?.jsonObject
            val endObj = o["end"]?.jsonObject
            val allDay = startObj?.get("date")?.jsonPrimitive?.contentOrNull != null
            val startMillis = millis(startObj) ?: return@mapNotNull null
            val endMillis = millis(endObj) ?: (startMillis + 60 * 60 * 1000)
            CalEvent(
                id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = title,
                startMillis = startMillis,
                endMillis = endMillis,
                allDay = allDay,
                location = o["location"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                calendar = o["organizer"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** A `dateTime` or (all-day) `date` object → millis. */
    private fun millis(o: JsonObject?): Long? {
        o ?: return null
        o["dateTime"]?.jsonPrimitive?.contentOrNull?.let { dt ->
            runCatching { java.time.OffsetDateTime.parse(dt).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { java.time.LocalDateTime.parse(dt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                .getOrNull()?.let { return it }
        }
        o["date"]?.jsonPrimitive?.contentOrNull?.let { d ->
            return runCatching { LocalDate.parse(d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
        return null
    }

    // ---- writing the API ---------------------------------------------------------------------------

    /** The request body for `events.insert`. Times are sent with the phone's zone so they mean what was said. */
    fun createBody(
        title: String,
        startMillis: Long,
        endMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        location: String = "",
        description: String = "",
    ): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        fun iso(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
        val tz = zone.id
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"summary\":").append(quote(title)).append(',')
        sb.append("\"start\":{\"dateTime\":").append(quote(iso(startMillis))).append(",\"timeZone\":").append(quote(tz)).append("},")
        sb.append("\"end\":{\"dateTime\":").append(quote(iso(endMillis))).append(",\"timeZone\":").append(quote(tz)).append("}")
        if (location.isNotBlank()) sb.append(",\"location\":").append(quote(location))
        if (description.isNotBlank()) sb.append(",\"description\":").append(quote(description))
        sb.append("}")
        return sb.toString()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    // ---- words ---------------------------------------------------------------------------------

    /** The confirmation card: exactly what will be booked, including the resolved time. */
    fun draftText(title: String, startMillis: Long, endMillis: Long, bengali: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = Instant.ofEpochMilli(startMillis).atZone(zone)
        val e = Instant.ofEpochMilli(endMillis).atZone(zone)
        val whenText = dateLabel(s.toLocalDate(), bengali) + " " + clock(s.hour, s.minute, bengali) +
            "–" + clock(e.hour, e.minute, bengali)
        return if (bengali) {
            "ক্যালেন্ডারে যোগ হবে: “$title” — $whenText (${durationLabel(endMillis - startMillis, true)})"
        } else {
            "Will be added to the calendar: “$title” — $whenText (${durationLabel(endMillis - startMillis, false)})"
        }
    }

    fun noTime(bengali: Boolean): String = if (bengali) {
        "কখন যোগ করবো ঠিক বুঝিনি — “কাল ৩টায় …” এভাবে সময় বলুন।"
    } else {
        "I did not catch the time — say it like “tomorrow at 3 …”."
    }

    fun noTitle(bengali: Boolean): String = if (bengali) {
        "কী যোগ করবো? ইভেন্টের নামটাও বলুন (যেমন “কাল ৩টায় ডাক্তারের অ্যাপয়েন্টমেন্ট”)।"
    } else {
        "What should I add? Say the title too (e.g. “dentist appointment tomorrow at 3”)."
    }

    fun added(title: String, bengali: Boolean): String = if (bengali) {
        "✓ ক্যালেন্ডারে যোগ হয়েছে: $title"
    } else {
        "✓ Added to the calendar: $title"
    }

    fun scopeMissing(bengali: Boolean): String = if (bengali) {
        "ক্যালেন্ডারের অনুমতি নেই — কানেক্টর স্ক্রিন에서 ‘ক্যালেন্ডার সংযুক্ত করুন’ চাপুন (Google অ্যাকাউন্ট লাগবে)।"
    } else {
        "No calendar permission — tap “Connect calendar” on the Connectors screen (a Google account is needed)."
    }

    fun failure(reason: String, bengali: Boolean): String {
        fun bnText(b: String, e: String) = if (bengali) b else e
        return when (reason) {
            "no_token" -> scopeMissing(bengali)
            "http_401", "http_403" -> bnText(
                "Google অনুমতি দেয়নি (${reason.removePrefix("http_")}) — কানেক্টরে আবার সংযোগ করুন।",
                "Google refused the request (${reason.removePrefix("http_")}) — reconnect in Connectors.",
            )
            "http_429" -> bnText("Google-এর কোটা শেষ — কিছুক্ষণ পরে আবার বলুন।", "Google's quota is used up — try again later.")
            "nonet" -> bnText("ইন্টারনেট নেই।", "No internet connection.")
            else -> bnText("ক্যালেন্ডার পাওয়া যায়নি ($reason)।", "The calendar could not be reached ($reason).")
        }
    }
}

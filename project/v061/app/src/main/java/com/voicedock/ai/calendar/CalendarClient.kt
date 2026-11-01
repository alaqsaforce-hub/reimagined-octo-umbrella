package com.voicedock.ai.calendar

import android.content.Context
import android.content.Intent
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v1.3.4 — the network half of the calendar: Google Calendar REST v3 against the user's **primary** calendar.
 *
 * Read and write, but only that: no calendar listing/creation, no deletion, no attendees (so nothing is emailed
 * to anyone by accident). The token comes from [com.voicedock.ai.connectors.GoogleAuth] — the same in-memory,
 * short-lived access token Gmail/Drive already use — and nothing is stored here.
 *
 * Every failure is a code the UI turns into a sentence with [CalendarCore.failure]; the app never reports an
 * event as added unless the API returned an id.
 */
object CalendarClient {

    const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar.events"
    private const val BASE = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    data class Outcome(val events: List<CalEvent> = emptyList(), val createdId: String? = null, val reason: String? = null) {
        val ok: Boolean get() = reason == null
    }

    /** Has the user granted the calendar scope in this session? */
    fun connected(): Boolean = !com.voicedock.ai.connectors.GoogleAuth.token.isNullOrBlank()

    fun list(fromMillis: Long, toMillis: Long, token: String? = com.voicedock.ai.connectors.GoogleAuth.token): Outcome {
        val t = token?.takeIf { it.isNotBlank() } ?: return Outcome(reason = "no_token")
        val url = "$BASE?" + listOf(
            "timeMin=" + enc(iso(fromMillis)),
            "timeMax=" + enc(iso(toMillis)),
            "singleEvents=true",
            "orderBy=startTime",
            "maxResults=25",
        ).joinToString("&")
        return try {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $t").get().build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) Outcome(reason = "http_${r.code}")
                else Outcome(events = CalendarCore.parseEvents(body))
            }
        } catch (e: IOException) {
            Outcome(reason = if (e.message?.contains("Unable to resolve host", true) == true) "nonet" else "io")
        } catch (e: Exception) {
            Outcome(reason = "io")
        }
    }

    fun create(
        title: String,
        startMillis: Long,
        endMillis: Long,
        token: String? = com.voicedock.ai.connectors.GoogleAuth.token,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Outcome {
        val t = token?.takeIf { it.isNotBlank() } ?: return Outcome(reason = "no_token")
        val payload = CalendarCore.createBody(title, startMillis, endMillis, zone)
        return try {
            val req = Request.Builder().url(BASE)
                .header("Authorization", "Bearer $t")
                .post(payload.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return Outcome(reason = "http_${r.code}")
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (id.isNullOrBlank()) Outcome(reason = "no_id") else Outcome(createdId = id)
            }
        } catch (e: IOException) {
            Outcome(reason = if (e.message?.contains("Unable to resolve host", true) == true) "nonet" else "io")
        } catch (e: Exception) {
            Outcome(reason = "io")
        }
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * The consent screen for the calendar scope. Uses the same Google Identity flow as the connectors screen;
     * it must be launched from an Activity, so the overlay hands the user a deep link to it instead of pretending
     * it can ask for permission in the background.
     */
    fun consentIntent(ctx: Context): Intent = Intent(ctx, com.voicedock.ai.ui.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(com.voicedock.ai.ui.MainActivity.EXTRA_SCREEN, "connectors")
}

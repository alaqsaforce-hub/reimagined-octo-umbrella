package com.voicedock.ai.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.voicedock.ai.events.VoiceDockNotificationListener

/**
 * v0.10.0 — "পরের গান" / "pause the music", for whatever player is actually playing.
 *
 * Android has no public "media player" API. The honest route is `MediaSessionManager.getActiveSessions()`:
 * it needs the notification-listener access the app already asks for (v0.8.0), and it covers Spotify,
 * YouTube Music, the stock player, podcasts and browsers alike. No package names, no per-app hacks.
 *
 * If no session exists, or the access was revoked, the outcome says so instead of pretending.
 */
enum class MediaAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

enum class MediaStrategy { MEDIA_SESSION, NONE }

/** One session as far as the decision layer cares. Pure data, so picking is unit-testable. */
data class MediaCandidate(val pkg: String, val playing: Boolean, val title: String = "")

data class MediaOutcome(
    val ok: Boolean,
    val strategy: MediaStrategy,
    /** Why it failed, or which package it acted on. */
    val detail: String = "",
    /** Track title if the player publishes one — shown, never invented. */
    val title: String = "",
)

/** Pure selection: prefer what is actually playing, then a session with a title, then the first one. */
object MediaPick {
    fun best(list: List<MediaCandidate>): Int {
        if (list.isEmpty()) return -1
        val playing = list.indexOfFirst { it.playing }
        if (playing >= 0) return playing
        val titled = list.indexOfFirst { it.title.isNotBlank() }
        return if (titled >= 0) titled else 0
    }
}

/**
 * Pure wording: spoken words → [MediaAction], and the answer shown afterwards.
 *
 * Bangla puts the object first ("পরের গান দাও"), so the keywords are matched anywhere in the sentence.
 * "গান চালাও" (start the music) and "গান বন্ধ করো" (stop it) are deliberately different actions.
 */
object MediaText {

    private val nextKeys = listOf("পরের গান", "পরবর্তী গান", "পরের ট্র্যাক", "next song", "next track", "next one", "skip song", "skip this")
    private val prevKeys = listOf("আগের গান", "পূর্বের গান", "আগের ট্র্যাক", "previous song", "previous track", "go back a song", "prev song", "last song")
    private val stopKeys = listOf("গান বন্ধ", "মিউজিক বন্ধ", "গান বন্ধ কর", "মিউজিক বন্ধ কর", "stop music", "stop the music", "stop the song")
    private val pauseKeys = listOf("গান থামাও", "মিউজিক থামাও", "থামিয়ে দাও", "পজ কর", "pause", "hold the music", "সাউন্ড বন্ধ")
    private val playKeys = listOf("গান চালাও", "গান বাজাও", "গান শুরু", "মিউজিক চালাও", "মিউজিক বাজাও", "play music", "play the song", "play song", "resume music", "resume the song", "গান দাও")
    private val toggleKeys = listOf("গান", "মিউজিক", "music", "media", "song", "প্লে", "play", "pause")

    fun contains(low: String, keys: List<String>) = keys.any { low.contains(it) }

    /** True when the sentence is about media at all (used by the parser's offline pass). */
    fun looksLikeMedia(low: String): Boolean = contains(low, toggleKeys) || contains(low, nextKeys) || contains(low, prevKeys) || contains(low, stopKeys)

    /**
     * The action a sentence asks for, or null when it is too vague to act on ("গান").
     * Order matters: "গান বন্ধ করো" is STOP, not PAUSE; "গান থামাও" is PAUSE.
     */
    fun action(query: String): MediaAction? {
        val low = query.lowercase().trim()
        // Closed tokens (what the parser writes into the slot, and what a model may return) first.
        when (low) {
            "play" -> return MediaAction.PLAY
            "pause" -> return MediaAction.PAUSE
            "toggle" -> return MediaAction.TOGGLE
            "next" -> return MediaAction.NEXT
            "previous", "prev" -> return MediaAction.PREVIOUS
            "stop" -> return MediaAction.STOP
        }
        return when {
            contains(low, nextKeys) -> MediaAction.NEXT
            contains(low, prevKeys) -> MediaAction.PREVIOUS
            contains(low, stopKeys) -> MediaAction.STOP
            contains(low, pauseKeys) -> MediaAction.PAUSE
            contains(low, playKeys) -> MediaAction.PLAY
            else -> null
        }
    }

    /** What undoing [action] means, or null when it cannot be undone honestly (e.g. NEXT). */
    fun inverse(action: MediaAction): MediaAction? = when (action) {
        MediaAction.PLAY -> MediaAction.PAUSE
        MediaAction.PAUSE -> MediaAction.PLAY
        MediaAction.TOGGLE -> MediaAction.TOGGLE
        MediaAction.PREVIOUS -> MediaAction.NEXT
        else -> null
    }

    /** Buttons offered when the sentence said only "গান" — each label is understood by [action]. */
    fun choices(): List<String> = listOf("গান চালাও", "গান থামাও", "পরের গান", "আগের গান")

    fun label(action: MediaAction): String = when (action) {
        MediaAction.PLAY -> "গান চালাও"
        MediaAction.PAUSE -> "গান থামাও"
        MediaAction.TOGGLE -> "গান"
        MediaAction.NEXT -> "পরের গান"
        MediaAction.PREVIOUS -> "আগের গান"
        MediaAction.STOP -> "গান বন্ধ করো"
    }

    fun done(action: MediaAction, bengali: Boolean, title: String): String {
        val what = when (action) {
            MediaAction.PLAY -> if (bengali) "চালু হলো" else "playing"
            MediaAction.PAUSE -> if (bengali) "থামানো হলো" else "paused"
            MediaAction.TOGGLE -> if (bengali) "টগল হলো" else "toggled"
            MediaAction.NEXT -> if (bengali) "পরের গান" else "next track"
            MediaAction.PREVIOUS -> if (bengali) "আগের গান" else "previous track"
            MediaAction.STOP -> if (bengali) "বন্ধ হলো" else "stopped"
        }
        if (title.isBlank()) return if (bengali) "$what ✓" else "$what ✓"
        return if (bengali) "$what ✓ — $title" else "$what ✓ — $title"
    }

    fun failed(bengali: Boolean, detail: String): String = when (detail) {
        "no_access" -> if (bengali)
            "মিউজিক নিয়ন্ত্রণ করতে নোটিফিকেশন অ্যাক্সেস দরকার — সেটিংস → কমান্ডে চালু করুন।"
        else
            "Controlling music needs notification access — enable it in Settings → Commands."
        "no_player" -> if (bengali)
            "এখন কোনো প্লেয়ার চলছে না — আগে গান চালু করুন।"
        else
            "No player is active right now — start one first."
        else -> if (bengali)
            "প্লেয়ারটি সাড়া দিল না (${detail.ifBlank { "unknown" }})."
        else
            "The player did not respond (${detail.ifBlank { "unknown" }})."
    }
}

/** The Android half: find the live session and send it one transport command. */
object MediaControl {

    private const val TAG = "VoiceDockMedia"

    fun hasAccess(ctx: Context): Boolean = com.voicedock.ai.notify.NotificationAccess.isGranted(ctx)

    fun perform(ctx: Context, action: MediaAction): MediaOutcome {
        if (!hasAccess(ctx)) return MediaOutcome(false, MediaStrategy.NONE, "no_access")
        val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return MediaOutcome(false, MediaStrategy.NONE, "no_service")

        val listener = ComponentName(ctx, VoiceDockNotificationListener::class.java)
        // getActiveSessions throws SecurityException when the listener is not enabled — report, never crash.
        val sessions = runCatching { mgr.getActiveSessions(listener) }.getOrElse { e ->
            Log.w(TAG, "getActiveSessions failed: ${e.javaClass.simpleName}")
            return MediaOutcome(
                false, MediaStrategy.NONE,
                if (e is SecurityException) "no_access" else (e.javaClass.simpleName ?: "error"),
            )
        }
        if (sessions.isEmpty()) return MediaOutcome(false, MediaStrategy.NONE, "no_player")

        val candidates = sessions.map { c ->
            MediaCandidate(
                pkg = c.packageName,
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            )
        }
        val index = MediaPick.best(candidates)
        val session = sessions[index]
        val controls = session.transportControls
            ?: return MediaOutcome(false, MediaStrategy.NONE, "no_transport")
        val title = candidates[index].title

        // A player that is paused and asked to pause again must not look like a success — check the state first.
        val playing = candidates[index].playing
        val resolved = when (action) {
            MediaAction.TOGGLE -> if (playing) MediaAction.PAUSE else MediaAction.PLAY
            else -> action
        }
        if ((resolved == MediaAction.PAUSE && !playing) || (resolved == MediaAction.PLAY && playing)) {
            // Already in the requested state: nothing to do, and saying "done" would be a lie.
            return MediaOutcome(true, MediaStrategy.MEDIA_SESSION, "already", title)
        }

        val sent = runCatching {
            when (resolved) {
                MediaAction.PLAY -> controls.play()
                MediaAction.PAUSE -> controls.pause()
                MediaAction.NEXT -> controls.skipToNext()
                MediaAction.PREVIOUS -> controls.skipToPrevious()
                MediaAction.STOP -> controls.stop()
                MediaAction.TOGGLE -> controls.play() // unreachable: resolved above
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "${resolved.name} failed: ${e.javaClass.simpleName}")
            false
        }
        return if (sent) {
            MediaOutcome(true, MediaStrategy.MEDIA_SESSION, session.packageName, title)
        } else {
            MediaOutcome(false, MediaStrategy.NONE, session.packageName)
        }
    }
}

package com.voicedock.ai.command

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v0.7.0 — one spoken sentence, turned into a structured command.
 *
 * The model is only ever allowed to answer with an [Intent] from this enum plus known [Slot]s; anything
 * else becomes [Intent.UNKNOWN], which the pipeline turns into a clarification instead of an action.
 * Everything in this package is pure Kotlin so the whole decision layer is unit-tested without a phone.
 */
enum class Intent(val id: String) {
    APP_LAUNCH("app.launch"),
    MEMORY_STORE("memory.store"),
    MEMORY_RECALL("memory.recall"),
    SPEAK("tts.speak"),
    ASK_AI("ask.ai"),
    // Declared now, executed in later phases: the parser can recognise them, the pipeline says so.
    CALL_PLACE("call.place"),
    CALL_ANSWER("call.answer"),
    CALL_DECLINE("call.decline"),
    MSG_DIGEST("msg.digest"),
    MSG_REPLY("msg.reply"),
    MSG_READ("msg.read"),
    MEDIA("media.control"),
    // v0.10.0: one step back for the actions that can honestly be reversed.
    UNDO("undo"),
    // v1.2.0: scheduled jobs — the first kind is a spoken reminder.
    REMINDER_ADD("reminder.add"),
    REMINDER_LIST("reminder.list"),
    // v1.2.1: send a message later, search the internet, search YouTube.
    MSG_SCHEDULE("msg.schedule"),
    WEB_SEARCH("web.search"),
    YT_SEARCH("yt.search"),
    // v1.3.0 device access: which SIM / my own number / who is calling / which chat instance / Termux.
    SIM_NUMBERS("sim.numbers"),
    CALLER_WHO("identity.who"),
    CHAT_OPEN("chat.open"),
    TERMUX_RUN("termux.run"),
    // v1.3.0 background task board.
    TASK_ADD("task.add"),
    TASK_LIST("task.list"),
    TASK_CANCEL("task.cancel"),
    // v1.3.1 voice-to-voice: the draft card is controlled by voice too, but only ever *explicitly*.
    DRAFT_INSERT("draft.insert"),
    DRAFT_DISCARD("draft.discard"),
    VOICE_OPEN("voice.open"),
    // v1.3.2 content summaries: one chat's roll-up from notifications, and a YouTube link's metadata.
    MSG_THREAD("msg.thread"),
    YT_SUMMARY("yt.summary"),
    /** v1.3.3 — the transcript panel of the video that is open on screen (accessibility pass). */
    YT_TRANSCRIPT("yt.transcript"),
    /** v1.3.4 — read the calendar, and add one event (confirmed first). */
    CAL_LIST("cal.list"),
    CAL_ADD("cal.add"),
    /** v1.4.0 — MCP: which tools the configured servers expose, and running one of them. */
    MCP_TOOLS("mcp.tools"),
    MCP_RUN("mcp.run"),
    UNKNOWN("unknown");

    companion object {
        /** Accepts "call.place", "CALL_PLACE", "callPlace" — models are inconsistent. */
        fun from(raw: String?): Intent {
            val k = raw?.trim()?.lowercase()?.replace('-', '_')?.replace('.', '_')?.replace(" ", "_") ?: return UNKNOWN
            return entries.firstOrNull { it.id.replace('.', '_') == k || it.name.lowercase() == k } ?: UNKNOWN
        }
    }
}

enum class Slot(val key: String) {
    APP("app"),
    CONTACT("contact"),
    TEXT("text"),
    QUERY("query"),
    /** v0.10.0 — which transport command: play / pause / next / previous / stop. */
    MEDIA("media"),
    /** v1.2.0 — when something should happen ("৩টায়", "২০ মিনিট পরে", "কাল সকাল ৯টা"). */
    TIME("time"),
    /** v1.3.0 — a SIM slot ("সিম ২"), so a call goes out on the SIM the user named. */
    SIM("sim"),
    /** v1.3.0 — which chat app / clone ("বিজনেস", "ক্লোন", "মেসেঞ্জার"). */
    CHAT("chat");

    companion object {
        fun from(raw: String?): Slot? {
            val k = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == k || it.name.lowercase() == k }
        }
    }
}

data class CommandSpec(
    val intent: Intent,
    val slots: Map<Slot, String> = emptyMap(),
    val confidence: Double = 0.0,
    val raw: String = "",
) {
    fun slot(s: Slot): String? = slots[s]?.takeIf { it.isNotBlank() }

    companion object {
        fun unknown(raw: String) = CommandSpec(Intent.UNKNOWN, emptyMap(), 0.0, raw)
        fun of(intent: Intent, raw: String, confidence: Double = 1.0, vararg slots: Pair<Slot, String>) =
            CommandSpec(intent, slots.toMap(), confidence, raw)
    }
}

/** Strict JSON → [CommandSpec]. Returns null on anything unexpected (never throws). */
object CommandJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Models love markdown fences and prose around the JSON; cut out the first object only. */
    fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return raw.substring(start, i + 1) }
            }
        }
        return null
    }

    fun parse(raw: String, original: String = raw): CommandSpec? {
        val body = extractObject(raw) ?: return null
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val intent = Intent.from(obj["intent"]?.jsonPrimitive?.contentOrNull)
        if (intent == Intent.UNKNOWN) return null
        val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.9
        val slots = HashMap<Slot, String>()
        (obj["slots"] as? JsonObject)?.forEach { (k, v) ->
            val slot = Slot.from(k) ?: return@forEach
            val text = v.jsonPrimitive.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty() && text != "null") slots[slot] = text
        }
        // A top-level "slots" is preferred, but tolerate flat objects ({"contact":"মা"}).
        if (slots.isEmpty()) obj.forEach { (k, v) ->
            val slot = Slot.from(k) ?: return@forEach
            val text = v.jsonPrimitive.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty() && text != "null") slots[slot] = text
        }
        return CommandSpec(intent, slots, confidence, original)
    }
}

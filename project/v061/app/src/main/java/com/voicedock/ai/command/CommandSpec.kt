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
    QUERY("query");

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

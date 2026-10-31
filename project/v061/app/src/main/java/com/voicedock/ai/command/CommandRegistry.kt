package com.voicedock.ai.command

/**
 * v0.7.0 — what each intent needs, whether it can hurt, and whether this build can actually do it.
 *
 * [available] is the honest switch: a v0.9 intent is recognised and explained, but the pipeline refuses
 * to pretend it ran. When a phase lands, only this table changes.
 */
data class IntentMeta(
    val requiredSlots: Set<Slot>,
    val destructive: Boolean,
    val available: Boolean,
    val helpBn: String,
    val helpEn: String,
)

object CommandRegistry {

    private val registry: Map<Intent, IntentMeta> = mapOf(
        Intent.APP_LAUNCH to IntentMeta(setOf(Slot.APP), false, true, "অ্যাপ খোলা", "Open an app"),
        Intent.MEMORY_STORE to IntentMeta(setOf(Slot.TEXT), false, true, "কথা মনে রাখা", "Remember something"),
        Intent.MEMORY_RECALL to IntentMeta(emptySet(), false, true, "মনে রাখা কথা বলা", "Recall memories"),
        Intent.SPEAK to IntentMeta(setOf(Slot.TEXT), false, true, "জোরে পড়ে শোনানো", "Read aloud"),
        Intent.ASK_AI to IntentMeta(emptySet(), false, true, "AI-কে জিজ্ঞাসা", "Ask the AI"),
        // Recognised now, executed later — the pipeline explains instead of failing silently.
        // v0.9.0: placing and declining confirm; answering does not (it is not destructive and it is
        // time-critical — a confirmation card would outlive the ring).
        Intent.CALL_PLACE to IntentMeta(setOf(Slot.CONTACT), true, true, "ফোন করা", "Place a call"),
        Intent.CALL_ANSWER to IntentMeta(emptySet(), false, true, "কল ধরা", "Answer a call"),
        Intent.CALL_DECLINE to IntentMeta(emptySet(), true, true, "কল কাটা", "Decline a call"),
        Intent.MSG_DIGEST to IntentMeta(emptySet(), false, true, "আজকের মেসেজের সারমর্ম", "Today's messages at a glance"),
        Intent.MSG_REPLY to IntentMeta(setOf(Slot.CONTACT, Slot.TEXT), true, true, "মেসেজে উত্তর পাঠানো", "Reply to a message"),
        Intent.MSG_READ to IntentMeta(emptySet(), false, true, "মেসেজ জোরে পড়া", "Read messages aloud"),
        // v0.10.0: media runs over the active MediaSession (any player), no per-app settings.
        Intent.MEDIA to IntentMeta(setOf(Slot.MEDIA), false, true, "মিউজিক নিয়ন্ত্রণ", "Control the music"),
        Intent.UNDO to IntentMeta(emptySet(), false, true, "শেষ কাজটি ফিরিয়ে নেওয়া", "Undo the last action"),
        Intent.UNKNOWN to IntentMeta(setOf(Slot.QUERY), false, false, "বোঝা যায়নি", "Not understood"),
    )

    fun meta(i: Intent): IntentMeta = registry[i] ?: registry.getValue(Intent.UNKNOWN)

    fun isAvailable(i: Intent): Boolean = meta(i).available

    fun isDestructive(i: Intent): Boolean = meta(i).destructive

    /** Required slots that the command did not provide, in registry order. */
    fun missing(spec: CommandSpec): List<Slot> =
        meta(spec.intent).requiredSlots.filter { spec.slot(it).isNullOrBlank() }

    fun help(i: Intent, bengali: Boolean): String = meta(i).let { if (bengali) it.helpBn else it.helpEn }

    /** Intents the user may actually pick from when a sentence was not understood. */
    fun offered(bengali: Boolean): List<Intent> =
        listOf(Intent.APP_LAUNCH, Intent.MEMORY_STORE, Intent.MEMORY_RECALL, Intent.ASK_AI)

    fun all(bengali: Boolean): List<String> = entries().map { help(it, bengali) }

    fun entries(): List<Intent> = registry.keys.filter { it != Intent.UNKNOWN }
}

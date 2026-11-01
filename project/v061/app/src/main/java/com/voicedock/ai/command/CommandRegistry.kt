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
        // v1.2.0: a reminder is harmless (it only speaks later), so it never asks for confirmation.
        Intent.REMINDER_ADD to IntentMeta(setOf(Slot.TEXT, Slot.TIME), false, true, "মনে করিয়ে দেওয়া", "Set a reminder"),
        Intent.REMINDER_LIST to IntentMeta(emptySet(), false, true, "রিমাইন্ডারের তালিকা", "List reminders"),
        // v1.2.1: sending cannot be taken back, so a scheduled message confirms once — and only then.
        Intent.MSG_SCHEDULE to IntentMeta(setOf(Slot.CONTACT, Slot.TEXT, Slot.TIME), true, true,
            "সময়মতো মেসেজ পাঠানো", "Send a message later"),
        Intent.WEB_SEARCH to IntentMeta(setOf(Slot.QUERY), false, true, "ইন্টারনেটে খোঁজা", "Search the internet"),
        Intent.YT_SEARCH to IntentMeta(setOf(Slot.QUERY), false, true, "ইউটিউবে খোঁজা", "Search YouTube"),
        // v1.3.0 — device access. Reading SIM/identity information is harmless; running a shell command and
        // handing the agent a background task can both change the phone, so they confirm.
        Intent.SIM_NUMBERS to IntentMeta(emptySet(), false, true, "নিজের নম্বর বলা", "Say my own numbers"),
        Intent.CALLER_WHO to IntentMeta(emptySet(), false, true, "কে ফোন করছে জানা", "Who is calling"),
        Intent.CHAT_OPEN to IntentMeta(emptySet(), false, true, "চ্যাট অ্যাপ/ক্লোন খোলা", "Open a chat app instance"),
        Intent.TERMUX_RUN to IntentMeta(setOf(Slot.TEXT), true, true, "টার্মাক্সে কমান্ড চালানো", "Run a Termux command"),
        Intent.TASK_ADD to IntentMeta(setOf(Slot.TEXT), true, true, "ব্যাকগ্রাউন্ডে কাজ দেওয়া", "Give a background task"),
        Intent.TASK_LIST to IntentMeta(emptySet(), false, true, "কাজের তালিকা", "List background tasks"),
        Intent.TASK_CANCEL to IntentMeta(emptySet(), true, true, "চলমান কাজ বাতিল", "Cancel the running task"),
        // v1.3.1 — putting a draft into another app is the user's own text, so it is not destructive; it is
        // also never automatic (see DraftCore.requiresExplicitInsert).
        Intent.DRAFT_INSERT to IntentMeta(emptySet(), false, true, "খসড়াটা কার্সরে বসানো", "Insert the draft"),
        Intent.DRAFT_DISCARD to IntentMeta(emptySet(), false, true, "খসড়া বাতিল করা", "Discard the draft"),
        Intent.VOICE_OPEN to IntentMeta(emptySet(), false, true, "ভয়েস মোড খোলা", "Open voice mode"),
        // v1.3.2 — reading a chat's notifications back is harmless; a YouTube link only reads a public page.
        Intent.MSG_THREAD to IntentMeta(setOf(Slot.CONTACT), false, true, "এক চ্যাটের সারমর্ম", "Summarise one chat"),
        Intent.YT_SUMMARY to IntentMeta(setOf(Slot.QUERY), false, true, "ইউটিউব লিংকের সারমর্ম", "Summarise a YouTube link"),
        Intent.YT_TRANSCRIPT to IntentMeta(emptySet(), false, true, "স্ক্রিনের ভিডিওর ট্রান্সক্রিপ্ট সারমর্ম", "Summarise the on-screen transcript"),
        // v1.3.4 — reading is harmless; writing to the user's calendar is not, so it confirms like a message.
        Intent.CAL_LIST to IntentMeta(emptySet(), false, true, "ক্যালেন্ডার দেখা", "Read the calendar"),
        Intent.CAL_ADD to IntentMeta(setOf(Slot.TEXT, Slot.TIME), true, true, "ক্যালেন্ডারে ইভেন্ট যোগ", "Add a calendar event"),
        // v1.4.0 — listing is harmless. Running a third-party tool is not, and the app cannot know what a tool
        // will do, so it always confirms (Fast Mode can opt in per the v1.0.0 rule).
        Intent.MCP_TOOLS to IntentMeta(emptySet(), false, true, "MCP টুল দেখা", "List MCP tools"),
        Intent.MCP_RUN to IntentMeta(setOf(Slot.TEXT), true, true, "MCP টুল চালানো", "Run an MCP tool"),
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

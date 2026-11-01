package com.voicedock.ai.command

/** One button in a clarification / confirmation card. */
data class Choice(val label: String, val intent: Intent, val slots: Map<Slot, String> = emptyMap())

/**
 * v0.7.0 — the decision layer, pure.
 *
 * Order is deliberate and is the safety contract of the whole assistant:
 *   1. did we understand it?         no  → clarify (never guess)
 *   2. can this build do it?         no  → say which version will, offer an alternative
 *   3. is a required slot missing?   yes → clarify with candidate buttons
 *   4. is it destructive?            yes → confirm once
 *   5. otherwise execute
 */
sealed interface CommandDecision {
    data class Clarify(val question: String, val choices: List<Choice>) : CommandDecision
    data class Confirm(val question: String, val confirm: Choice, val cancel: Choice) : CommandDecision
    data object Execute : CommandDecision
}

object CommandPipeline {

    const val MIN_CONFIDENCE = 0.7

    /**
     * @param candidates possible values for a missing slot (e.g. installed apps matching "whats"),
     *                   supplied by the caller because only it can see the phone.
     */
    fun decide(
        spec: CommandSpec,
        bengali: Boolean,
        candidates: Map<Slot, List<String>> = emptyMap(),
        /**
         * Which intents this build can actually run. Defaults to the registry; injected only by tests so
         * step 2 ("understood, but not in this version") stays covered now that every intent ships.
         */
        isAvailable: (Intent) -> Boolean = CommandRegistry::isAvailable,
        /**
         * v1.4.1 — the app decides per *command*, not just per intent: `mcp.run` confirms only when the tool is
         * not read-only. Tests keep the old behaviour by using the default.
         */
        needsConfirm: (Intent, CommandSpec) -> Boolean = { i, _ -> CommandRegistry.isDestructive(i) },
    ): CommandDecision {
        fun bn(b: String, e: String) = if (bengali) b else e

        // 1 — not understood, or too unsure
        if (spec.intent == Intent.UNKNOWN || spec.confidence < MIN_CONFIDENCE) {
            val choices = CommandRegistry.offered(bengali).map { Choice(CommandRegistry.help(it, bengali), it) }
            return CommandDecision.Clarify(bn("বুঝতে পারিনি — কী করতে চাও?", "I did not catch that — what should I do?"), choices)
        }

        // 2 — recognised, but this version cannot act on it yet
        if (!isAvailable(spec.intent)) {
            val help = CommandRegistry.help(spec.intent, bengali)
            return CommandDecision.Clarify(
                bn("$help — এই ভার্সনে এখনো চালু হয়নি।", "$help — not enabled in this version yet."),
                listOf(
                    Choice(bn("AI-কে জিজ্ঞেস করো", "Ask the AI instead"), Intent.ASK_AI, mapOf(Slot.QUERY to spec.raw)),
                    Choice(bn("বাতিল", "Cancel"), Intent.UNKNOWN),
                ),
            )
        }

        // 3 — missing information, with buttons when the phone knows the options
        val missing = CommandRegistry.missing(spec)
        if (missing.isNotEmpty()) {
            val slot = missing.first()
            val options = candidates[slot].orEmpty().take(4)
            val question = when (slot) {
                Slot.APP -> bn("কোন অ্যাপ?", "Which app?")
                Slot.CONTACT -> bn("কাকে?", "Which contact?")
                Slot.TEXT -> if (spec.intent == Intent.REMINDER_ADD)
                    bn("কী মনে করিয়ে দেব?", "Remind you about what?")
                else bn("কী মনে রাখবো? আবার বলো।", "What should I remember? Say it again.")
                Slot.QUERY -> bn("কী সম্পর্কে?", "About what?")
                Slot.MEDIA -> bn("গানের কী করবো?", "What should I do with the music?")
                Slot.TIME -> bn("কখন মনে করিয়ে দেব? যেমন ‘৩টায়’ বা ‘২০ মিনিট পরে’", "When should I remind you? e.g. ‘at 3’ or ‘in 20 minutes’")
                // v1.3.0 — these two only ever arrive from a statement the parser already understood, so a
                // missing value is not a question the user can answer with a button.
                Slot.SIM -> bn("কোন সিম দিয়ে?", "On which SIM?")
                Slot.CHAT -> bn("কোন অ্যাপ/ইনস্ট্যান্স?", "Which app/instance?")
            }
            val choices = if (options.isEmpty()) {
                listOf(Choice(bn("বাতিল", "Cancel"), Intent.UNKNOWN))
            } else {
                options.map { Choice(it, spec.intent, spec.slots + (slot to it)) }
            }
            return CommandDecision.Clarify(question, choices)
        }

        // 4 — irreversible: always confirm once
        if (needsConfirm(spec.intent, spec)) {
            val describe = describe(spec, bengali)
            return CommandDecision.Confirm(
                question = bn("নিশ্চিত? $describe", "Are you sure? $describe"),
                confirm = Choice(bn("হ্যাঁ, করো", "Yes, do it"), spec.intent, spec.slots),
                cancel = Choice(bn("বাতিল", "Cancel"), Intent.UNKNOWN),
            )
        }

        // 5
        return CommandDecision.Execute
    }

    /** Human-readable echo of what will happen — used in the confirmation card and the log. */
    fun describe(spec: CommandSpec, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        return when (spec.intent) {
            Intent.APP_LAUNCH -> bn("‘${spec.slot(Slot.APP).orEmpty()}’ খুলবে", "open ‘${spec.slot(Slot.APP).orEmpty()}’")
            Intent.MEMORY_STORE -> bn("মনে রাখবো: ${spec.slot(Slot.TEXT).orEmpty()}", "remember: ${spec.slot(Slot.TEXT).orEmpty()}")
            Intent.MEMORY_RECALL -> bn("মনে রাখা কথা দেখাবে", "show your memories")
            Intent.SPEAK -> bn("জোরে পড়বে: ${spec.slot(Slot.TEXT).orEmpty().take(60)}", "read aloud: ${spec.slot(Slot.TEXT).orEmpty().take(60)}")
            Intent.ASK_AI -> bn("AI-কে জিজ্ঞেস করবে", "ask the AI")
            Intent.CALL_PLACE -> bn("‘${spec.slot(Slot.CONTACT).orEmpty()}’ কে ফোন করবে", "call ‘${spec.slot(Slot.CONTACT).orEmpty()}’")
            Intent.CALL_ANSWER -> bn("কল ধরবে", "answer the call")
            Intent.CALL_DECLINE -> bn("কল কাটবে", "decline the call")
            Intent.MSG_DIGEST -> bn("মেসেজের সারমর্ম দেবে", "summarise today's messages")
            Intent.MSG_REPLY -> bn(
                "‘${spec.slot(Slot.CONTACT).orEmpty()}’ কে পাঠাবে: “${spec.slot(Slot.TEXT).orEmpty().take(60)}”",
                "send to ‘${spec.slot(Slot.CONTACT).orEmpty()}’: “${spec.slot(Slot.TEXT).orEmpty().take(60)}”",
            )
            Intent.MSG_READ -> bn("মেসেজ পড়ে শোনাবে", "read messages aloud")
            Intent.MEDIA -> {
                val a = spec.slot(Slot.MEDIA).orEmpty()
                val what = com.voicedock.ai.media.MediaText.action(a)?.let { com.voicedock.ai.media.MediaText.label(it) }
                bn("মিউজিক: ${what ?: "নিয়ন্ত্রণ"}", "media: ${what ?: (a.ifBlank { "control" })}")
            }
            Intent.UNDO -> bn("শেষ কাজটি ফিরিয়ে নেবে", "undo the last action")
            Intent.SIM_NUMBERS -> bn("সিম ও নিজের নম্বর দেখাবে", "show your SIMs and own numbers")
            Intent.CALLER_WHO -> bn("কে ফোন করছে দেখাবে", "show who is calling")
            Intent.CHAT_OPEN -> bn("চ্যাট অ্যাপ খুলবে: ${spec.slot(Slot.CHAT).orEmpty()}", "open a chat app: ${spec.slot(Slot.CHAT).orEmpty()}")
            Intent.TERMUX_RUN -> bn("টার্মাক্সে চালাবে: ${spec.slot(Slot.TEXT).orEmpty().take(60)}", "run in Termux: ${spec.slot(Slot.TEXT).orEmpty().take(60)}")
            Intent.TASK_ADD -> bn("ব্যাকগ্রাউন্ডে কাজ নেবে: ${spec.slot(Slot.TEXT).orEmpty().take(60)}", "take a background task: ${spec.slot(Slot.TEXT).orEmpty().take(60)}")
            Intent.TASK_LIST -> bn("কাজের তালিকা দেখাবে", "list background tasks")
            Intent.TASK_CANCEL -> bn("চলমান কাজ বাতিল করবে", "cancel the running task")
            Intent.DRAFT_INSERT -> bn("খসড়া কার্সরে বসাবে", "insert the draft")
            Intent.DRAFT_DISCARD -> bn("খসড়া বাতিল করবে", "discard the draft")
            Intent.VOICE_OPEN -> bn("ভয়েস মোড খুলবে", "open voice mode")
            Intent.MSG_THREAD -> bn("‘${spec.slot(Slot.CONTACT).orEmpty()}’-এর মেসেজের সারমর্ম দেবে",
                "summarise the chat with ‘${spec.slot(Slot.CONTACT).orEmpty()}’")
            Intent.YT_SUMMARY -> bn("ইউটিউব লিংকটার সারমর্ম দেবে", "summarise the YouTube link")
            Intent.YT_TRANSCRIPT -> bn("স্ক্রিনে খোলা ভিডিওর ট্রান্সক্রিপ্ট পড়ে সারমর্ম দেবে",
                "read the on-screen transcript and summarise it")
            Intent.CAL_LIST -> bn("ক্যালেন্ডার দেখাবে (${spec.slot(Slot.TIME).orEmpty().ifBlank { "আজ" }})",
                "read the calendar (${spec.slot(Slot.TIME).orEmpty().ifBlank { "today" }})")
            Intent.MCP_TOOLS -> bn("MCP সার্ভারগুলোর টুল দেখাবে", "list the tools the MCP servers expose")
            Intent.MCP_RUN -> bn("MCP টুল চালাবে: “${spec.slot(Slot.TEXT).orEmpty().take(50)}”",
                "run an MCP tool: “${spec.slot(Slot.TEXT).orEmpty().take(50)}”")
            Intent.CAL_ADD -> bn("ক্যালেন্ডারে যোগ করবে: “${spec.slot(Slot.TEXT).orEmpty().take(50)}” (${spec.slot(Slot.TIME).orEmpty()})",
                "add to the calendar: “${spec.slot(Slot.TEXT).orEmpty().take(50)}” (${spec.slot(Slot.TIME).orEmpty()})")
            Intent.REMINDER_ADD -> bn(
                "‘${spec.slot(Slot.TEXT).orEmpty()}’ — ${spec.slot(Slot.TIME).orEmpty()} মনে করিয়ে দেবে",
                "remind you to ‘${spec.slot(Slot.TEXT).orEmpty()}’ — ${spec.slot(Slot.TIME).orEmpty()}",
            )
            Intent.REMINDER_LIST -> bn("রিমাইন্ডারগুলোর তালিকা দেখাবে", "list your reminders")
            Intent.MSG_SCHEDULE -> bn(
                "${spec.slot(Slot.TIME).orEmpty()} ‘${spec.slot(Slot.CONTACT).orEmpty()}’ কে পাঠাবে: “${spec.slot(Slot.TEXT).orEmpty().take(60)}”",
                "send ‘${spec.slot(Slot.CONTACT).orEmpty()}’ at ${spec.slot(Slot.TIME).orEmpty()}: “${spec.slot(Slot.TEXT).orEmpty().take(60)}”",
            )
            Intent.WEB_SEARCH -> bn("‘${spec.slot(Slot.QUERY).orEmpty()}’ খুঁজবে", "search for ‘${spec.slot(Slot.QUERY).orEmpty()}’")
            Intent.YT_SEARCH -> bn("ইউটিউবে ‘${spec.slot(Slot.QUERY).orEmpty()}’ খুঁজবে", "search YouTube for ‘${spec.slot(Slot.QUERY).orEmpty()}’")
            Intent.UNKNOWN -> bn("কিছু না", "nothing")
        }
    }
}

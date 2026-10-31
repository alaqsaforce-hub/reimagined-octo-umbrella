package com.voicedock.ai.skills

/**
 * v1.1.0 — one honest list of what the assistant can do **today** and what is planned.
 *
 * Every entry mirrors something real in the code (or names the release that will add it). The settings
 * screen shows this list with ✓ / ○ next to each row, so "it can do X" is always checkable instead of a
 * marketing claim. Adding a skill means adding a row here *and* the four edits of the command layer
 * (Intent → Registry → executor → Parser), or implementing the integration it names.
 */
enum class SkillGroup(val titleBn: String, val titleEn: String) {
    CALLS("কল", "Calls"),
    MESSAGES("মেসেজ", "Messages"),
    MEDIA("মিডিয়া", "Media"),
    DEVICE("ডিভাইস ও অ্যাপ", "Device & apps"),
    KNOWLEDGE("জ্ঞান ও মেমরি", "Knowledge & memory"),
    AUTOMATION("অটোমেশন", "Automation"),
    INTEGRATIONS("ইন্টিগ্রেশন", "Integrations"),
}

data class Skill(
    val id: String,
    val nameBn: String,
    val nameEn: String,
    val group: SkillGroup,
    /** true = works in this build. false = planned; [noteBn]/[noteEn] say when/what is missing. */
    val ready: Boolean,
    val needsBn: String = "",
    val needsEn: String = "",
    val noteBn: String = "",
    val noteEn: String = "",
)

object Skills {

    private val list: List<Skill> = listOf(
        // ---- calls (v0.9.0) ----
        Skill("call.place", "নাম বলে ফোন করা", "Call by name", SkillGroup.CALLS, true,
            "কলের অনুমতি + কন্টাক্ট", "call + contacts permission"),
        Skill("call.answer", "কল ধরা (হোয়াটসঅ্যাপ কলসহ)", "Answer a call (incl. WhatsApp)", SkillGroup.CALLS, true,
            "ANSWER_PHONE_CALLS, অথবা রিং নোটিফিকেশনের বাটন", "ANSWER_PHONE_CALLS, or the ringing notification"),
        Skill("call.decline", "কল কাটা", "Decline a call", SkillGroup.CALLS, true, "কলের অনুমতি", "call permission"),
        Skill("call.last_sender", "“শেষ যে মেসেজ দিয়েছে” তাকে ফোন", "Call whoever messaged last", SkillGroup.CALLS, true,
            "নোটিফিকেশন অ্যাক্সেস", "notification access"),

        // ---- messages (v0.8.0) ----
        Skill("msg.digest", "আজ কে কে মেসেজ দিয়েছে", "Today's messages at a glance", SkillGroup.MESSAGES, true,
            "নোটিফিকেশন অ্যাক্সেস", "notification access"),
        Skill("msg.read", "মেসেজ জোরে পড়া", "Read messages aloud", SkillGroup.MESSAGES, true,
            "নোটিফিকেশন অ্যাক্সেস + TTS", "notification access + TTS"),
        Skill("msg.reply", "নোটিফিকেশন থেকে সরাসরি উত্তর", "Reply from the notification", SkillGroup.MESSAGES, true,
            "অ্যাপটি Direct Reply দিলে", "app must offer Direct Reply"),
        Skill("msg.schedule", "সময়মতো পরে মেসেজ পাঠানো", "Send a message later, on time", SkillGroup.MESSAGES, false,
            noteBn = "পরিকল্পিত: v1.2.0 (সময়সূচি + ব্যাকগ্রাউন্ড)",
            noteEn = "planned: v1.2.0 (queue + background)"),

        // ---- media (v0.10.0) ----
        Skill("media.control", "গান চালানো/থামানো/পরের গান", "Play, pause, next, previous", SkillGroup.MEDIA, true,
            "নোটিফিকেশন অ্যাক্সেস", "notification access"),
        Skill("yt.navigate", "ইউটিউবে প্লে/পজ/পরের ভিডিও", "YouTube play/pause/next", SkillGroup.MEDIA, true,
            "ইউটিউব চললে একই মিডিয়া কমান্ড কাজ করে", "works while YouTube is playing (same media command)"),
        Skill("yt.search", "ইউটিউবে ভিডিও খোঁজা ও চালানো", "Search YouTube and play", SkillGroup.MEDIA, false,
            noteBn = "পরিকল্পিত: v1.2.0 (সার্চ ইনটেন্ট)", noteEn = "planned: v1.2.0 (search intent)"),
        Skill("yt.summary", "ইউটিউব লিংকের সারমর্ম", "Summarise a YouTube link", SkillGroup.MEDIA, false,
            noteBn = "পরিকল্পিত: v1.3.0 (ক্যাপশন + AI)", noteEn = "planned: v1.3.0 (captions + AI)"),

        // ---- device ----
        Skill("app.launch", "অ্যাপ খোলা", "Open an app", SkillGroup.DEVICE, true),
        Skill("screen.read", "স্ক্রিনের লেখা পড়া", "Read the current screen", SkillGroup.DEVICE, true,
            "অ্যাক্সেসিবিলিটি", "accessibility"),
        Skill("agent.os", "স্ক্রিনে চাপ দেওয়া/স্ক্রল (এজেন্ট)", "Tap and scroll on screen (agent)", SkillGroup.DEVICE, true,
            "অ্যাক্সেসিবিলিটি", "accessibility"),
        Skill("social.summary", "হোয়াটসঅ্যাপ/ফেসবুক/ইনস্টাগ্রামের সারমর্ম", "WhatsApp / Facebook / Instagram summaries",
            SkillGroup.DEVICE, false,
            noteBn = "পরিকল্পিত: v1.3.0 — প্রথমে হোয়াটসঅ্যাপ (নোটিফিকেশন আছে), বাকিগুলো অ্যাক্সেসিবিলিটি দিয়ে",
            noteEn = "planned: v1.3.0 — WhatsApp first (notifications exist), the rest via accessibility"),

        // ---- knowledge ----
        Skill("memory.store", "কথা মনে রাখা", "Remember something", SkillGroup.KNOWLEDGE, true),
        Skill("memory.recall", "মনে রাখা কথা বলা", "Recall memories", SkillGroup.KNOWLEDGE, true),
        Skill("ask.ai", "AI-কে প্রশ্ন (ফ্রি-ফার্স্ট)", "Ask the AI (free-first)", SkillGroup.KNOWLEDGE, true,
            "একটি প্রোভাইডার সাইন-ইন/কি", "one signed-in provider or key"),
        Skill("kb.search", "নলেজ বেসে খোঁজা", "Search the knowledge base", SkillGroup.KNOWLEDGE, true),
        Skill("web.search", "ইন্টারনেটে খোঁজা", "Search the internet", SkillGroup.KNOWLEDGE, false,
            noteBn = "পরিকল্পিত: v1.2.0 (Brave/Tavily/SearxNG key)", noteEn = "planned: v1.2.0 (Brave/Tavily/SearxNG key)"),
        Skill("local.model", "ফোন/LAN-এর লোকাল মডেল দিয়ে কাজ", "Work through a local model", SkillGroup.KNOWLEDGE, true,
            "Ollama / llama.cpp সার্ভার (Settings → AI → Custom)", "an Ollama / llama.cpp server (Settings → AI → Custom)"),

        // ---- automation ----
        Skill("job.remind", "নির্দিষ্ট সময়ে মনে করিয়ে দেওয়া", "Remind me at a time", SkillGroup.AUTOMATION, false,
            noteBn = "পরিকল্পিত: v1.2.0", noteEn = "planned: v1.2.0"),
        Skill("job.chain", "কয়েক ধাপের কাজ একসঙ্গে", "Multi-step chains", SkillGroup.AUTOMATION, true,
            noteBn = "এখন একটি চেইন: “শেষ যে মেসেজ দিয়েছে” → কল/উত্তর", noteEn = "one chain today: “whoever messaged last” → call/reply"),
        Skill("undo", "শেষ কাজ ফিরিয়ে নেওয়া", "Undo the last action", SkillGroup.AUTOMATION, true),
        Skill("mcp.client", "ফ্রি MCP সার্ভারের সাথে কাজ (GitHub, ফাইল)", "Work with free MCP servers (GitHub, files)",
            SkillGroup.AUTOMATION, false,
            noteBn = "পরিকল্পিত: v1.4.0 (MCP ক্লায়েন্ট; আজ GitHub/Gmail/Drive/ফাইল আলাদা কানেক্টর হিসেবে আছে)",
            noteEn = "planned: v1.4.0 (MCP client; GitHub/Gmail/Drive/files exist today as separate connectors)"),

        // ---- integrations that already exist as connectors ----
        Skill("github", "GitHub — রিপো, ফাইল পড়া", "GitHub — repos, file reading", SkillGroup.INTEGRATIONS, true,
            "GitHub টোকেন", "a GitHub token"),
        Skill("mail", "মেইল পড়া (Gmail IMAP / Google)", "Read mail (Gmail IMAP / Google)", SkillGroup.INTEGRATIONS, true,
            "অ্যাপ পাসওয়ার্ড বা Google সাইন-ইন", "an app password or Google sign-in"),
        Skill("drive", "ড্রাইভে খোঁজা ও পড়া", "Search and read Drive files", SkillGroup.INTEGRATIONS, true,
            "Google সাইন-ইন", "Google sign-in"),
        Skill("calendar", "ক্যালেন্ডার দেখা/যোগ করা", "Read and add calendar events", SkillGroup.INTEGRATIONS, false,
            noteBn = "পরিকল্পিত: v1.3.0 (Calendar API)", noteEn = "planned: v1.3.0 (Calendar API)"),
        Skill("vps", "আমার VPS সার্ভারে কমান্ড", "Run commands on my VPS", SkillGroup.INTEGRATIONS, false,
            noteBn = "পরিকল্পিত: v1.4.0 (SSH; কী স্টোর করা হবে Keystore-এ)", noteEn = "planned: v1.4.0 (SSH; key stored in the Keystore)"),
    )

    fun all(): List<Skill> = list
    fun ready(): List<Skill> = list.filter { it.ready }
    fun planned(): List<Skill> = list.filterNot { it.ready }
    fun byGroup(): Map<SkillGroup, List<Skill>> = list.groupBy { it.group }
    fun of(id: String): Skill? = list.firstOrNull { it.id == id }

    /** "12 / 24 skills work in this build" — the number the settings screen shows. */
    fun summary(bengali: Boolean): String =
        if (bengali) "${ready().size} / ${list.size} স্কিল এখন কাজ করে (বাকিগুলো পরিকল্পিত)"
        else "${ready().size} / ${list.size} skills work in this build (the rest are planned)"
}

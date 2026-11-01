package com.voicedock.ai.device

/**
 * v1.3.0 — "একাধিক হোয়াটসঅ্যাপ": normal, Business, and the Dual-Messenger/work-profile clone.
 *
 * How a second WhatsApp actually exists on Android: it runs in a **second user profile** (work profile, Samsung
 * Dual Messenger, Xiaomi Dual Apps, Island…). That is why `getInstalledPackages()` shows one copy while the
 * launcher shows two — `LauncherApps.getActivityList(package, user)` shows both, with the profile as the thing
 * that distinguishes them. [ChatInstance] therefore carries the user handle as its identity, and the picker
> asks instead of guessing when there are several.
 */
enum class ChatKind(val pkg: String, val labelEn: String, val labelBn: String, val isWhatsApp: Boolean) {
    WHATSAPP("com.whatsapp", "WhatsApp", "হোয়াটসঅ্যাপ", true),
    WHATSAPP_BUSINESS("com.whatsapp.w4b", "WhatsApp Business", "হোয়াটসঅ্যাপ বিজনেস", true),
    MESSENGER("com.facebook.orca", "Messenger", "মেসেঞ্জার", false),
    TELEGRAM("org.telegram.messenger", "Telegram", "টেলিগ্রাম", false),
    SIGNAL("org.thoughtcrime.securesms", "Signal", "সিগন্যাল", false),
    VIBER("com.viber.voip", "Viber", "ভাইবার", false),
    UNKNOWN("", "App", "অ্যাপ", false);

    companion object {
        fun of(pkg: String): ChatKind = entries.firstOrNull { it.pkg == pkg && pkg.isNotBlank() } ?: UNKNOWN
        val chatPackages: List<String> = entries.mapNotNull { it.pkg.takeIf { p -> p.isNotBlank() } }
    }
}

data class ChatInstance(
    val kind: ChatKind,
    val packageName: String,
    /** Android user handle: 0 = the phone's owner profile, anything else = a clone / work profile. */
    val userId: Int,
    /** Label as the launcher shows it ("WhatsApp", "WhatsApp Business", "Work", "Dual Messenger"). */
    val label: String,
    /** True when this instance lives outside the owner profile — i.e. the clone the user is asking about. */
    val cloned: Boolean,
    val installed: Boolean = true,
) {
    val key: String get() = "$packageName@$userId"
    val title: String get() = if (cloned) "$label (ক্লোন)" else label
}

object ChatInstanceCore {

    /** One entry per distinct (package, profile) — duplicates from the launcher are collapsed. */
    fun dedupe(instances: List<ChatInstance>): List<ChatInstance> =
        instances.filter { it.installed }
            .distinctBy { it.key }
            .sortedWith(compareBy<ChatInstance>({ it.kind.ordinal }, { it.userId }))

    fun whatsapps(instances: List<ChatInstance>): List<ChatInstance> =
        dedupe(instances).filter { it.kind.isWhatsApp }

    /**
     * Which instance a spoken request means.
     *
     * @param hint the words around "হোয়াটসঅ্যাপ" — "বিজনেস", "ক্লোন", "দ্বিতীয়", "work"
     * @return the instance, or null when the request is ambiguous (the caller then asks with the list)
     */
    fun pick(instances: List<ChatInstance>, hint: String? = null): ChatInstance? {
        val list = dedupe(instances)
        if (list.isEmpty()) return null
        val h = hint?.lowercase().orEmpty()
        // 1 — narrow to the app that was actually named, so "হোয়াটসঅ্যাপ" is never confused with Messenger.
        val scoped = when {
            h.contains("মেসেঞ্জার") || h.contains("messenger") -> list.filter { it.kind == ChatKind.MESSENGER }
            h.contains("হোয়াটসঅ্যাপ") || h.contains("whatsapp") -> list.filter { it.kind.isWhatsApp }
            h.contains("টেলিগ্রাম") || h.contains("telegram") -> list.filter { it.kind == ChatKind.TELEGRAM }
            else -> list
        }.ifEmpty { list }
        // 2 — an explicitly named flavour wins.
        if (h.contains("বিজনেস") || h.contains("business")) {
            scoped.firstOrNull { it.kind == ChatKind.WHATSAPP_BUSINESS }?.let { return it }
        }
        if (listOf("ক্লোন", "clone", "দ্বিতীয়", "dual", "work", "অফিস").any { h.contains(it) }) {
            scoped.firstOrNull { it.cloned }?.let { return it }
        }
        // 3 — one candidate is not a guess. Business and clones only win when named, so the plain app is the
        //     default; two different apps both matching means we ask instead of picking.
        if (scoped.size == 1) return scoped.single()
        return scoped.filter { !it.cloned && it.kind != ChatKind.WHATSAPP_BUSINESS }.singleOrNull()
    }

    /** Labels for the clarification card. */
    fun choices(instances: List<ChatInstance>): List<String> = dedupe(instances).map { it.title }.take(4)

    fun describe(instance: ChatInstance?, bengali: Boolean): String = when {
        instance == null -> if (bengali) "কোন হোয়াটসঅ্যাপ ঠিক করা নেই" else "no WhatsApp instance chosen"
        bengali -> "${instance.title} থেকে পাঠানো হবে"
        else -> "will use ${instance.title}"
    }

    /** What the app can honestly promise per instance. A clone has no notification-reply path we can rely on. */
    fun canDirectReply(instance: ChatInstance): Boolean = instance.kind.isWhatsApp && !instance.cloned
}

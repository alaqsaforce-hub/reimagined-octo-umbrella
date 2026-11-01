package com.voicedock.ai.permissions

/**
 * v1.2.0 — one list of every permission/access the app declares, what it unlocks, and how it is verified.
 *
 * Why this exists: v1.1.0's Settings section had nine hand-written rows. Hand-written lists drift — a new
 * `uses-permission` in the manifest, or a permission the code checks but the UI never offered, becomes a
 * silent gap ("why is the wake word not working?" → nobody ever asked for the microphone). The catalog is
 * the single source of truth for the UI **and** for [ManifestAudit], which fails the build's tests if the
 * manifest and the catalog disagree in either direction.
 *
 * A [Access.NORMAL] row is a permission Android grants at install time — it is listed so the audit can
 * account for it, and so the Settings screen can honestly show "নিজে থেকেই আছে" instead of pretending it
 * needs a button.
 */
enum class Access {
    /** Runtime permission: one system dialog, requestable in-app. */
    RUNTIME,
    /** Special access: only the system settings screen can grant it. */
    SPECIAL,
    /** Granted at install time; nothing for the user to do. */
    NORMAL,
}

/** A feature the assistant offers. A feature is *usable* only when every one of its accesses is granted. */
object Feature {
    const val MIC = "mic"
    const val WAKE = "wake"
    const val BUBBLE = "bubble"
    const val SCREEN = "screen"
    const val MESSAGES = "messages"
    const val CALLS = "calls"
    const val MEDIA = "media"
    const val REMINDERS = "reminders"
    const val BACKGROUND = "background"

    val all = listOf(MIC, WAKE, BUBBLE, SCREEN, MESSAGES, CALLS, MEDIA, REMINDERS, BACKGROUND)

    fun label(f: String, bengali: Boolean): String = when (f) {
        MIC -> if (bengali) "মাইক/ডিক্টেশন" else "Microphone / dictation"
        WAKE -> if (bengali) "‘শাহরুখ’ ডাক" else "‘Shahrukh’ wake word"
        BUBBLE -> if (bengali) "ভাসমান বাবল" else "Floating bubble"
        SCREEN -> if (bengali) "স্ক্রিন পড়া" else "Reading the screen"
        MESSAGES -> if (bengali) "মেসেজ পড়া/উত্তর" else "Messages read/reply"
        CALLS -> if (bengali) "কল করা/ধরা" else "Placing/answering calls"
        MEDIA -> if (bengali) "মিউজিক নিয়ন্ত্রণ" else "Media control"
        REMINDERS -> if (bengali) "মনে করিয়ে দেওয়া" else "Reminders"
        BACKGROUND -> if (bengali) "ব্যাকগ্রাউন্ডে চালু থাকা" else "Running in the background"
        else -> f
    }
}

data class PermissionItem(
    /** Stable id used by settings + tests. Equals the Android permission name when there is one. */
    val key: String,
    /** The `android.permission.*` constant, when an OS permission exists. */
    val permission: String?,
    val access: Access,
    val titleBn: String,
    val titleEn: String,
    val whyBn: String,
    val whyEn: String,
    val features: List<String>,
)

object PermissionCatalog {

    // Permission names as constants so a typo cannot slip past the manifest audit.
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val CALL_PHONE = "android.permission.CALL_PHONE"
    private const val ANSWER_PHONE_CALLS = "android.permission.ANSWER_PHONE_CALLS"
    private const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    private const val REQUEST_IGNORE_BATTERY = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    private const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
    private const val RECEIVE_BOOT_COMPLETED = "android.permission.RECEIVE_BOOT_COMPLETED"
    private const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
    private const val FOREGROUND_SERVICE_MICROPHONE = "android.permission.FOREGROUND_SERVICE_MICROPHONE"
    private const val FOREGROUND_SERVICE_SPECIAL_USE = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
    private const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    private const val VIBRATE = "android.permission.VIBRATE"
    private const val INTERNET = "android.permission.INTERNET"

    /** Special-access keys (no runtime dialog exists for these). */
    const val ACCESSIBILITY = "accessibility"
    const val NOTIFICATION_LISTENER = "notification_listener"
    const val ASSISTANT_ROLE = "assistant_role"

    val items: List<PermissionItem> = listOf(
        PermissionItem(
            RECORD_AUDIO, RECORD_AUDIO, Access.RUNTIME,
            "মাইক্রোফোন", "Microphone",
            "ডিক্টেশন, ‘শাহরুখ’ ডাক ও ভয়েস কমান্ডের জন্য", "dictation, the ‘শাহরুখ’ wake word and voice commands",
            listOf(Feature.MIC, Feature.WAKE),
        ),
        PermissionItem(
            POST_NOTIFICATIONS, POST_NOTIFICATIONS, Access.RUNTIME,
            "নোটিফিকেশন দেখানো", "Show notifications",
            "শুনছে কি না বোঝা, রিমাইন্ডার ও কলের বাটন দেখানোর জন্য", "knowing it is listening, plus reminders and call buttons",
            listOf(Feature.WAKE, Feature.BACKGROUND, Feature.REMINDERS, Feature.CALLS),
        ),
        PermissionItem(
            SYSTEM_ALERT_WINDOW, SYSTEM_ALERT_WINDOW, Access.SPECIAL,
            "বাবল (অন্য অ্যাপের উপরে)", "Bubble (over other apps)",
            "সহকারীর ভাসমান বাবল দেখানোর জন্য", "showing the floating assistant over other apps",
            listOf(Feature.BUBBLE, Feature.WAKE),
        ),
        PermissionItem(
            ACCESSIBILITY, null, Access.SPECIAL,
            "অ্যাক্সেসিবิลিটি", "Accessibility",
            "স্ক্রিন পড়া, ট্যাপ/স্ক্রল করা ও লেখা বসানোর জন্য", "reading the screen, tapping/scrolling and inserting text",
            listOf(Feature.SCREEN, Feature.MESSAGES, Feature.CALLS),
        ),
        PermissionItem(
            NOTIFICATION_LISTENER, null, Access.SPECIAL,
            "নোটিফিকেশন অ্যাক্সেস", "Notification access",
            "মেসেজ পড়া/উত্তর দেওয়া, কল ধরা ও মিউজিক নিয়ন্ত্রণের জন্য", "reading/replying to messages, answering calls and media control",
            listOf(Feature.MESSAGES, Feature.MEDIA, Feature.CALLS),
        ),
        PermissionItem(
            READ_CONTACTS, READ_CONTACTS, Access.RUNTIME,
            "কন্টাক্ট পড়া", "Read contacts",
            "‘রহিমকে ফোন করো’ — নাম থেকে নম্বর বের করার জন্য", "turning ‘call Rahim’ into a number",
            listOf(Feature.CALLS, Feature.MESSAGES),
        ),
        PermissionItem(
            CALL_PHONE, CALL_PHONE, Access.RUNTIME,
            "সরাসরি কল করা", "Place calls directly",
            "নম্বর বসিয়ে ডায়ালার খোলার বদলে নিজেই কল করার জন্য", "calling instead of only opening the dialer prefilled",
            listOf(Feature.CALLS),
        ),
        PermissionItem(
            ANSWER_PHONE_CALLS, ANSWER_PHONE_CALLS, Access.RUNTIME,
            "কল ধরা/কাটা", "Answer/decline calls",
            "‘কল ধরো’ বললে সত্যিই ধরার জন্য", "actually answering when you say ‘pick up’",
            listOf(Feature.CALLS),
        ),
        PermissionItem(
            READ_PHONE_STATE, READ_PHONE_STATE, Access.RUNTIME,
            "কলের অবস্থা", "Phone state",
            "কল আসছে কি না বোঝা ও কল চলাকালে কথা না বলার জন্য", "knowing a call is ringing, and staying quiet during one",
            listOf(Feature.CALLS),
        ),
        PermissionItem(
            REQUEST_IGNORE_BATTERY, REQUEST_IGNORE_BATTERY, Access.SPECIAL,
            "ব্যাটারি অপটিমাইজেশন বাদ", "Battery exemption",
            "স্ক্রিন বন্ধ থাকলেও ‘শাহরুখ’ শোনার জন্য", "hearing ‘শাহরুখ’ while the screen is off",
            listOf(Feature.WAKE, Feature.BACKGROUND, Feature.REMINDERS),
        ),
        PermissionItem(
            SCHEDULE_EXACT_ALARM, SCHEDULE_EXACT_ALARM, Access.SPECIAL,
            "ঠিক সময়ে রিমাইন্ডার", "Exact-time reminders",
            "‘৩টায় মনে করিয়ে দাও’ কথাটি মিনিটে-মিনিটে সময়মতো বলার জন্য (না থাকলে কয়েক মিনিট দেরি হতে পারে)",
            "reminders firing to the minute (without it they can be a few minutes late)",
            listOf(Feature.REMINDERS),
        ),
        PermissionItem(
            ASSISTANT_ROLE, null, Access.SPECIAL,
            "সহকারী রোল", "Assistant role",
            "হোম বাটন চেপে ধরে/সিস্টেম ‘অ্যাসিস্ট্যান্ট’ ডাকলে খোলার জন্য", "being opened by the system assistant gesture",
            listOf(Feature.BUBBLE, Feature.MIC),
        ),
        PermissionItem(
            RECEIVE_BOOT_COMPLETED, RECEIVE_BOOT_COMPLETED, Access.RUNTIME,
            "রিস্টার্টের পর আবার চালু", "Restart after reboot",
            "ফোন বন্ধ করে চালু করলেও রিমাইন্ডার ও ডাক শোনা চালু থাকার জন্য", "reminders and listening surviving a reboot",
            listOf(Feature.BACKGROUND, Feature.REMINDERS),
        ),
        // Install-time permissions: listed so the manifest audit can account for every declared line.
        PermissionItem(FOREGROUND_SERVICE, FOREGROUND_SERVICE, Access.NORMAL, "ফোরগ্রাউন্ড সার্ভিস", "Foreground service",
            "শোনা ও বাবল চালু রাখার জন্য", "keeping listening and the bubble alive", listOf(Feature.BACKGROUND)),
        PermissionItem(FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MICROPHONE, Access.NORMAL, "ফোরগ্রাউন্ডে মাইক", "Microphone in foreground",
            "মাইক খোলা থাকলে নোটিফিকেশন দেখানোর জন্য", "the required notification while the mic is open", listOf(Feature.WAKE, Feature.MIC)),
        PermissionItem(FOREGROUND_SERVICE_SPECIAL_USE, FOREGROUND_SERVICE_SPECIAL_USE, Access.NORMAL, "ফোরগ্রাউন্ড (special use)", "Foreground (special use)",
            "Android 14+ এ বাবল সার্ভিসের জন্য", "the bubble service on Android 14+", listOf(Feature.BUBBLE)),
        PermissionItem(FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_DATA_SYNC, Access.NORMAL, "ফোরগ্রাউন্ড (data sync)", "Foreground (data sync)",
            "Android 10–13 এ বাবল সার্ভিসের জন্য", "the bubble service on Android 10–13", listOf(Feature.BUBBLE)),
        PermissionItem(VIBRATE, VIBRATE, Access.NORMAL, "কম্পন", "Vibration",
            "ডাক শুনলে একবার কম্পন", "one short buzz when it hears the wake word", listOf(Feature.WAKE)),
        PermissionItem(INTERNET, INTERNET, Access.NORMAL, "ইন্টারনেট", "Internet",
            "AI প্রোভাইডার ও অনলাইন স্পিচ", "AI providers and online speech", listOf(Feature.MIC, Feature.MESSAGES)),
    )

    fun byKey(key: String): PermissionItem? = items.firstOrNull { it.key == key }

    /** Everything that needs a user decision (runtime dialogs + system settings screens). */
    val actionable: List<PermissionItem> get() = items.filter { it.access != Access.NORMAL }

    fun runtimeNames(): List<String> = items.filter { it.access == Access.RUNTIME }.mapNotNull { it.permission }

    fun forFeature(feature: String): List<PermissionItem> = items.filter { feature in it.features }

    /**
     * v1.2.0 gap detection, used by [ManifestAudit] and by the Settings screen:
     * permissions declared in the manifest that no catalog row explains.
     */
    fun unclassified(declared: Collection<String>): List<String> {
        val known = items.mapNotNull { it.permission }.toSet()
        return declared.filter { it.startsWith("android.permission.") && it !in known }.sorted()
    }

    /** Catalog rows that claim an OS permission the manifest never declares — they could never be granted. */
    fun undeclared(declared: Collection<String>): List<String> =
        items.mapNotNull { it.permission }.filter { it !in declared }.sorted()

    /** Total number of accesses that need the user, for the summary line. */
    val actionableCount: Int get() = actionable.size
}

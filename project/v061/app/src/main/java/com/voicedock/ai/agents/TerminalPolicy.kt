package com.voicedock.ai.agents

/**
 * v1.3.0 — "টার্মাক্সে …" / the proot Ubuntu inside it / node-based CLIs like OpenCode.
 *
 * A shell handed to a voice assistant is the single most dangerous feature this app can have, so the rule is:
 * **off by default, read-only commands only without a confirmation, everything else confirmed every time, and a
 * short list that is refused outright even when confirmed.** The policy is pure so the rule cannot be changed by
 * accident in one of three call sites, and it is unit-tested.
 */
enum class TerminalDecision { ALLOW, CONFIRM, BLOCK }

data class TerminalVerdict(
    val decision: TerminalDecision,
    val reasonBn: String,
    val reasonEn: String,
    /** i18n keys for the confirmation card button. */
    val dangerous: Boolean = false,
)

object TerminalPolicy {

    /**
     * Read-only commands that cannot change the phone, the files or the network. Only these run silently.
     * Deliberately no `find`, no `grep -r` outside home, no `top` (it needs a TTY here).
     */
    private val readOnly = listOf(
        "ls", "pwd", "whoami", "id", "uname -a", "date", "uptime", "df -h", "free -h",
        "termux-info", "getprop ro.product.model", "cat /proc/cpuinfo", "which ", "echo ",
        "git status", "git log --oneline -5", "python3 --version", "node --version", "proot-distro list",
        "pkg list-installed",
    )

    /**
     * Refused even with a confirmation: a spoken sentence is too easily misheard to be allowed to wipe a device.
     * Not a security boundary (the user could type it in Termux) — a guard against an accent and a microphone.
     */
    private val neverAllowed = listOf(
        "rm -rf /", "rm -rf /*", "rm -rf /data", "rm -rf /sdcard", "mkfs", "dd if=", "dd of=/dev/",
        ":(){", "shutdown", "reboot", "reboot -p", "> /dev/sda", "chmod -R 777 /", "chown -R /",
        "pm uninstall", "pm clear", "settings put global", "am force-stop com.voicedock", "wipe",
        "mv /* ", "curl | sh", "wget | sh", "curl|sh", "wget|sh",
    )

    private val risky = listOf(
        "rm ", "mv ", "cp ", "chmod", "chown", "sudo", "su ", "apt ", "pkg install", "pip install",
        "npm install", "git push", "git reset --hard", "kill ", "pkill", "export ", ">", ">>", "&&",
        "||", "systemctl", "service ", "proot-distro install", "proot-distro remove",
    )

    fun decide(command: String, enabled: Boolean, confirmed: Boolean): TerminalVerdict {
        val cmd = command.trim()
        val lower = cmd.lowercase()
        fun bn(b: String, e: String) = TerminalVerdict(TerminalDecision.BLOCK, b, e)
        return when {
            cmd.isBlank() -> bn("কোনো কমান্ড নেই।", "No command.")
            !enabled -> bn(
                "টার্মাক্স কমান্ড বন্ধ আছে — সেটিংস → ডিভাইস অ্যাক্সেসে চালু করুন (নিরাপত্তার জন্য ডিফল্ট বন্ধ)।",
                "Termux commands are off — enable them in Settings → Device access (off by default for safety).",
            )
            neverAllowed.any { lower.contains(it) } -> TerminalVerdict(
                TerminalDecision.BLOCK,
                "এই কমান্ডটি কখনোই চালাব না (ফোন নষ্ট হতে পারে): ${cmd.take(60)}",
                "I will never run this one (it can destroy the phone): ${cmd.take(60)}",
                dangerous = true,
            )
            !enabled -> bn("বন্ধ", "off")
            readOnly.any { lower == it || lower.startsWith(it) } && !lower.contains(";") ->
                TerminalVerdict(TerminalDecision.ALLOW, "পড়ার কমান্ড — চালানো হচ্ছে", "read-only command — running")
            risky.any { lower.contains(it) } || !readOnly.any { lower.startsWith(it) } -> if (confirmed) {
                TerminalVerdict(TerminalDecision.ALLOW, "আপনি অনুমতি দিয়েছেন — চালাচ্ছি", "you confirmed — running", dangerous = true)
            } else {
                TerminalVerdict(
                    TerminalDecision.CONFIRM,
                    "এই কমান্ড ফোনের কিছু বদলাতে পারে: ${cmd.take(70)} — নিশ্চিত?",
                    "This command can change the phone: ${cmd.take(70)} — are you sure?",
                    dangerous = true,
                )
            }
            else -> TerminalVerdict(TerminalDecision.CONFIRM, "নিশ্চিত?", "Confirm?", dangerous = true)
        }
    }

    /** What the assistant says after dispatching. Termux reports results separately, so this never claims success. */
    fun dispatchedText(command: String, termuxPresent: Boolean, bengali: Boolean): String = when {
        !termuxPresent -> if (bengali)
            "টার্মাক্স ইনস্টল নেই — Play Store/F-Droid থেকে ইনস্টল করে আবার বলুন।"
        else "Termux is not installed — install it (Play Store / F-Droid) and ask again."
        bengali -> "টার্মাক্সে পাঠানো হলো: ${command.take(60)}\n(ফলাফল টার্মাক্সে দেখুন — Termux-এ ‘allow-external-apps’ চালু থাকতে হবে।)"
        else -> "Sent to Termux: ${command.take(60)}\n(Watch the result in Termux — its ‘allow-external-apps’ must be on.)"
    }
}

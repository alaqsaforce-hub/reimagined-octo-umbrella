package com.voicedock.ai.context

/**
 * Pure, unit-tested rules for the user-maintained screen-probe blocklist.
 *
 * This closes the open P0 privacy item from the v0.6 handoff: *"No user-maintained pre-traversal
 * blocklist"*. The list is checked BEFORE `ScreenProbe` walks a single node, so a blocked app's
 * window is never traversed, never counted and never buffered.
 *
 * A blocked entry also covers its children: blocking `com.example.bank` blocks
 * `com.example.bank.ui.MainActivity`-style packages/services of that app family, because Android
 * package names are hierarchical. Matching is case-insensitive.
 */
object ProbePolicy {

    /** Hard cap so the settings blob (and the probe's per-tap check) stays small. */
    const val MAX_PACKAGES = 100

    /** Android package names: at least two dot-separated identifiers, no spaces, no wildcards. */
    private val PACKAGE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    /** Trim/lower-case user input and return it only when it is a plausible package name. */
    fun normalize(raw: String): String? {
        val t = raw.trim().lowercase()
        if (t.length !in 3..128) return null
        if (t.any { it.isWhitespace() }) return null
        if (!PACKAGE.matches(t)) return null
        return t
    }

    /** Add an entry (idempotent, case-insensitive, capped). Invalid input leaves the list untouched. */
    fun add(existing: List<String>, raw: String): List<String> {
        val pkg = normalize(raw) ?: return existing
        if (existing.any { it.equals(pkg, ignoreCase = true) }) return existing
        if (existing.size >= MAX_PACKAGES) return existing
        return existing + pkg
    }

    /** Remove one entry (case-insensitive). */
    fun remove(existing: List<String>, pkg: String): List<String> =
        existing.filterNot { it.equals(pkg.trim(), ignoreCase = true) }

    /** Sanitise a list loaded from disk: normalise, dedupe and cap. */
    fun sanitize(existing: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in existing) {
            val pkg = normalize(raw) ?: continue
            out += pkg
            if (out.size >= MAX_PACKAGES) break
        }
        return out.toList()
    }

    /**
     * True when [pkg] is the blocked entry itself or a child of it
     * (`com.bank` blocks `com.bank.wallet`, not `com.bankrupt.app`).
     */
    fun matches(pkg: String, blocked: Collection<String>): Boolean {
        if (pkg.isBlank() || blocked.isEmpty()) return false
        val p = pkg.lowercase()
        return blocked.any { b ->
            val entry = b.trim().lowercase()
            entry.isNotEmpty() && (p == entry || p.startsWith("$entry."))
        }
    }

    /**
     * The single pre-traversal gate used by [com.voicedock.ai.device.ScreenProbe]:
     * user blocklist first, then the built-in finance-package and finance-label rules.
     */
    fun isBlocked(
        pkg: String,
        appLabel: String = "",
        userBlocked: Collection<String> = emptyList(),
        oracle: SafetyOracle = DefaultSafetyOracle,
    ): Boolean = matches(pkg, userBlocked) || oracle.blockedPackage(pkg) || oracle.financeLabel(appLabel)

    /** True when the user blocklist (not the built-in rules) is what refuses this package. */
    fun userBlockedOnly(
        pkg: String,
        appLabel: String = "",
        userBlocked: Collection<String> = emptyList(),
        oracle: SafetyOracle = DefaultSafetyOracle,
    ): Boolean = matches(pkg, userBlocked) && !oracle.blockedPackage(pkg) && !oracle.financeLabel(appLabel)
}

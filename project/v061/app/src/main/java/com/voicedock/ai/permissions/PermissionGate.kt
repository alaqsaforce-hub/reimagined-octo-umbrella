package com.voicedock.ai.permissions

/**
 * v1.2.0 — "which feature is blocked, by exactly which missing permission, and what is the next step".
 *
 * Pure: the caller supplies a `granted(key)` probe, so every rule (including the Android-13 notification
 * exception and the "a feature needs *all* of its permissions" rule) is unit-tested without a device.
 */
object PermissionGate {

    data class Summary(
        val granted: Int,
        val total: Int,
        val blockedFeatures: List<String>,
    ) {
        val allGranted: Boolean get() = blockedFeatures.isEmpty()
    }

    /** Missing accesses for one feature, catalog order. */
    fun missing(feature: String, granted: (String) -> Boolean): List<PermissionItem> =
        PermissionCatalog.forFeature(feature).filter { it.access != Access.NORMAL && !granted(it.key) }

    fun ready(feature: String, granted: (String) -> Boolean): Boolean = missing(feature, granted).isEmpty()

    /** Features the user would notice as broken right now. */
    fun blockedFeatures(granted: (String) -> Boolean): List<String> = Feature.all.filter { !ready(it, granted) }

    /**
     * Runtime permissions worth asking for in one dialog.
     *
     * POST_NOTIFICATIONS only exists from Android 13; asking for it on 12 and below throws/ignores, so it
     * is filtered by [sdkInt]. Nothing else is version-dependent in this catalog.
     */
    fun requestable(granted: (String) -> Boolean, sdkInt: Int): List<String> =
        PermissionCatalog.items
            .filter { it.access == Access.RUNTIME }
            .filter { it.permission != null && !granted(it.key) }
            .filter { it.permission != "android.permission.POST_NOTIFICATIONS" || sdkInt >= 33 }
            // RECEIVE_BOOT_COMPLETED is install-time on every supported version; never show a dialog for it.
            .filter { it.permission != "android.permission.RECEIVE_BOOT_COMPLETED" }
            .mapNotNull { it.permission }

    /** Accesses that only the system settings app can grant, in the order worth doing them. */
    fun specialMissing(granted: (String) -> Boolean): List<PermissionItem> =
        PermissionCatalog.actionable.filter { it.access == Access.SPECIAL && !granted(it.key) }

    fun summary(granted: (String) -> Boolean): Summary {
        val all = PermissionCatalog.actionable
        return Summary(
            granted = all.count { granted(it.key) },
            total = all.size,
            blockedFeatures = blockedFeatures(granted),
        )
    }

    /** One sentence naming the thing to fix first — never a wall of ✗. */
    fun nextStep(granted: (String) -> Boolean, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val s = summary(granted)
        if (s.allGranted) return bn("✓ সব অনুমতি দেওয়া আছে।", "✓ Every permission is granted.")
        val blocking = s.blockedFeatures.firstOrNull()
        val first = blocking?.let { missing(it, granted).firstOrNull() }
        return when {
            first != null -> bn(
                "এখন ${Feature.label(blocking!!, true)} বন্ধ আছে — প্রথমে ‘${first.titleBn}’ দিন।",
                "${Feature.label(blocking!!, false)} is off — start by granting ‘${first.titleEn}’.",
            )
            else -> bn("কিছু অনুমতি বাকি আছে।", "Some permissions are still missing.")
        }
    }
}

/** A declared-permission list vs the catalog — the permanent answer to "is any permission unaccounted for?". */
object ManifestAudit {

    data class Report(val unclassified: List<String>, val undeclared: List<String>) {
        val clean: Boolean get() = unclassified.isEmpty() && undeclared.isEmpty()
        fun describe(): String = when {
            clean -> "every declared permission is classified and every catalog row is declared"
            else -> buildString {
                if (unclassified.isNotEmpty()) append("unclassified in manifest: ${unclassified.joinToString(", ")}. ")
                if (undeclared.isNotEmpty()) append("catalog rows with no manifest permission: ${undeclared.joinToString(", ")}.")
            }.trim()
        }
    }

    /** Extracts `android:name` of every `<uses-permission>` from manifest XML text. */
    fun declaredFrom(xml: String): List<String> {
        val re = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
        return re.findAll(xml).map { it.groupValues[1] }.distinct().sorted().toList()
    }

    fun report(declared: Collection<String>): Report =
        Report(PermissionCatalog.unclassified(declared), PermissionCatalog.undeclared(declared))
}

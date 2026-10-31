package com.voicedock.ai.ai

/**
 * v0.6.7 — "is an AI provider usable right now, and if not, exactly why".
 *
 * v0.6.6's bubble said "needs an AI key" for every failure, including a saved key with no model
 * chosen, cloud consent switched off, or paid gating. That reads like "the AI is not connected to
 * the bubble" and gives the user nothing to act on. This object is pure so the decision and the
 * wording are unit-tested; [AiSetup.readiness] only supplies the Android-side inputs.
 */
object AiReadinessCore {

    data class Decision(val ok: Boolean, val code: String?, val provider: AiProvider?)

    /**
     * @param order providers in the user's order (free-first), never empty
     * @param reason [AiPolicy.blockReason] result for a provider (null = usable)
     * @param hasKey whether a credential is stored for that provider
     */
    fun decide(
        order: List<AiProvider>,
        reason: (AiProvider) -> String?,
        hasKey: (AiProvider) -> Boolean,
    ): Decision {
        if (order.isEmpty()) return Decision(false, "no_key", null)
        order.firstOrNull { reason(it) == null }?.let { return Decision(true, null, it) }
        // Nothing is usable. Explain the most advanced setup the user got to: a provider that
        // actually holds a credential is far more informative than "no key anywhere".
        val withKey = order.firstOrNull { hasKey(it) }
        val subject = withKey ?: order.firstOrNull { it != AiProvider.CUSTOM } ?: order.first()
        return Decision(false, reason(subject) ?: "no_key", subject)
    }

    /** One actionable sentence. Never says "key" when a key exists. */
    fun message(code: String?, providerLabel: String, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        return when (code) {
            null -> bn("✓ AI প্রস্তুত", "✓ AI ready")
            "no_key" -> bn(
                "$providerLabel-এর কোনো key নেই — সেটিংস → AI প্রোভাইডারে key দিন",
                "No key for $providerLabel — add one in Settings → AI providers",
            )
            "no_model" -> bn(
                "$providerLabel-এর মডেল বাছা হয়নি — সেটিংস → AI-তে ‘যাচাই’ চেপে মডেল বাছুন",
                "No model chosen for $providerLabel — tap ‘Test’ in Settings → AI",
            )
            "no_consent" -> bn(
                "‘ক্লাউড AI-তে পাঠানোর অনুমতি’ বন্ধ — সেটিংস → AI-তে চালু করুন",
                "Cloud AI consent is off — enable it in Settings → AI",
            )
            "local_only" -> bn(
                "শুধু-লোকাল মোড চালু — সেটিংস → AI-তে বন্ধ করুন বা লোকাল সার্ভার দিন",
                "Local-only mode is on — turn it off in Settings → AI or add a local server",
            )
            "paid_off" -> bn(
                "$providerLabel পেইড — ‘পেইড প্রোভাইডার অনুমতি’ চালু করুন বা ফ্রি key দিন",
                "$providerLabel is paid — allow paid providers or add a free key",
            )
            "no_url" -> bn("Custom সার্ভারের ঠিকানা দিন (সেটিংস → AI)", "Enter the custom server URL (Settings → AI)")
            "invalid_url" -> bn("Custom সার্ভারের ঠিকানা সঠিক নয়", "The custom server URL is not valid")
            "insecure_url" -> bn(
                "http:// শুধু লোকাল সার্ভারের জন্য — https:// ব্যবহার করুন",
                "http:// is only allowed for a local server — use https://",
            )
            else -> bn("AI প্রস্তুত নয় ($code)", "AI is not ready ($code)")
        }
    }

    /** Codes that the user can only fix in Settings, i.e. worth showing a ⚙ action for. */
    fun needsSettings(code: String?): Boolean = code != null
}

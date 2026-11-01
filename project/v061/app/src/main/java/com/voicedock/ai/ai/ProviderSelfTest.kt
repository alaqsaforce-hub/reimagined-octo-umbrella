package com.voicedock.ai.ai

import android.content.Context
import com.voicedock.ai.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.1.0 — "which providers that are already signed in actually work?"
 *
 * The app can be configured in several ways: a ChatGPT account signed in with OAuth, a Google/Gemini key,
 * an OpenRouter/Groq/Mistral key, or a local server (Ollama, llama.cpp, LM Studio). Being *configured* is
 * not the same as *working* — a key can be expired, a plan can be out of quota, a local server can be off.
 *
 * This runs one cheap call per provider (`/models`, no tokens spent) and reports what happened, so the
 * answer is measured rather than assumed. [ProviderSelfTestCore] is pure and unit-tested; the Android half
 * only does the calls.
 */
enum class TestStatus { OK, NO_CREDENTIALS, BLOCKED, FAILED }

data class ProviderTestResult(
    val provider: AiProvider,
    val status: TestStatus,
    /** Human-readable reason: "18 models", "key missing", "401 unauthorized", "network". */
    val detail: String,
)

object ProviderSelfTestCore {

    /**
     * Decide the status before any network call.
     * [blocked] is the reason id from `AiSetup.blockReason` (`null` = allowed to run).
     */
    fun status(blocked: String?, hasCredentials: Boolean): TestStatus = when {
        blocked == null -> if (hasCredentials) TestStatus.OK else TestStatus.NO_CREDENTIALS
        blocked == "not_configured" || blocked == "no_key" -> TestStatus.NO_CREDENTIALS
        else -> TestStatus.BLOCKED
    }

    fun mark(results: List<ProviderTestResult>): Map<TestStatus, Int> = results.groupingBy { it.status }.eachCount()

    /**
     * v1.2.0 — a self-hosted server that answers `/models` but lists nothing cannot chat. Calling that
     * "✓ working" was the one place this screen could still lie.
     */
    fun refine(provider: AiProvider, status: TestStatus, modelCount: Int): TestStatus =
        if (provider == AiProvider.CUSTOM && status == TestStatus.OK && modelCount == 0) TestStatus.FAILED else status

    /** One line for the settings card. Never claims a provider works when it was not tested. */
    fun summarize(results: List<ProviderTestResult>, bengali: Boolean): String {
        if (results.isEmpty()) return if (bengali) "এখনো কিছু পরীক্ষা করা হয়নি।" else "Nothing tested yet."
        val ok = results.count { it.status == TestStatus.OK }
        val failed = results.count { it.status == TestStatus.FAILED }
        val blocked = results.count { it.status == TestStatus.BLOCKED }
        val none = results.count { it.status == TestStatus.NO_CREDENTIALS }
        return if (bengali)
            "$ok টি কাজ করছে · $failed ব্যর্থ · $blocked ব্লকড · $none সেট করা নেই"
        else
            "$ok working · $failed failed · $blocked blocked · $none not configured"
    }

    fun detailFor(status: TestStatus, detail: String, bengali: Boolean): String = when (status) {
        TestStatus.OK -> if (bengali) "✓ কাজ করছে — $detail" else "✓ working — $detail"
        TestStatus.NO_CREDENTIALS -> if (bengali) "○ সেট করা নেই" else "○ not configured"
        TestStatus.BLOCKED -> if (bengali) "⛔ ব্লকড — $detail" else "⛔ blocked — $detail"
        TestStatus.FAILED -> if (bengali) "✗ ব্যর্থ — $detail" else "✗ failed — $detail"
    }
}

object ProviderSelfTest {

    suspend fun run(ctx: Context, providers: List<AiProvider> = AiProvider.entries): List<ProviderTestResult> =
        withContext(Dispatchers.IO) { providers.map { test(ctx, it) } }

    private fun test(ctx: Context, p: AiProvider): ProviderTestResult {
        val s = SettingsStore.state.value
        val blocked = runCatching { AiSetup.blockReason(ctx, p, s) }.getOrNull()
        val has = runCatching { AiSetup.hasKey(ctx, p) }.getOrDefault(false)
        val pre = ProviderSelfTestCore.status(blocked, has)
        if (pre != TestStatus.OK) {
            val reason = if (blocked != null) AiSetup.reasonText(blocked) else ""
            return ProviderTestResult(p, pre, reason)
        }
        val client = runCatching { AiSetup.testClient(ctx, p, s) }.getOrNull()
            ?: return ProviderTestResult(p, TestStatus.NO_CREDENTIALS, "no client")
        val bn = s.uiLanguage == com.voicedock.ai.settings.UiLanguage.BN
        return try {
            // /models is the cheapest authenticated call; it proves the credential and the endpoint.
            val models = client.listModels()
            val status = ProviderSelfTestCore.refine(p, TestStatus.OK, models.size)
            val ranked = if (p == AiProvider.CUSTOM) LocalServer.rank(models) else models
            val detail = when {
                status == TestStatus.FAILED -> LocalServer.diagnose(AiSetup.customKind(s), "no_models", bn)
                ranked.isEmpty() -> "connected"
                ranked.size == 1 -> "${ranked.first()}"
                else -> "${ranked.size} · ${ranked.first()}"
            }
            ProviderTestResult(p, status, detail)
        } catch (e: Exception) {
            // v1.2.0: a local server's failure gets its own actionable sentence, not "no internet".
            ProviderTestResult(p, TestStatus.FAILED, AiSetup.explainFor(e, s))
        }
    }
}

package com.voicedock.ai.ai

import java.net.URI

/**
 * v1.2.0 — the local-model half of "a provider must not fail for a silly reason".
 *
 * The most common ways a self-hosted model appeared broken were all address problems, not model
 * problems: the user typed `192.168.0.5:11434` (no scheme), pasted `…:11434/api/tags` from a curl
 * command, or left the `/v1` off an OpenAI-compatible endpoint. [normalise] fixes those deterministically
 * and [diagnose] turns the failure into one actionable sentence instead of "Request rejected".
 *
 * Pure Kotlin + java.net only, so every rule is unit-tested without a network or a phone.
 */
object LocalServer {

    /** Known self-hosted servers (used only for hints and default paths, never to block anything). */
    enum class Kind(val label: String, val port: Int?) {
        OLLAMA("Ollama", 11434),
        LM_STUDIO("LM Studio", 1234),
        LLAMA_CPP("llama.cpp", 8080),
        JAN("Jan", 1337),
        OTHER("", null),
    }

    data class Parsed(val url: String, val changed: Boolean, val kind: Kind) {
        val blank: Boolean get() = url.isBlank()
    }

    /** Paths people paste from a working curl command; the client appends its own path. */
    private val endpointSuffixes = listOf(
        "/v1/chat/completions", "/chat/completions", "/v1/completions", "/completions",
        "/v1/models", "/models", "/api/chat", "/api/generate", "/api/tags", "/api/show", "/api/ps", "/api",
    )

    private val badChars = Regex("[\\s\"'<>]")

    /**
     * A whitespace-free, endpoint-correct OpenAI-compatible base URL.
     *
     * Never "repairs" something that is genuinely unsafe: a URL with userinfo, a query or a fragment is
     * returned untouched so [AiPolicy] can reject it (a LAN address must not smuggle credentials or
     * redirect the prompt). Empty input stays empty.
     */
    fun normalise(raw: String): Parsed {
        val t = raw.trim()
        if (t.isEmpty()) return Parsed("", false, Kind.OTHER)
        if (t.contains('?') || t.contains('#') || badChars.containsMatchIn(t)) return Parsed(t, false, Kind.OTHER)
        // userinfo (@ before the path) is a policy problem, not a typo
        val schemeEnd = t.indexOf("://").takeIf { it > 0 }?.plus(3) ?: 0
        val authority = t.substring(schemeEnd).substringBefore('/')
        if (authority.contains('@')) return Parsed(t, false, Kind.OTHER)

        var changed = false
        var s = t
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // A bare host:port (or host) is what people type for a box on their own Wi-Fi. Plain http is
            // still subject to the local-only rule in AiPolicy — this only removes the "invalid URL" dead end.
            s = "http://$s"
            changed = true
        }
        for (suffix in endpointSuffixes) {
            if (s.endsWith(suffix, ignoreCase = true)) {
                s = s.dropLast(suffix.length)
                changed = true
                break
            }
        }
        s = s.trimEnd('/')
        val path = runCatching { URI(s).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") {
            s = "$s/v1"
            changed = true
        }
        val kind = kindOf(s)
        if (s != t) changed = true
        return Parsed(s, changed, kind)
    }

    /** Best-effort identification of the server behind an address (for hints only). */
    fun kindOf(url: String): Kind {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return Kind.OTHER
        val port = u.port
        val host = u.host?.lowercase().orEmpty()
        return when {
            host.contains("ollama") -> Kind.OLLAMA
            port == Kind.OLLAMA.port -> Kind.OLLAMA
            port == Kind.LM_STUDIO.port -> Kind.LM_STUDIO
            host.contains("lmstudio") -> Kind.LM_STUDIO
            port == Kind.LLAMA_CPP.port -> Kind.LLAMA_CPP
            port == Kind.JAN.port -> Kind.JAN
            else -> Kind.OTHER
        }
    }

    /**
     * Which model should be tried first on a local server.
     *
     * The old behaviour was "alphabetically first", which happily picked an embedding model or a
     * vision-only model and then failed at chat time. Ranking prefers real chat models and pushes
     * everything that cannot hold a conversation to the bottom (they can still be chosen by hand).
     */
    private val notChat = listOf("embed", "nomic", "bge", "e5-", "gte", "rerank", "whisper", "tts", "audio",
        "guard", "moderation", "clip", "llava-v", "bakllava", "ocr", "sd-", "stable-diffusion", "flux")
    private val chatHints = listOf("instruct", "chat", "qwen", "gemma", "llama", "mistral", "phi", "deepseek",
        "smollm", "tinyllama", "granite", "falcon", "yi", "command", "hermes", "vicu")

    fun rank(models: List<String>): List<String> {
        if (models.isEmpty()) return models
        fun score(m: String): Int {
            val l = m.lowercase()
            var s = 0
            if (notChat.any { l.contains(it) }) s -= 100
            if (l.contains("instruct")) s += 30
            if (l.contains("chat")) s += 25
            s += chatHints.count { l.contains(it) } * 10
            if (l.contains("vision")) s -= 5 // can chat, just not the ideal default on a phone
            return s
        }
        return models.sortedWith(compareByDescending<String> { score(it) }.thenBy { it.length }.thenBy { it })
    }

    /** One actionable sentence for a local-server failure, in the user's language. */
    fun diagnose(kind: Kind, code: String, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val name = kind.label.ifBlank { bn("সার্ভার", "server") }
        return when (code) {
            "unreachable" -> bn(
                "$name-এ পৌঁছানো যাচ্ছে না — কম্পিউটারে সার্ভারটি চালু আছে কি? ফোন ও কম্পিউটার একই ওয়াই-ফাইতে আছে তো?",
                "Cannot reach $name — is the server running, and are the phone and computer on the same Wi-Fi?",
            )
            "timeout" -> bn(
                "$name সময়মতো উত্তর দেয়নি — মডেলটি হয়তো ফোনের/LAN-এর জন্য বড়। qwen2.5:1.5b বা gemma2:2b দিয়ে দেখুন।",
                "$name did not answer in time — the model may be too big for this LAN. Try qwen2.5:1.5b or gemma2:2b.",
            )
            "not_found" -> bn(
                "$name-এর ঠিকানায় API পথটি নেই — সাধারণত পোর্ট 11434 (Ollama) বা 1234 (LM Studio) ঠিক থাকলেই হয়; /v1 নিজে যোগ করে নেয়।",
                "$name has no API on that path — usually the port is wrong (Ollama 11434, LM Studio 1234); /v1 is added automatically.",
            )
            "auth" -> bn(
                "$name অনুমতি চাইছে — লোকাল সার্ভারে সাধারণত key লাগে না, তাই key খালি রাখুন।",
                "$name is asking for credentials — a local server normally needs none, so leave the key empty.",
            )
            "no_models" -> bn(
                "$name-এ কোনো মডেল নেই — কম্পিউটারে `ollama pull qwen2.5:1.5b` চালিয়ে আবার ‘যাচাই’ চাপুন।",
                "No models on $name — run `ollama pull qwen2.5:1.5b` on the computer, then tap Test again.",
            )
            else -> bn("$name-এ সমস্যা", "Problem with $name")
        }
    }
}

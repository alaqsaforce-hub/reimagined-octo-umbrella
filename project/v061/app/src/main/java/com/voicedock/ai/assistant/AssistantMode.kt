package com.voicedock.ai.assistant

import kotlinx.serialization.Serializable

/**
 * v1.1.0 — the four ways the assistant can behave. One setting, and every other part of the app asks this
 * file instead of deciding for itself, so the modes cannot disagree with each other.
 *
 *  * [AGENT]           — always listening (when the wake word is on), acts on multi-step commands,
 *                        speaks its answers. This is the "Jarvis" mode.
 *  * [VOICE_TO_VOICE]  — you speak, it speaks back; text is still recorded but the screen is secondary.
 *  * [VOICE_TEXT]      — you speak, the answer appears as text **and** is read aloud.
 *  * [TEXT_ONLY]       — no microphone, no speech: commands are typed, answers are written. Useful in a
 *                        meeting, on a train, or for anyone who does not want a device that listens.
 */
@Serializable
enum class AssistantMode {
    AGENT, VOICE_TO_VOICE, VOICE_TEXT, TEXT_ONLY;

    val titleBn: String
        get() = when (this) {
            AGENT -> "এজেন্ট"
            VOICE_TO_VOICE -> "ভয়েস → ভয়েস"
            VOICE_TEXT -> "ভয়েস + লেখা"
            TEXT_ONLY -> "শুধু লেখা"
        }

    val titleEn: String
        get() = when (this) {
            AGENT -> "Agent"
            VOICE_TO_VOICE -> "Voice → voice"
            VOICE_TEXT -> "Voice + text"
            TEXT_ONLY -> "Text only"
        }
}

/** The decisions every caller must take from one place. Pure, so all four modes are unit-tested. */
object AssistantModeCore {

    /** May the microphone open at all (dictation and the wake word)? */
    fun acceptsVoice(mode: AssistantMode): Boolean = mode != AssistantMode.TEXT_ONLY

    /** May the hotword service run? Also needs the user's own opt-in — this is only the mode's half. */
    fun allowsHotword(mode: AssistantMode): Boolean = mode != AssistantMode.TEXT_ONLY

    /** Should command results be read aloud? (The "Speak results" switch still has the final word.) */
    fun speaksResults(mode: AssistantMode, speakResultsEnabled: Boolean): Boolean =
        mode != AssistantMode.TEXT_ONLY && speakResultsEnabled

    /** A one-word spoken greeting when the wake word is heard. */
    fun greetsAloud(mode: AssistantMode): Boolean = mode != AssistantMode.TEXT_ONLY

    /** Is the written transcript the primary output (so it must stay on screen)? */
    fun showsText(mode: AssistantMode): Boolean = mode != AssistantMode.VOICE_TO_VOICE

    /** Agent mode is allowed to run a plan of several steps; the other modes answer one command at a time. */
    fun allowsPlans(mode: AssistantMode): Boolean = mode == AssistantMode.AGENT

    /** When the wake word is heard in this mode, what happens? */
    fun onWakeAction(mode: AssistantMode): WakeAction = when (mode) {
        AssistantMode.TEXT_ONLY -> WakeAction.SHOW_ONLY
        AssistantMode.AGENT -> WakeAction.LISTEN_AND_GREET
        AssistantMode.VOICE_TO_VOICE, AssistantMode.VOICE_TEXT -> WakeAction.LISTEN_AND_GREET
    }

    enum class WakeAction {
        /** Microphone stays closed: the bubble opens with a note, the user types. */
        SHOW_ONLY,

        /** Bubble opens, a short greeting is spoken, dictation starts. */
        LISTEN_AND_GREET,
    }

    fun describe(mode: AssistantMode, bengali: Boolean): String = when (mode) {
        AssistantMode.AGENT -> if (bengali)
            "সবসময় এজেন্ট — ডাকলে খুলবে, কথা বলবে, কয়েক ধাপের কাজও করতে পারবে।"
        else
            "Always an agent — opens when called, speaks, and may run multi-step work."
        AssistantMode.VOICE_TO_VOICE -> if (bengali)
            "ভয়েস → ভয়েস — বলবেন, উত্তর শুনবেন।"
        else
            "Voice → voice — you speak, you hear the answer."
        AssistantMode.VOICE_TEXT -> if (bengali)
            "ভয়েস + লেখা — বলবেন, উত্তর লিখেও দেখাবে এবং পড়েও শোনাবে।"
        else
            "Voice + text — you speak, the answer is written and read out."
        AssistantMode.TEXT_ONLY -> if (bengali)
            "শুধু লেখা — মাইক খোলা থাকবে না, কিছু শোনা যাবে না।"
        else
            "Text only — the microphone stays closed and nothing is listened to."
    }
}

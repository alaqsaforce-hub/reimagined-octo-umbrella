package com.voicedock.ai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * v0.7.0 — "পড়ে শোনাও" (read it aloud) and spoken results.
 *
 * One TextToSpeech engine, lazily created, Bangla preferred and English as fallback. If the phone has no
 * Bangla voice the engine reports it and [speak] returns false, so the caller can show the text instead
 * of staying silent. Nothing is cached to disk and no audio leaves the device.
 */
class Speaker(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var banglaOk: Boolean? = null
    /** Called (on the TTS thread) when an utterance finishes — the overlay uses it to stop listening. */
    @Volatile var onDone: (() -> Unit)? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                banglaOk = runCatching {
                    val r = tts?.setLanguage(Locale("bn", "BD"))
                    r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                }.getOrDefault(false)
                if (banglaOk == false) runCatching { tts?.setLanguage(Locale.US) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { onDone?.invoke() }
                    @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) { onDone?.invoke() }
                })
            } else {
                Log.w(TAG, "TextToSpeech init failed")
            }
        }
    }

    fun hasBangla(): Boolean = banglaOk == true

    /** @return false when the engine is not ready or has no suitable voice — caller shows text instead. */
    fun speak(text: String, bengali: Boolean, flush: Boolean = true): Boolean {
        init()
        val engine = tts ?: return false
        if (!ready) return false
        val lang = if (bengali && hasBangla()) Locale("bn", "BD") else if (bengali) Locale.US else Locale.US
        runCatching { engine.setLanguage(lang) }
        engine.setSpeechRate(1.0f)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return engine.speak(text.take(3000), mode, null, "vd-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    fun stop() = runCatching { tts?.stop() }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    companion object { private const val TAG = "VoiceDockTts" }
}

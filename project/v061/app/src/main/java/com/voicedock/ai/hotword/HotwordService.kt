package com.voicedock.ai.hotword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.voicedock.ai.R
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.overlay.AssistantOverlayService
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.MainActivity

/**
 * v1.0.0 — **"শাহরুখ"** wake-word listener (opt-in foreground service, type = microphone).
 *
 * What it really is: the phone's own speech recogniser, restarted in a loop, with every partial result
 * checked against [WakePhrase]. That has honest consequences, which the settings screen states plainly:
 *
 *  * it needs the microphone (and a persistent notification while it runs — Android's rule, not a choice);
 *  * the recogniser belongs to the phone's speech service, so accuracy and battery cost are that service's;
 *  * Android does not allow an endless background microphone forever, so the service is started from the
 *    app (a user action) and stops if the microphone permission or the setting goes away;
 *  * with `hotwordAlwaysOn` off it only listens while the screen is on; the check happens every cycle, so
 *    turning the screen off pauses it within a few seconds and does not need a broadcast receiver.
 *
 * On a wake it releases the microphone, buzzes once, and hands over to [AssistantOverlayService]; the wake
 * word and a command in one breath ("শাহরুখ, ওয়াটসঅ্যাপ খোলো") goes straight to the command layer.
 */
class HotwordService : LifecycleService() {

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var failures = 0
    private var lastStatus = ""
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        listening = true
        startCycle()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ the listening loop

    private fun screenOn(): Boolean = runCatching {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)

    /** Charging? The duty plan listens continuously when it is — there is nothing to save. */
    private fun charging(): Boolean = runCatching {
        (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging ?: false
    }.getOrDefault(false)

    /** The current listening rhythm (pure policy in [DutyPlanner]). */
    private fun duty(): Duty {
        val s = SettingsStore.state.value
        return DutyPlanner.plan(
            screenOn = screenOn(),
            charging = charging(),
            alwaysOn = s.hotwordAlwaysOn,
            lean = s.hotwordLean,
        )
    }

    private fun dictationBusy(): Boolean = runCatching {
        HotwordGate.dictationBusy(VoiceDock.dictation(this).ui.value.phase?.name)
    }.getOrDefault(false)

    private fun mayListen(): Boolean {
        val s = SettingsStore.state.value
        if (!Permissions.mic(this)) return false
        return HotwordGate.shouldListen(
            enabled = s.hotwordEnabled,
            alwaysOn = s.hotwordAlwaysOn,
            screenOn = screenOn(),
            dictationBusy = dictationBusy(),
        )
    }

    /** Why we are not listening right now — shown in the notification so it is never a silent no-op. */
    private fun statusLine(): String {
        val s = SettingsStore.state.value
        return when {
            !Permissions.mic(this) -> tr("মাইক্রোফোনের অনুমতি নেই", "Microphone permission missing")
            !s.hotwordEnabled -> tr("বন্ধ আছে", "Turned off")
            dictationBusy() -> tr("ডিকটেশন চলছে — পরে আবার শুনবে", "Dictation in progress — resuming after")
            !screenOn() && !s.hotwordAlwaysOn -> tr("স্ক্রিন বন্ধ — থেমে আছে", "Screen off — paused")
            else -> tr("শুনছি…", "Listening…")
        }
    }

    private fun startCycle() {
        if (!running || paused) return
        val duty = duty()
        updateNotification(statusLine())
        if (!mayListen()) {
            // Idle check: cheap, and it makes the screen-off / setting-off cases recover by themselves.
            schedule(duty.idleMs)
            return
        }
        cycles++
        val r = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (r == null) {                                    // no recogniser on this phone
            schedule(HotwordGate.backoffMs(++failures))
            return
        }
        runCatching { r.startListening(listenIntent()) }.onFailure {
            Log.w(TAG, "startListening failed: ${it.javaClass.simpleName}")
            schedule(HotwordGate.backoffMs(++failures))
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification(tr("এই ফোনে স্পিচ রিকগনাইজার নেই", "No speech recogniser on this phone"))
            return null
        }
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(Listener()) }
        }.getOrElse {
            Log.w(TAG, "createSpeechRecognizer failed: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, SettingsStore.state.value.language.tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        // Keep each cycle short: a wake word is one or two words, not a paragraph.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
    }

    private fun schedule(delayMs: Long) {
        if (!running) return
        main.postDelayed({ startCycle() }, delayMs.coerceAtLeast(200L))
    }

    private fun stopRecognizer() {
        val r = recognizer
        recognizer = null
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (check(partialResults)) return // a wake cancels the rest of this cycle
        }

        override fun onResults(results: Bundle?) {
            if (check(results)) return
            failures = 0
            // The gap between cycles is the whole battery story: see [DutyPlanner].
            schedule(duty().idleMs)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotification(tr("মাইক্রোফোনের অনুমতি নেই", "Microphone permission missing"))
                    stopEverything()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    // Another app owns the recogniser; drop ours and try again shortly.
                    stopRecognizer()
                    schedule(HotwordGate.backoffMs(++failures))
                }
                // NO_MATCH / SPEECH_TIMEOUT are the normal case for a silent room: just listen again.
                else -> schedule(HotwordGate.backoffMs(failures))
            }
        }
    }

    /** True when one of the candidates contained the wake word (and the hand-over started). */
    private fun check(bundle: Bundle?): Boolean {
        val candidates = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (u in candidates) {
            if (!WakePhrase.matches(u)) continue
            onWake(WakePhrase.remainder(u))
            return true
        }
        return false
    }

    private fun onWake(remainder: String) {
        paused = true
        failures = 0
        wakes++
        // Release the microphone FIRST: the assistant is about to open it for dictation.
        stopRecognizer()
        buzz()
        updateNotification(tr("‘শাহরুখ’ শুনেছি ✓", "Heard ‘Shahrukh’ ✓"))
        val intent = Intent(this, AssistantOverlayService::class.java)
            .setAction(AssistantOverlayService.ACTION_WAKE)
            .putExtra(AssistantOverlayService.EXTRA_WAKE_COMMAND, remainder)
        runCatching { ContextCompat.startForegroundService(this, intent) }.onFailure {
            Log.w(TAG, "assistant start failed: ${it.javaClass.simpleName}")
        }
        waitForMicToFree(WAKE_PAUSE_MS)
    }

    /** Dictation owns the microphone for a while; come back as soon as it lets go. */
    private fun waitForMicToFree(leftMs: Long) {
        if (!running) return
        if (leftMs <= 0 || !dictationBusy()) {
            paused = false
            schedule(700L)
            return
        }
        main.postDelayed({ waitForMicToFree(leftMs - POLL_MS) }, POLL_MS)
    }

    private fun buzz() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            // minSdk is 26, so VibrationEffect is always available.
            v?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ------------------------------------------------------------------ foreground + notification

    private fun goForeground(): Boolean {
        if (!Permissions.mic(this)) {
            updateNotification(tr("মাইক্রোফোনের অনুমতি নেই", "Microphone permission missing"))
            return false
        }
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(tr("শুনছি…", "Listening…")), type)
            true
        } catch (e: Exception) {
            // Android 14+ refuses a microphone FGS started from the background: the user must open the app.
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, tr("‘শাহরুখ’ ওয়েক-ওয়ার্ড", "‘Shahrukh’ wake word"), NotificationManager.IMPORTANCE_LOW)
        ch.description = tr("মাইক খোলা থাকলে এই নোটিফিকেশনটি দেখাবে (Android-এর নিয়ম)।", "Shown while the microphone is open (Android's rule).")
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val off = PendingIntent.getService(
            this, 1, Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(tr("‘শাহরুখ’ — $status", "‘Shahrukh’ — $status"))
            .setContentText(tr("ডাকলে বাবল খুলবে। বন্ধ করতে ‘বন্ধ’ চাপুন।", "Say it to open the bubble. Tap Stop to turn it off."))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, tr("বন্ধ", "Stop"), off)
            .build()
    }

    private fun updateNotification(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(status)) }
    }

    private fun stopEverything() {
        running = false
        listening = false
        paused = false
        stopRecognizer()
        // Turning the setting off as well keeps the UI and the service from disagreeing.
        if (SettingsStore.state.value.hotwordEnabled) SettingsStore.update { it.copy(hotwordEnabled = false) }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        listening = false
        main.removeCallbacksAndMessages(null)
        stopRecognizer()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceDockHotword"
        const val CHANNEL = "hotword"
        const val NOTIF_ID = 47
        const val ACTION_STOP = "com.voicedock.ai.HOTWORD_STOP"
        private const val RESTART_MS = 350L
        private const val POLL_MS = 500L
        private const val WAKE_PAUSE_MS = 25_000L

        /** True while the foreground service is actually running (read by the settings screen). */
        @Volatile var listening = false
        /** v1.1.0 diagnostics for the settings screen: listen cycles started / wake words heard. */
        @Volatile var cycles = 0
        @Volatile var wakes = 0

        /** Call from a visible screen (Android 14 background-start rules for microphone services). */
        fun start(ctx: Context) {
            val i = Intent(ctx, HotwordService::class.java)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .onFailure { Log.w(TAG, "hotword start failed: ${it.javaClass.simpleName}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, HotwordService::class.java).setAction(ACTION_STOP)) }
            listening = false
        }
    }
}

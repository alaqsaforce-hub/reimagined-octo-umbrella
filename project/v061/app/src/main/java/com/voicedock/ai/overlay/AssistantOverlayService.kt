package com.voicedock.ai.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voicedock.ai.R
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiSetup
import com.voicedock.ai.security.SecretDetector
import com.voicedock.ai.device.AgentSafetyOracle
import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.context.AssistantGreeting
import com.voicedock.ai.context.CatchUpSummary
import com.voicedock.ai.context.ContextClassifier
import com.voicedock.ai.context.ContextKind
import com.voicedock.ai.context.ContextPolicy
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.context.LocalIntentRouter
import com.voicedock.ai.device.ProbeRunner
import com.voicedock.ai.device.ScreenProbe
import com.voicedock.ai.insertion.ClipboardInsertion
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.insertion.TextInserter
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.MainActivity
import com.voicedock.ai.ai.AiReadinessCore
import com.voicedock.ai.appc.AppLauncher
import com.voicedock.ai.command.Choice
import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandJson
import com.voicedock.ai.command.CommandLog
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.assistant.AssistantModeCore
import com.voicedock.ai.command.ChainOutcome
import com.voicedock.ai.command.ChainResolver
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.UndoEntry
import com.voicedock.ai.command.UndoKind
import com.voicedock.ai.command.UndoLog
import com.voicedock.ai.command.UndoText
import com.voicedock.ai.media.MediaControl
import com.voicedock.ai.media.MediaText
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent as CmdIntent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.memory.MemoryStore
import com.voicedock.ai.notify.Digest
import com.voicedock.ai.telecom.CallController
import com.voicedock.ai.telecom.CallStrategy
import com.voicedock.ai.telecom.ContactEntry
import com.voicedock.ai.telecom.ContactMatch
import com.voicedock.ai.telecom.ContactResolver
import com.voicedock.ai.notify.NotifStore
import com.voicedock.ai.notify.NotificationAccess
import com.voicedock.ai.notify.RemoteReply
import com.voicedock.ai.notify.SenderMatch
import com.voicedock.ai.memory.MemoryText
import com.voicedock.ai.tts.Speaker
import com.voicedock.ai.text.AiFormatter
import com.voicedock.ai.text.FormatStyle
import com.voicedock.ai.vision.ScreenAssistant
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * v0.6 "Contextual Mic Bubble".
 *
 * This is the mic-FREE overlay owner (design decision #3: Collection != Activation).
 * It has NO microphone permission requirement: on Android 14+ it runs as a `specialUse`
 * foreground service, on Android 10-13 as `dataSync`, on older as an untyped FGS.
 * The existing [OverlayService] remains the microphone FGS and is started only when the user
 * actually chooses "Dictate" — so a text/screen assistant works with the mic OFF.
 *
 * Tap flow: SCANNING (one ScreenProbe) -> ContextClassifier -> ContextCard (non-focusable) or
 * AssistantBubble (focusable, types its own greeting). Nothing is inserted into another app
 * without an explicit user tap on "Insert here".
 */
class AssistantOverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var scrim: ScrimController? = null
    private var cardView: ComposeView? = null
    private var cardOwner: OverlayLifecycleOwner? = null
    private var chatView: ComposeView? = null
    private var chatOwner: OverlayLifecycleOwner? = null
    private var dictView: ComposeView? = null
    private var dictOwner: OverlayLifecycleOwner? = null

    /** v0.6.6 — the small dictation bubble (tap = mic on). */
    private val _dict = MutableStateFlow(DictationBubbleState())
    /** True once ✨ has replaced the recognised text, so new live text does not overwrite it. */
    private var dictFormatted = false

    // ---- v0.7.0 commands ----
    private val memory by lazy { MemoryStore(filesDir, VoiceDock.db(this).assistant()) }
    private val commandLog by lazy { CommandLog(filesDir) }
    private val launcher by lazy { AppLauncher(this) }
    private val notifStore by lazy { NotifStore(filesDir, VoiceDock.db(this).assistant()) }
    private val calls by lazy { CallController(this) }

    /** v0.10.0 — single-slot undo (60 s). The actual revert lives in [undoRun]. */
    private val undoLog = UndoLog()
    private var undoRun: (() -> Boolean)? = null
    private val contacts by lazy { ContactResolver(this) }
    private val speaker by lazy { Speaker(this) }
    /** Buttons currently shown in the bubble, and the command waiting for a confirmation. */
    private var pendingChoices: List<Choice> = emptyList()
    private var pendingSpec: CommandSpec? = null
    private var pendingSource: String = ""



    private val _ui = MutableStateFlow(AssistantUiState())
    val ui: StateFlow<AssistantUiState> = _ui.asStateFlow()

    private var probeJob: Job? = null
    private var assistantDictationStarted = false
    private var assistantHelpPending = false
    private var lastDictationDraft = ""
    private var lastHelpAnswer = ""

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        createChannel()
        scrim = ScrimController(this, wm, overlayType()) { closeWindows() }.also { it.attach() }
        addBubble()
        observeDictation()
        observeScreenHelp()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_HIDE -> removeBubble()
            ACTION_RESCAN -> activate()
            ACTION_CLOSE -> { closeEverything(); return START_NOT_STICKY }
            // v1.0.0: the wake word was heard. Same bubble as a tap; if the command came in the same
            // breath ("শাহরুখ, ওয়াটসঅ্যাপ খোলো") it runs immediately instead of waiting for speech.
            ACTION_WAKE -> {
                addBubble()
                val mode = SettingsStore.state.value.mode
                val spoken = intent?.getStringExtra(EXTRA_WAKE_COMMAND).orEmpty().trim()
                when {
                    // v1.1.0 — text-only mode must not open the microphone, whatever woke the bubble.
                    !AssistantModeCore.acceptsVoice(mode) -> {
                        _dict.value = _dict.value.copy(
                            visible = true,
                            note = tr(
                                "শুধু লেখা মোড — মাইক বন্ধ। লিখে কমান্ড দিন (সেটিংস → মোড)।",
                                "Text-only mode — the microphone stays closed. Type the command (Settings → Mode).",
                            ),
                        )
                        ensureBubbleVisible()
                    }
                    spoken.isNotBlank() -> {
                        _dict.value = _dict.value.copy(visible = true, liveText = spoken)
                        ensureBubbleVisible()
                        runCommand(spoken)
                    }
                    else -> {
                        // "শাহরুখ" → it answers out loud, then listens.
                        if (AssistantModeCore.greetsAloud(mode)) speakGreeting()
                        startDictationHere()
                    }
                }
            }
            else -> addBubble()
        }
        return START_NOT_STICKY
    }

    /** No RECORD_AUDIO check here — that was the v0.5 defect. */
    private fun goForeground(): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        Log.w("VoiceDock", "assistant startForeground failed: ${e.javaClass.simpleName}")
        Toast.makeText(this, tr("VoiceDock অ্যাপ খুলে আবার চেষ্টা করুন", "Open VoiceDock and try again"), Toast.LENGTH_LONG).show()
        false
    }

    // ------------------------------------------------------------------ mic bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null) return
        if (!Permissions.overlay(this)) {
            toast(tr("‘অন্য অ্যাপের উপরে দেখানো’ অনুমতি দিন", "Allow ‘Display over other apps’"))
            return
        }
        val s = SettingsStore.state.value
        val size = (54 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(size / 4, size / 4, size / 4, size / 4)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
            contentDescription = tr("VoiceDock সহকারী বাবল", "VoiceDock assistant bubble")
            elevation = 8f
        }
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = s.bubbleX; y = s.bubbleY }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var downAt = 0L
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                    downAt = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) SettingsStore.update { it.copy(bubbleX = p.x, bubbleY = p.y) }
                    else if (System.currentTimeMillis() - downAt >= 450) {
                        // Long press keeps the old contextual panel reachable (agent, settings, drafts).
                        if (_ui.value.phase != AssistantPhase.IDLE) closeWindows() else activate()
                    } else onBubbleTap()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(view, p) }.onSuccess { bubble = view; bubbleParams = p }
            .onFailure { Log.w("VoiceDock", "assistant bubble add failed: ${it.javaClass.simpleName}") }
        updateBubble()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun updateBubble() {
        val b = bubble ?: return
        val d = _dict.value
        val color = when {
            d.visible && d.phase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PROCESSING) -> COLOR_DICT_ON
            d.visible && d.liveText.isNotBlank() -> COLOR_DICT_READY
            d.visible -> COLOR_DICT_ON
            _ui.value.phase == AssistantPhase.SCANNING -> COLOR_SCANNING
            _ui.value.phase == AssistantPhase.CONTEXT_CARD -> COLOR_CONTEXT
            _ui.value.phase == AssistantPhase.CHAT -> COLOR_CHAT
            else -> COLOR_IDLE
        }
        (b.background as? GradientDrawable)?.setColor(color)
    }

    // ------------------------------------------------------------------ dictation bubble (v0.6.6)

    /**
     * A short tap on the floating icon. It NEVER opens the tall panel: it turns the microphone on
     * and shows the small bubble that grows with the text. Tapping again closes it.
     */
    private fun onBubbleTap() {
        if (_dict.value.visible) { closeDictBubble(); return }
        val d = VoiceDock.dictation(this).ui.value
        val busy = d.phase in setOf(
            DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED,
            DictationPhase.WAITING, DictationPhase.INTERRUPTED, DictationPhase.PROCESSING,
        )
        if (busy || d.live.isNotBlank()) {
            dictFormatted = false
            _dict.value = DictationBubbleState(
                visible = true, phase = d.phase,
                liveText = d.live.ifBlank { d.draft },
                note = if (d.live.isBlank() && d.draft.isBlank()) tr("শুনছি… বলুন", "Listening — speak") else "",
            )
            addDictBubble()
        } else {
            startDictationHere()
        }
        updateBubble()
    }

    private fun startDictationHere() {
        if (!AssistantModeCore.acceptsVoice(SettingsStore.state.value.mode)) {
            _dict.value = _dict.value.copy(
                note = tr("শুধু লেখা মোড — মাইক চালু হবে না।", "Text-only mode — the microphone stays off."),
            )
            ensureBubbleVisible()
            return
        }
        if (!Permissions.mic(this)) {
            toast(tr("মাইক্রোফোনের অনুমতি দিন", "Grant microphone permission"))
            openSettings()
            return
        }
        // A stale panel from a previous long-press must not linger behind the bubble.
        closeWindows()
        dictFormatted = false
        assistantDictationStarted = true
        undoLog.clear(); undoRun = null
        lastDictationDraft = ""
        _dict.value = DictationBubbleState(
            visible = true, phase = DictationPhase.LISTENING,
            busy = true, busyLabel = tr("মাইক চালু হচ্ছে…", "Starting the microphone…"),
        )
        addDictBubble()
        updateBubble()
        OverlayService.start(this, OverlayService.ACTION_START_DICTATION)
        lifecycleScope.launch {
            delay(1600)
            if (!OverlayService.foregroundOk) {
                _dict.value = _dict.value.copy(
                    busy = false, busyLabel = "", phase = null,
                    note = tr("মাইক চালু হয়নি — VoiceDock খুলে আবার চেষ্টা করুন", "Microphone did not start — open VoiceDock and try again"),
                )
            } else {
                _dict.value = _dict.value.copy(busy = false, busyLabel = "")
            }
        }
    }

    /** Non-focusable, wrap-content window anchored to the floating icon: the app below keeps the cursor. */
    private fun addDictBubble() {
        if (dictView != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view); owner.create()
        view.setContent {
            val state by _dict.collectAsState()
            DictationBubble(
                state = state,
                onInsert = { insertDictated() },
                onRephrase = { rephraseDictated() },
                onAsk = { askDictated() },
                onCopy = {
                    val t = state.answer.ifBlank { state.liveText }
                    if (t.isNotBlank()) { ClipboardInsertion.insert(this, t); toast(tr("কপি হয়েছে", "Copied")) }
                },
                onClose = { closeDictBubble() },
                onOpenSettings = { closeDictBubble(); openSettings() },
                onCommand = { runCommand(state.liveText) },
                onChoice = { onChoicePicked(it) },
                onUndo = { runUndo() },
            )
        }
        val dm = resources.displayMetrics
        val iconSize = (54 * dm.density).toInt()
        val maxWidth = (dm.widthPixels * 0.68f).toInt()
        val bp = bubbleParams
        var x = (bp?.x ?: 0) + iconSize / 2
        var y = (bp?.y ?: 200) + iconSize + (8 * dm.density).toInt()
        x = x.coerceIn(0, (dm.widthPixels - maxWidth).coerceAtLeast(0))
        // Keep it on screen: if there is no room below the icon, put the bubble above it.
        val estimatedHeight = (dm.heightPixels * 0.34f).toInt()
        if (y + estimatedHeight > dm.heightPixels) y = ((bp?.y ?: 400) - estimatedHeight).coerceAtLeast(0)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        runCatching { wm.addView(view, p) }.onSuccess { dictView = view; dictOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "dict bubble add failed: ${it.javaClass.simpleName}") }
    }

    private fun removeDictBubble() {
        dictView?.let { runCatching { wm.removeView(it) } }
        dictOwner?.destroy(); dictView = null; dictOwner = null
    }

    /** Close the bubble. The text is never lost: the controller has already saved every sentence. */
    private fun closeDictBubble(stopDictation: Boolean = true) {
        removeDictBubble()
        pendingChoices = emptyList()
        pendingSpec = null
        _dict.value = DictationBubbleState()
        if (stopDictation) {
            val d = VoiceDock.dictation(this).ui.value
            if (d.micOn) runCatching { VoiceDock.dictation(this).finish() }
        }
        updateBubble()
    }

    /** ⏎ — put the text into the focused field of the app underneath (never into a password field). */
    private fun insertDictated() {
        val text = _dict.value.liveText.trim()
        if (text.isBlank()) { toast(tr("কোনো লেখা নেই", "There is no text yet")); return }
        val micOn = VoiceDock.dictation(this).ui.value.micOn
        removeDictBubble()
        lifecycleScope.launch {
            if (micOn) runCatching { VoiceDock.dictation(this@AssistantOverlayService).finish() }
            // Let our window disappear so the app underneath gets the input focus back.
            delay(280)
            val out = TextInserter.insert(this@AssistantOverlayService, text)
            toast(tr(out.result.bn, out.result.en))
            VoiceDock.dictation(this@AssistantOverlayService).markInserted(out.destination)
            _dict.value = DictationBubbleState()
            updateBubble()
        }
    }

    /** ✨ — tidy the recognised text (Mode B). The original is kept on device as the draft. */
    private fun rephraseDictated() {
        val text = _dict.value.liveText.trim()
        if (text.isBlank()) return
        val readiness = AiSetup.readiness(this)
        if (!readiness.ok) {
            // v0.6.7: say which provider is stuck and what to do — not "add a key" when a key exists.
            _dict.value = _dict.value.copy(
                note = AiReadinessCore.message(readiness.code, readiness.provider?.label ?: "AI", isBengali()),
                needsSetup = AiReadinessCore.needsSettings(readiness.code),
            )
            return
        }
        val style = runCatching { FormatStyle.valueOf(SettingsStore.state.value.formatStyle) }.getOrDefault(FormatStyle.CLEAN)
        _dict.value = _dict.value.copy(
            busy = true, busyLabel = tr("সাজানো হচ্ছে…", "Tidying up…"), note = "", answer = "", needsSetup = false,
        )
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AiFormatter.format(AiSetup.router(this@AssistantOverlayService), text, style) }
            }.onSuccess { r ->
                if (r.text.isBlank()) {
                    _dict.value = _dict.value.copy(busy = false, busyLabel = "", note = tr("সাজানো যায়নি", "Could not tidy the text"))
                } else {
                    dictFormatted = true
                    _dict.value = _dict.value.copy(
                        liveText = r.text, busy = false, busyLabel = "",
                        providerLabel = BubbleAi.providerLabel(r.answer),
                        note = if (r.warnings.isEmpty()) tr("✨ সাজানো হয়েছে", "✨ Tidied up") else r.warnings.joinToString(" · "),
                    )
                }
            }.onFailure { e ->
                val why = if (e is AiException) AiSetup.explain(e) else (e.message ?: e.javaClass.simpleName)
                _dict.value = _dict.value.copy(
                    busy = false, busyLabel = "",
                    note = tr("AI সংযোগ ব্যর্থ — লেখা অপরিবর্তিত: ", "AI request failed — text unchanged: ") + why,
                    needsSetup = true,
                )
            }
        }
    }

    /** 💬 — ask the AI about what you just said. The answer is shown here, never inserted. */
    private fun askDictated() {
        val text = _dict.value.liveText.trim()
        if (text.isBlank()) return
        if (SecretDetector.isSecret(text)) {
            _dict.value = _dict.value.copy(note = tr("কোড/পিন-সদৃশ লেখা AI-তে পাঠানো হয়নি", "Code/PIN-like text was not sent to the AI"))
            return
        }
        val readiness = AiSetup.readiness(this)
        if (!readiness.ok) {
            _dict.value = _dict.value.copy(
                answer = LocalIntentRouter.reply(text, null, isBengali(), requestMayHaveBeenSent = false),
                providerLabel = "",
                note = AiReadinessCore.message(readiness.code, readiness.provider?.label ?: "AI", isBengali()),
                needsSetup = AiReadinessCore.needsSettings(readiness.code),
            )
            return
        }
        val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
        _dict.value = _dict.value.copy(
            busy = true, busyLabel = tr("উত্তর তৈরি হচ্ছে…", "Asking your AI…"), answer = "", note = "", needsSetup = false,
        )
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiSetup.router(this@AssistantOverlayService)
                        .complete(BubbleAi.generalSystemPrompt(bn), BubbleAi.generalMessages(text))
                }
            }.onSuccess { a ->
                _dict.value = _dict.value.copy(
                    answer = a.text.trim(), providerLabel = BubbleAi.providerLabel(a),
                    busy = false, busyLabel = "",
                )
            }.onFailure { e ->
                val why = if (e is AiException) AiSetup.explain(e) else (e.message ?: e.javaClass.simpleName)
                val sent = (e as? AiException)?.kind != AiException.Kind.NOT_CONFIGURED
                _dict.value = _dict.value.copy(
                    answer = LocalIntentRouter.reply(text, null, isBengali(), requestMayHaveBeenSent = sent),
                    busy = false, busyLabel = "",
                    note = tr("AI উত্তর দিতে পারেনি — নিচে অফলাইন উত্তর: ", "The AI could not answer — offline fallback below: ") + why,
                    needsSetup = true,
                )
            }
        }
    }

    // ------------------------------------------------------------------ commands (v0.7.0)

    /**
     * What a long press on ⏎ does: take the sentence that is in the bubble and try to *do* it instead of
     * inserting it. Offline patterns first (works with no key, no internet, instantly), the model only
     * when they find nothing.
     */
    private fun runCommand(text: String) {
        val said = text.trim()
        if (said.isBlank()) { toast(tr("কিছু বলা হয়নি", "Nothing was said")); return }
        if (!SettingsStore.state.value.commandsEnabled) {
            _dict.value = _dict.value.copy(note = tr("কমান্ড বন্ধ আছে (সেটিংস → কমান্ড)", "Commands are off (Settings → Commands)"))
            return
        }
        val bn = isBengali()
        lifecycleScope.launch {
            var spec = CommandParser.offline(said)
            var source = "offline"
            if (!CommandParser.confident(spec) && AiSetup.readiness(this@AssistantOverlayService).ok) {
                _dict.value = _dict.value.copy(busy = true, busyLabel = tr("বুঝছি…", "Understanding…"), choices = emptyList())
                val parsed = withContext(Dispatchers.IO) {
                    runCatching {
                        val answer = AiSetup.router(this@AssistantOverlayService)
                            .complete(CommandParser.prompt(bn), CommandParser.messages(said, bn))
                        CommandJson.parse(answer.text, said)
                    }.getOrNull()
                }
                _dict.value = _dict.value.copy(busy = false, busyLabel = "")
                if (parsed != null) { spec = parsed; source = "model" }
            }
            handleCommand(spec, source)
        }
    }

    /** Runs the pure decision layer and shows whatever it asks for. */
    private fun handleCommand(specIn: CommandSpec, source: String) {
        var spec = specIn
        val bn = isBengali()
        var chainAnnounce = ""
        // v0.10.0 — chain: "শেষ যে মেসেজ দিয়েছে তাকে ফোন করো". The pronoun is replaced by a real, visible
        // name from the notification log BEFORE anything is confirmed; no messages → honest refusal.
        if (ChainResolver.isChain(spec)) {
            if (!NotificationAccess.isGranted(this)) {
                _dict.value = _dict.value.copy(
                    note = tr(
                        "‘শেষ যে মেসেজ দিয়েছে’ জানতে নোটিফিকেশন অ্যাক্সেস দরকার — সেটিংস → কমান্ডে চালু করুন।",
                        "Knowing who messaged last needs notification access — enable it in Settings → Commands.",
                    ),
                    needsSetup = true, choices = emptyList(), answer = "",
                )
                ensureBubbleVisible()
                commandLog.record(spec.raw, spec.intent, spec.confidence, source, "blocked", false, "no notification access")
                return
            }
            when (val chain = ChainResolver.resolve(spec, notifStore.sendersSince(dayStart()))) {
                is ChainOutcome.Resolved -> {
                    spec = chain.spec
                    chainAnnounce = ChainResolver.announced(spec, chain.name, bn)
                }
                is ChainOutcome.NoSenders -> {
                    _dict.value = _dict.value.copy(
                        note = tr(
                            "আজ কারও মেসেজ পাওয়া যায়নি, তাই ‘শেষ যে মেসেজ দিয়েছে’ কাউকে পাওয়া গেল না।",
                            "Nobody has messaged today, so there is no ‘last person’ to use.",
                        ),
                        choices = emptyList(), answer = "",
                    )
                    ensureBubbleVisible()
                    commandLog.record(spec.raw, spec.intent, spec.confidence, source, "none", false, "no senders")
                    return
                }
                ChainOutcome.NotAChain -> Unit
            }
        }
        // v0.9: a call needs a real number — resolve the contact before the confirmation card so the card
        // shows the name AND the number that will be dialled.
        if (spec.intent == CmdIntent.CALL_PLACE) {
            val q = spec.slot(Slot.CONTACT).orEmpty()
            val number = ContactMatch.sanitizeNumber(q)
            if (number != null) {
                spec = spec.copy(slots = spec.slots + (Slot.CONTACT to number))
            } else {
                if (!Permissions.contacts(this)) {
                    _dict.value = _dict.value.copy(
                        note = tr(
                            "কন্টাক্ট পড়ার অনুমতি নেই — সেটিংস → কমান্ডে ‘ফোন/কল’ অনুমতি দিন, অথবা নম্বরটাই বলুন।",
                            "No contacts permission — grant it in Settings → Commands, or say the number itself.",
                        ),
                        needsSetup = true, choices = emptyList(), answer = "",
                    )
                    ensureBubbleVisible()
                    commandLog.record(spec.raw, spec.intent, spec.confidence, source, "blocked", false, "no contacts permission")
                    return
                }
                val hits = contacts.resolve(q)
                when {
                    hits.isEmpty() -> {
                        _dict.value = _dict.value.copy(
                            note = tr("‘$q’ নামে কোনো কন্টাক্ট পাইনি।", "No contact called ‘$q’ was found."),
                            choices = emptyList(), answer = "",
                        )
                        ensureBubbleVisible()
                        commandLog.record(spec.raw, spec.intent, spec.confidence, source, "none", false, "no contact")
                        return
                    }
                    hits.size > 1 -> {
                        showChoices(
                            tr("কোন নম্বরে ফোন করবো?", "Which number?"),
                            hits.take(4).map { Choice(it.display, CmdIntent.CALL_PLACE, mapOf(Slot.CONTACT to it.number)) },
                            spec, source, ok = true, strategy = "clarify",
                        )
                        return
                    }
                    else -> spec = spec.copy(slots = spec.slots + (Slot.CONTACT to hits.first().number))
                }
            }
        }
        // v0.8: a reply needs a real sender from today's notifications — resolve it before confirming.
        if (spec.intent == CmdIntent.MSG_REPLY) {
            if (!NotificationAccess.isGranted(this)) {
                _dict.value = _dict.value.copy(
                    note = tr(
                        "নোটিফিকেশন অ্যাক্সেস নেই — সেটিংস → কমান্ডে গিয়ে চালু করুন।",
                        "Notification access is off — enable it in Settings → Commands.",
                    ),
                    needsSetup = true, choices = emptyList(), answer = "",
                )
                ensureBubbleVisible()
                commandLog.record(spec.raw, spec.intent, spec.confidence, source, "blocked", false, "no notification access")
                return
            }
            val senders = notifStore.sendersSince(dayStart())
            if (senders.isEmpty()) {
                _dict.value = _dict.value.copy(
                    note = tr("আজ কোনো প্রেরকের মেসেজ নেই, তাই উত্তর দেওয়ার কেউ নেই।", "No messages today, so there is nobody to reply to."),
                    choices = emptyList(), answer = "",
                )
                ensureBubbleVisible()
                commandLog.record(spec.raw, spec.intent, spec.confidence, source, "none", false, "no senders")
                return
            }
            val q = spec.slot(Slot.CONTACT).orEmpty()
            val hits = SenderMatch.resolve(q, senders)
            when {
                hits.isEmpty() -> {
                    showChoices(
                        tr("‘$q’ নামে আজ কাউকে মেসেজ করতে দেখিনি। কাকে পাঠাবো?", "Nobody called ‘$q’ messaged today. Who should I send it to?"),
                        senders.take(4).map { Choice(it, CmdIntent.MSG_REPLY, spec.slots + (Slot.CONTACT to it)) },
                        spec, source, ok = true, strategy = "clarify",
                    )
                    return
                }
                hits.size > 1 -> {
                    showChoices(
                        tr("কাকে পাঠাবো?", "Which one?"),
                        hits.take(4).map { Choice(it, CmdIntent.MSG_REPLY, spec.slots + (Slot.CONTACT to it)) },
                        spec, source, ok = true, strategy = "clarify",
                    )
                    return
                }
                q != hits.first() -> spec = spec.copy(slots = spec.slots + (Slot.CONTACT to hits.first()))
            }
        }
        val candidates: Map<Slot, List<String>> = when {
            // v0.10.0: "গান" alone is ambiguous between play and pause — offer the actions, never guess.
            spec.intent == CmdIntent.MEDIA && spec.slot(Slot.MEDIA).isNullOrBlank() -> mapOf(Slot.MEDIA to MediaText.choices())
            spec.intent == CmdIntent.APP_LAUNCH && spec.slot(Slot.APP).isNullOrBlank() -> emptyMap()
            spec.intent == CmdIntent.APP_LAUNCH -> {
                val hits = launcher.resolve(spec.slot(Slot.APP).orEmpty())
                if (hits.size > 1) mapOf(Slot.APP to hits.map { it.label }) else emptyMap()
            }
            else -> emptyMap()
        }
        when (val d = CommandPipeline.decide(spec, bn, candidates)) {
            is CommandDecision.Clarify -> {
                pendingSpec = null
                pendingSource = source
                showChoices(d.question, d.choices, spec, source, ok = true, strategy = "clarify")
            }
            is CommandDecision.Confirm -> {
                pendingSpec = spec
                pendingSource = source
                pendingChoices = listOf(d.confirm, d.cancel)
                val question = if (chainAnnounce.isBlank()) d.question else "$chainAnnounce\n${d.question}"
                _dict.value = _dict.value.copy(
                    note = question, answer = "", providerLabel = "", needsSetup = false,
                    busy = false, busyLabel = "",
                    choices = listOf(d.confirm.label, d.cancel.label),
                )
                ensureBubbleVisible()
                commandLog.record(spec.raw, spec.intent, spec.confidence, source, "confirm", true, CommandPipeline.describe(spec, bn))
            }
            CommandDecision.Execute -> executeCommand(spec, source)
        }
    }

    private fun showChoices(
        question: String,
        choices: List<Choice>,
        spec: CommandSpec,
        source: String,
        ok: Boolean,
        strategy: String,
    ) {
        pendingChoices = choices
        _dict.value = _dict.value.copy(
            note = question, answer = "", providerLabel = "", needsSetup = false,
            busy = false, busyLabel = "", choices = choices.map { it.label },
        )
        ensureBubbleVisible()
        commandLog.record(spec.raw, spec.intent, spec.confidence, source, strategy, ok, question)
    }

    /** A button in a clarification / confirmation card was tapped. */
    private fun onChoicePicked(index: Int) {
        val choices = pendingChoices
        if (index !in choices.indices) { clearChoices(); return }
        val choice = choices[index]
        // Confirmation of a pending destructive command.
        val waiting = pendingSpec
        if (waiting != null) {
            clearChoices()
            if (choice.intent == CmdIntent.UNKNOWN) {
                _dict.value = _dict.value.copy(note = tr("বাতিল করা হলো", "Cancelled"))
                commandLog.record(waiting.raw, waiting.intent, waiting.confidence, pendingSource, "confirm", false, "cancelled")
            } else {
                executeCommand(waiting.copy(slots = waiting.slots + choice.slots), pendingSource)
            }
            return
        }
        clearChoices()
        if (choice.intent == CmdIntent.UNKNOWN) { _dict.value = _dict.value.copy(note = tr("বাতিল", "Cancelled")); return }
        // A clarified intent usually still needs its slot, so go back through the pipeline.
        handleCommand(CommandSpec(choice.intent, choice.slots, 1.0, choice.label), "choice")
    }

    private fun clearChoices() {
        pendingChoices = emptyList()
        pendingSpec = null
        if (_dict.value.choices.isNotEmpty()) _dict.value = _dict.value.copy(choices = emptyList())
    }

    private fun ensureBubbleVisible() {
        if (dictView == null) {
            _dict.value = _dict.value.copy(visible = true)
            addDictBubble()
            updateBubble()
        }
    }

    /** Actually runs an available, fully-specified command. Every branch logs its strategy. */
    private fun executeCommand(spec: CommandSpec, source: String) {
        val bn = isBengali()
        fun done(text: String, ok: Boolean, strategy: String) {
            autoSpeak(spec, text, ok)
            _dict.value = _dict.value.copy(
                answer = text, note = "", providerLabel = "", needsSetup = false,
                busy = false, busyLabel = "", choices = emptyList(), undoLabel = "",
            )
            undoLog.clear(); undoRun = null
            ensureBubbleVisible()
            commandLog.record(spec.raw, spec.intent, spec.confidence, source, strategy, ok, text)
        }

        when (spec.intent) {
            CmdIntent.APP_LAUNCH -> {
                val query = spec.slot(Slot.APP).orEmpty()
                val hits = launcher.resolve(query)
                when {
                    hits.isEmpty() -> done(
                        tr("‘$query’ নামে কোনো অ্যাপ পাইনি।", "No app called ‘$query’ was found."), false, "launcher",
                    )
                    hits.size > 1 && spec.confidence < 0.99 -> {
                        pendingChoices = hits.map { Choice(it.label, CmdIntent.APP_LAUNCH, mapOf(Slot.APP to it.label)) }
                        pendingSpec = null
                        _dict.value = _dict.value.copy(
                            note = tr("কোন অ্যাপ?", "Which app?"), choices = pendingChoices.map { it.label }, answer = "",
                        )
                        ensureBubbleVisible()
                        commandLog.record(spec.raw, spec.intent, spec.confidence, source, "clarify", true, "ambiguous app")
                    }
                    else -> {
                        val app = hits.first()
                        val ok = launcher.launch(app.pkg)
                        done(
                            if (ok) tr("${app.label} খোলা হলো ✓", "${app.label} opened ✓")
                            else tr("${app.label} খোলা যায়নি", "Could not open ${app.label}"),
                            ok, "launcher",
                        )
                    }
                }
            }

            CmdIntent.MEMORY_STORE -> {
                val text = spec.slot(Slot.TEXT).orEmpty()
                val e = memory.add(text)
                done(
                    if (e == null) tr("কিছু মনে রাখা যায়নি", "Nothing to remember")
                    else MemoryText.storedAnswer(e.text, bn),
                    e != null, "memory",
                )
                // v0.10.0 undo: the row exists, so it can genuinely be deleted again.
                if (e != null) {
                    offerUndo(
                        label = tr("মনে রাখা: ${e.text.take(40)}", "remembered: ${e.text.take(40)}"),
                        kind = UndoKind.MEMORY_ADD,
                    ) { memory.forget(e.id) }
                }
            }

            CmdIntent.MEMORY_RECALL -> {
                val entries = memory.all()
                val found = MemoryText.search(entries, spec.slot(Slot.QUERY) ?: spec.raw)
                val answer = MemoryText.recallAnswer(found, entries.size, bn)
                done(answer, true, "memory")
                if (SettingsStore.state.value.speakResults) speaker.speak(answer, bn)
            }

            CmdIntent.SPEAK -> {
                val text = spec.slot(Slot.TEXT).orEmpty()
                val ok = speaker.speak(text, bn)
                done(
                    if (ok) tr("পড়ে শোনানো হচ্ছে…", "Reading it aloud…")
                    else tr("এই ফোনে টেক্সট-টু-স্পিচ বাংলা চালু নেই — লেখা হিসেবেই দেখানো হলো।", "This phone has no usable speech voice — showing the text instead."),
                    ok, "tts",
                )
            }

            CmdIntent.CALL_PLACE -> {
                val number = spec.slot(Slot.CONTACT).orEmpty()
                val out = calls.place(number)
                done(
                    when {
                        out.ok && out.strategy == CallStrategy.TELECOM -> tr("$number নম্বরে কল করা হচ্ছে ✓", "Calling $number ✓")
                        out.ok && out.strategy == CallStrategy.DIALER_OPENED ->
                            tr("কল করার অনুমতি নেই — ডায়ালারে $number বসানো হয়েছে, আপনি চাপ দিন।", "No call permission — the dialer is open with $number filled in.")
                        else -> tr("কল করা যায়নি (অনুমতি বা সিস্টেম বাধা)।", "Could not place the call (permission or system block).")
                    },
                    out.ok, "place:${out.strategy.name.lowercase()}",
                )
                if (!out.ok) _dict.value = _dict.value.copy(needsSetup = true)
            }

            CmdIntent.CALL_ANSWER -> {
                val out = calls.answer()
                done(
                    when {
                        out.ok && out.strategy == CallStrategy.TELECOM -> tr("কল ধরা হয়েছে ✓", "Call answered ✓")
                        out.ok -> tr("কল ধরা হয়েছে ✓ (${out.detail})", "Call answered ✓ (${out.detail})")
                        !calls.canAnswer() -> tr(
                            "কল ধরার অনুমতি নেই — সেটিংস → কমান্ডে দিন, অথবা নিজে ধরুন।",
                            "No answer permission — grant it in Settings → Commands, or pick up yourself.",
                        )
                        else -> tr("কল ধরতে পারিনি — হয় কলটি বন্ধ হয়ে গেছে, নয় অ্যাপটি উত্তর বাটন দেয়নি। নিজে ধরুন।",
                            "Could not answer — the call ended, or the app exposed no answer button. Please pick up manually.")
                    },
                    out.ok, "answer:${out.strategy.name.lowercase()}",
                )
                if (!out.ok) _dict.value = _dict.value.copy(needsSetup = true)
            }

            CmdIntent.CALL_DECLINE -> {
                val out = calls.decline()
                done(
                    when {
                        out.ok -> tr("কল কাটা হয়েছে ✓", "Call declined ✓")
                        else -> tr("কল কাটা যায়নি — নিজে কেটে দিন।", "Could not decline the call — please end it manually.")
                    },
                    out.ok, "decline:${out.strategy.name.lowercase()}",
                )
                if (!out.ok) _dict.value = _dict.value.copy(needsSetup = true)
            }

            CmdIntent.MSG_DIGEST -> {
                if (!NotificationAccess.isGranted(this)) {
                    val msg = tr("নোটিফিকেশন অ্যাক্সেস নেই — সেটিংস → কমান্ডে চালু করুন।", "Notification access is off — enable it in Settings → Commands.")
                    done(msg, false, "blocked")
                    _dict.value = _dict.value.copy(needsSetup = true, note = msg)
                } else {
                    val text = Digest.summary(notifStore.all(), dayStart(), bn)
                    done(text, true, "digest")
                    if (SettingsStore.state.value.speakResults) speaker.speak(text, bn)
                }
            }

            CmdIntent.MSG_READ -> {
                if (!NotificationAccess.isGranted(this)) {
                    val msg = tr("নোটিফিকেশন অ্যাক্সেস নেই — সেটিংস → কমান্ডে চালু করুন।", "Notification access is off — enable it in Settings → Commands.")
                    done(msg, false, "blocked")
                    _dict.value = _dict.value.copy(needsSetup = true, note = msg)
                } else {
                    val text = Digest.readAloud(notifStore.all(), 5, bn)
                    val ok = speaker.speak(text, bn)
                    done(if (ok) text else tr("$text\n\n(এই ফোনে বাংলা ভয়েস নেই — লেখায় দেখানো হলো।)", "$text\n\n(This phone has no Bangla voice — shown as text.)"), true, "tts")
                }
            }

            CmdIntent.MSG_REPLY -> {
                val sender = spec.slot(Slot.CONTACT).orEmpty()
                val body = spec.slot(Slot.TEXT).orEmpty()
                val live = RemoteReply.find(sender)
                val reason = RemoteReply.send(this, live, body)
                if (reason == null) {
                    done(tr("‘$sender’ কে পাঠানো হয়েছে ✓", "Sent to ‘$sender’ ✓"), true, "directReply")
                } else {
                    // The trailing comma belongs to done(...), so the message is built first.
                    val msg = when (reason) {
                        "no_notification" -> tr(
                            "‘$sender’-এর নোটিফিকেশন আর নেই — অ্যাপটি একবার খুলে দেখুন, তারপর আবার বলুন।",
                            "‘$sender’ has no live notification — open the app once, then try again.",
                        )
                        "no_action" -> tr(
                            "এই নোটিফিকেশনে সরাসরি উত্তর দেওয়ার সুবিধা নেই — অ্যাপ থেকে পাঠাতে হবে।",
                            "That notification offers no direct reply — it has to be sent from the app.",
                        )
                        else -> tr("উত্তর পাঠানো যায়নি (অনুমতি/সিস্টেম বাধা)।", "The reply was blocked (permission/system).")
                    }
                    done(msg, false, "directReply")
                }
            }

            CmdIntent.MEDIA -> {
                val action = MediaText.action(spec.slot(Slot.MEDIA).orEmpty())
                if (action == null) {
                    done(tr("কোন কাজটা করবো ঠিক বুঝিনি।", "I did not catch which media action you meant."), false, "media:none")
                } else {
                    val r = MediaControl.perform(this, action)
                    when {
                        r.ok && r.detail == "already" -> done(
                            when (action) {
                                com.voicedock.ai.media.MediaAction.PAUSE -> tr("মিউজিক আগেই থামানো আছে।", "The music is already paused.")
                                com.voicedock.ai.media.MediaAction.PLAY -> tr("মিউজিক আগেই চলছে।", "The music is already playing.")
                                else -> tr("কিছু বদলায়নি।", "Nothing changed.")
                            },
                            true, "media:already",
                        )
                        r.ok -> {
                            done(MediaText.done(action, bn, r.title), true, "media:${action.name.lowercase()}")
                            // Undo only where the inverse is real (play<->pause, next<->previous).
                            val inv = MediaText.inverse(action)
                            if (inv != null) {
                                offerUndo(
                                    label = tr("মিউজিক: ${MediaText.label(action)}", "media: ${MediaText.label(action)}"),
                                    kind = UndoKind.MEDIA,
                                    mediaAction = inv.name,
                                ) { MediaControl.perform(this, inv).ok }
                            }
                        }
                        else -> {
                            done(MediaText.failed(bn, r.detail), false, "media:none")
                            if (r.detail == "no_access") _dict.value = _dict.value.copy(needsSetup = true)
                        }
                    }
                }
            }

            CmdIntent.UNDO -> {
                val entry = undoLog.take()
                val run = undoRun
                undoRun = null
                if (entry == null || run == null) {
                    done(UndoText.nothing(bn), false, "undo:none")
                } else {
                    val ok = runCatching { run() }.getOrDefault(false)
                    _dict.value = _dict.value.copy(undoLabel = "")
                    done(
                        if (ok) UndoText.done(entry, bn) else tr("ফিরিয়ে নেওয়া যায়নি।", "Could not undo it."),
                        ok, "undo:${entry.kind.name.lowercase()}",
                    )
                }
            }

            CmdIntent.ASK_AI -> {
                _dict.value = _dict.value.copy(liveText = spec.slot(Slot.QUERY) ?: spec.raw, choices = emptyList())
                askDictated()
                commandLog.record(spec.raw, spec.intent, spec.confidence, source, "ai", true, "")
            }

            else -> done(
                tr("এই কমান্ডটি এখনো চালু হয়নি।", "That command is not enabled yet."), false, "none",
            )
        }
    }

    // ------------------------------------------------------------------ voice out (v1.1.0)

    /**
     * A one-word greeting after the wake word, so the assistant *answers* instead of staying silent.
     * Deliberately short: it must not talk over the user starting to speak.
     */
    private fun speakGreeting() {
        val bn = isBengali()
        if (!AssistantModeCore.greetsAloud(SettingsStore.state.value.mode)) return
        if (!SettingsStore.state.value.speakResults) return
        runCatching { speaker.speak(tr("বলুন", "Yes?"), bn) }
    }

    /**
     * Reads a command result aloud when the mode allows it. Two reasons to stay quiet:
     *  * the intent already speaks for itself ([CmdIntent.SPEAK], recall, digest, read-aloud) — otherwise
     *    the same sentence would be heard twice;
     *  * calls: never speak into a live call.
     */
    private fun autoSpeak(spec: CommandSpec, text: String, ok: Boolean) {
        if (!ok || text.isBlank()) return
        val s = SettingsStore.state.value
        if (!AssistantModeCore.speaksResults(s.mode, s.speakResults)) return
        when (spec.intent) {
            CmdIntent.SPEAK, CmdIntent.MEMORY_RECALL, CmdIntent.MSG_DIGEST, CmdIntent.MSG_READ -> return
            CmdIntent.CALL_PLACE, CmdIntent.CALL_ANSWER, CmdIntent.CALL_DECLINE -> return
            else -> Unit
        }
        runCatching { speaker.speak(text, isBengali()) }
    }

    // ------------------------------------------------------------------ undo (v0.10.0)

    /** Offers a one-step undo for an action that can genuinely be reversed. */
    private fun offerUndo(label: String, kind: UndoKind, mediaAction: String = "", run: () -> Boolean) {
        undoLog.push(UndoEntry(java.util.UUID.randomUUID().toString(), label, System.currentTimeMillis(), kind, mediaAction))
        undoRun = run
        _dict.value = _dict.value.copy(undoLabel = UndoText.chip(isBengali()))
    }

    /** The ↩ chip (or "আনডু" spoken) was used. One shot: an undo cannot be undone. */
    private fun runUndo() {
        val entry = undoLog.take()
        val run = undoRun
        undoRun = null
        val bn = isBengali()
        if (entry == null || run == null) {
            _dict.value = _dict.value.copy(undoLabel = "", answer = UndoText.nothing(bn), note = "", needsSetup = false)
            ensureBubbleVisible()
            return
        }
        val ok = runCatching { run() }.getOrDefault(false)
        _dict.value = _dict.value.copy(
            undoLabel = "", note = "", needsSetup = false,
            answer = if (ok) UndoText.done(entry, bn) else tr("ফিরিয়ে নেওয়া যায়নি।", "Could not undo it."),
        )
        ensureBubbleVisible()
        commandLog.record("undo", CmdIntent.UNDO, 1.0, "tap", "undo:${entry.kind.name.lowercase()}", ok, entry.label)
    }

    // ------------------------------------------------------------------ activation

    private fun activate() {
        val s = SettingsStore.state.value
        closeWindows(reset = false)
        scrim?.show(s.assistantDimBlur, s.assistantDimBlur)
        _ui.value = AssistantUiState(phase = AssistantPhase.SCANNING, busy = true, localOnly = s.localOnly)
        updateBubble()
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            if (s.assistantAutoScan) delay(240)
            val snapshot = withContext(Dispatchers.IO) {
                if (s.assistantAutoScan && s.assistantScreenContext)
                    ProbeRunner.probe(InsertAccessibilityService.instance, this@AssistantOverlayService)
                else ContextSnapshot(probeOk = true) // no consent / auto-scan off -> generic assistant
            }
            val current = SettingsStore.state.value
            val assessment = if (!current.assistantScreenContext || !current.assistantAutoScan)
                ContextClassifier.noConsentAssessment()
            else ContextClassifier.assess(snapshot, current.assistantPrefs().policy(), AgentSafetyOracle)

            val cu = if (s.assistantCatchUp) CatchUpSummary.build(emptyList(), System.currentTimeMillis(), 6) else null
            val bn = s.uiLanguage == UiLanguage.BN
            val greeting = AssistantGreeting.build(assessment.kind, cu, bn, assessment.snapshot.appLabel)
            val phase = if (assessment.kind == ContextKind.FIELD_FOUND || assessment.kind == ContextKind.CONTENT_NO_FIELD)
                AssistantPhase.CONTEXT_CARD else AssistantPhase.CHAT
            _ui.value = AssistantUiState(phase, assessment, greeting, cu, localOnly = s.localOnly)
            showWindow(phase)
            updateBubble()
        }
    }

    private fun showWindow(phase: AssistantPhase) {
        removeCard(); removeChat()
        when (phase) {
            AssistantPhase.CONTEXT_CARD -> addCard()
            AssistantPhase.CHAT -> addChat()
            else -> Unit
        }
    }

    private fun addCard() {
        if (cardView != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view); owner.create()
        view.setContent {
            val state by ui.collectAsState()
            ContextCard(state, ::onAction, ::switchToChat, { closeWindows() }, ::openSettings, ::selectDraft)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(view, p) }.onSuccess { cardView = view; cardOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "card add failed: ${it.javaClass.simpleName}") }
    }

    /** Focusable window: the user can actually TYPE here (v0.5 panel was NOT_FOCUSABLE). */
    private fun addChat() {
        if (chatView != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view); owner.create()
        view.setContent {
            val state by ui.collectAsState()
            AssistantBubble(state, ::onAction, ::onSubmit, ::selectDraft, { closeWindows() }, ::openSettings,
                onPauseDictation = { VoiceDock.dictation(this).pause() },
                onResumeDictation = { VoiceDock.dictation(this).resume() },
                onFinishDictation = { VoiceDock.dictation(this).finish() })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(view, p) }.onSuccess { chatView = view; chatOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "chat add failed: ${it.javaClass.simpleName}") }
    }

    private fun switchToChat() {
        _ui.value = _ui.value.copy(phase = AssistantPhase.CHAT)
        showWindow(AssistantPhase.CHAT)
        updateBubble()
    }

    private fun removeCard() {
        cardView?.let { runCatching { wm.removeView(it) } }
        cardOwner?.destroy(); cardView = null; cardOwner = null
    }

    private fun removeChat() {
        chatView?.let { runCatching { wm.removeView(it) } }
        chatOwner?.destroy(); chatView = null; chatOwner = null
    }

    private fun closeWindows(reset: Boolean = true) {
        probeJob?.cancel(); probeJob = null
        removeCard(); removeChat(); scrim?.hide()
        if (reset) _ui.value = AssistantUiState()
        updateBubble()
    }

    private fun closeEverything() {
        probeJob?.cancel()
        removeDictBubble()
        _dict.value = DictationBubbleState()
        closeWindows()
        removeBubble()
        scrim?.detach()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ actions

    private fun onAction(a: AssistantAction) {
        val s = SettingsStore.state.value
        val bn = s.uiLanguage == UiLanguage.BN
        when (a) {
            AssistantAction.ASK_QUESTION -> switchToChat()
            AssistantAction.DICTATE -> startDictation()
            AssistantAction.DRAFT_REPLY -> draftReply("")
            AssistantAction.INSERT_APPROVED -> insertApprovedDraft()
            AssistantAction.COPY -> {
                val text = _ui.value.draft.ifBlank { _ui.value.answer }
                if (text.isBlank()) { toast(tr("কপি করার মতো লেখা নেই", "Nothing to copy")); return }
                ClipboardInsertion.insert(this, text)
                toast(tr("কপি হয়েছে", "Copied"))
            }
            AssistantAction.EXPLAIN_SCREEN -> explainScreen("")
            AssistantAction.SUMMARISE -> explainScreen(
                tr("এই স্ক্রিনের মূল কথাগুলো সহজ ভাষায় সংক্ষেপে বলো।", "Summarise the key points of this screen in simple words."),
            )
            AssistantAction.WHY_BLOCKED -> {
                val reason = _ui.value.assessment?.blockReason
                _ui.value = _ui.value.copy(answer = if (reason != null) ContextPolicy.blockExplanation(reason, bn) else "")
                switchToChat()
            }
            AssistantAction.OPEN_SETTINGS -> openSettings()
            else -> {
                _ui.value = _ui.value.copy(answer = LocalIntentRouter.reply("", a, bn))
                switchToChat()
            }
        }
    }

    /** Close our focusable chat, then verify the original target again before touching another app. */
    private fun insertApprovedDraft() {
        val state = _ui.value
        val text = state.draft
        if (text.isBlank()) { toast(tr("আগে একটি খসড়া তৈরি করুন", "Create a draft first")); return }
        val target = state.assessment?.takeIf { it.kind == ContextKind.FIELD_FOUND }?.snapshot?.pkg.orEmpty()
        closeWindows()
        lifecycleScope.launch {
            // Give the app underneath a chance to regain focus; never guess a target.
            delay(250)
            val settings = SettingsStore.state.value
            val snapshot = if (target.isNotBlank() && settings.assistantScreenContext)
                withContext(Dispatchers.IO) { ProbeRunner.probe(InsertAccessibilityService.instance, this@AssistantOverlayService) }
            else ContextSnapshot(probeOk = false)
            val assessment = ContextClassifier.assess(snapshot, settings.assistantPrefs().policy(), AgentSafetyOracle)
            if (assessment.kind != ContextKind.FIELD_FOUND || snapshot.pkg != target || snapshot.focusedField == null) {
                ClipboardInsertion.insert(this@AssistantOverlayService, text)
                if (text == lastDictationDraft) VoiceDock.dictation(this@AssistantOverlayService).markInserted("clipboard")
                toast(tr("নিরাপদ সক্রিয় ঘর পাওয়া যায়নি — কপি করা হলো", "No verified focused field — copied instead"))
                return@launch
            }
            val out = TextInserter.insert(this@AssistantOverlayService, text, expectedPackage = target)
            toast(tr(out.result.bn, out.result.en))
            if (text == lastDictationDraft) VoiceDock.dictation(this@AssistantOverlayService).markInserted(out.destination)
        }
    }

    private fun onSubmit(text: String) {
        if (text.isBlank()) return
        val action = LocalIntentRouter.route(text)
        when (action) {
            // A typed instruction is not the separate approval tap promised by the UI.
            AssistantAction.INSERT_APPROVED -> {
                _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = tr("খসড়ার ‘বসাও’ বোতাম চাপুন", "Tap Insert on the visible draft"))
            }
            AssistantAction.COPY, AssistantAction.EXPLAIN_SCREEN, AssistantAction.SUMMARISE -> {
                _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = "")
                onAction(action)
            }
            AssistantAction.DRAFT_REPLY -> draftReply(text)
            AssistantAction.CATCH_UP, AssistantAction.TODAY_TASKS, AssistantAction.REMEMBER, AssistantAction.GENERAL_HELP -> {
                val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
                _ui.value = _ui.value.copy(answer = LocalIntentRouter.reply(text, action, bn), providerLabel = "", note = "")
                switchToChat()
            }
            else -> askAi(text)
        }
    }

    private fun askAi(text: String) {
        val settings = SettingsStore.state.value
        val bn = settings.uiLanguage == UiLanguage.BN
        if (SecretDetector.isSecret(text)) {
            _ui.value = _ui.value.copy(answer = "", providerLabel = "", busy = false,
                note = tr("কোড/পিন-সদৃশ লেখা AI-তে পাঠানো হয়নি।", "Code/PIN-like text was not sent to AI."), localOnly = settings.localOnly)
            switchToChat()
            return
        }
        _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = "", busy = true,
            busyLabel = tr("AI উত্তর তৈরি করছে…", "Asking your configured AI…"), localOnly = settings.localOnly)
        switchToChat()
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    AiSetup.router(this@AssistantOverlayService).complete(
                        BubbleAi.generalSystemPrompt(bn), BubbleAi.generalMessages(text),
                    )
                }
                _ui.value = _ui.value.copy(answer = answer.text, providerLabel = BubbleAi.providerLabel(answer),
                    busy = false, busyLabel = "", note = "")
            } catch (e: Exception) {
                val current = SettingsStore.state.value
                val localOnly = e is AiException && e.kind == AiException.Kind.NOT_CONFIGURED
                val notice = if (localOnly)
                    tr("কোনো অনুমোদিত প্রোভাইডার প্রস্তুত নেই; অফলাইন নিয়মে উত্তর দিলাম।", "No usable approved provider; using offline rules.")
                else tr("AI অনুরোধ সম্পূর্ণ হয়নি; এটি প্রোভাইডারে পৌঁছে থাকতে পারে। অফলাইন উত্তর দেখানো হলো।", "The AI request did not complete; the provider may have received it. Showing an offline fallback.")
                _ui.value = _ui.value.copy(
                    answer = LocalIntentRouter.reply(text, null, current.uiLanguage == UiLanguage.BN, requestMayHaveBeenSent = !localOnly),
                    providerLabel = "", busy = false, busyLabel = "", note = notice, localOnly = current.localOnly,
                )
            }
        }
    }

    /** Re-check the target after hiding VoiceDock, read it once, reject secrets, then ask an approved provider. */
    private fun draftReply(instruction: String) {
        val state = _ui.value
        val settings = SettingsStore.state.value
        val assessment = state.assessment
        val target = assessment?.takeIf { it.kind == ContextKind.FIELD_FOUND }?.snapshot?.pkg.orEmpty()
        val bn = settings.uiLanguage == UiLanguage.BN
        fun offlineDraft(message: String) {
            _ui.value = _ui.value.copy(
                draft = if (bn) "আপনার বার্তা পেয়েছি। দ্রুত দেখে জানাচ্ছি।" else "Got your message. I will check and get back to you shortly.",
                draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "", localOnly = settings.localOnly,
                note = message,
            )
            switchToChat()
        }
        if (!settings.assistantScreenContext || target.isBlank() || assessment?.kind != ContextKind.FIELD_FOUND) {
            offlineDraft(tr("যাচাইকৃত স্ক্রিন-প্রসঙ্গ নেই — অফলাইন খসড়া দেখানো হলো।", "No verified screen context; showing an offline template."))
            return
        }
        if (!AiSetup.ready(this)) {
            offlineDraft(tr("কোনো অনুমোদিত AI প্রোভাইডার প্রস্তুত নেই; স্ক্রিন পড়া বা পাঠানো হয়নি।", "No usable approved AI provider; the screen was not read or sent."))
            return
        }

        _ui.value = _ui.value.copy(answer = "", draft = "", draftOptions = emptyList(), providerLabel = "",
            busy = true, busyLabel = tr("অ্যাপ যাচাই করে খসড়া তৈরি হচ্ছে…", "Verifying the app and drafting replies…"),
            note = tr("স্ক্রিনের লেখা অনুমোদিত AI-তে যেতে পারে; এখনো কিছু পাঠানো বা বসানো হবে না।", "Visible conversation may go to your approved AI; nothing will be sent or inserted."),
            localOnly = settings.localOnly)
        closeWindows(reset = false)
        lifecycleScope.launch {
            try {
                delay(300)
                val service = InsertAccessibilityService.instance
                // screenText verifies that the same package is still active and reads content only once.
                val visible = withContext(Dispatchers.IO) {
                    service?.screenText(maxChars = 4000, expectedPackage = target).orEmpty()
                }
                if (visible.isBlank()) {
                    offlineDraft(tr("আগের অ্যাপ আর সামনে নেই বা স্ক্রিন পড়া যায়নি — অফলাইন খসড়া দেখানো হলো।", "The original app is no longer in front or could not be read; using an offline template."))
                    return@launch
                }
                if (SecretDetector.containsSecretScreenText(visible)) {
                    _ui.value = _ui.value.copy(draft = "", draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "",
                        note = tr("কোড/পিন-সদৃশ লেখা শনাক্ত হয়েছে; কোনো স্ক্রিনের লেখা AI-তে পাঠানো হয়নি।", "A code/PIN-like item was found; no screen text was sent to AI."))
                    switchToChat()
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    AiSetup.router(this@AssistantOverlayService).complete(
                        BubbleAi.draftSystemPrompt(bn), BubbleAi.draftMessages(instruction, visible),
                    )
                }
                val options = BubbleAi.parseDraftOptions(result.text)
                val chosen = options.firstOrNull().orEmpty()
                _ui.value = _ui.value.copy(draft = chosen, draftOptions = options, providerLabel = BubbleAi.providerLabel(result),
                    busy = false, busyLabel = "", note = tr("খসড়া তৈরি হয়েছে — এখনো কোথাও পাঠানো বা বসানো হয়নি।", "Drafts ready — nothing has been sent or inserted."))
                switchToChat()
            } catch (e: Exception) {
                val current = SettingsStore.state.value
                val notice = if (e is AiException && e.kind == AiException.Kind.NOT_CONFIGURED)
                    tr("কোনো ব্যবহারযোগ্য AI পাওয়া যায়নি; অফলাইন খসড়া দেখানো হলো।", "No usable AI provider; showing an offline template.")
                else tr("AI খসড়া তৈরি হয়নি; স্ক্রিনের লেখা প্রোভাইডারে পৌঁছে থাকতে পারে। অফলাইন টেমপ্লেট দেখানো হলো।", "AI drafting failed; the provider may have received the conversation. Showing an offline template.")
                _ui.value = _ui.value.copy(
                    draft = if (current.uiLanguage == UiLanguage.BN) "আপনার বার্তা পেয়েছি। দ্রুত দেখে জানাচ্ছি." else "Got your message. I will check and get back to you shortly.",
                    draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "", note = notice, localOnly = current.localOnly,
                )
                switchToChat()
            }
        }
    }

    private fun selectDraft(text: String) {
        if (text in _ui.value.draftOptions) _ui.value = _ui.value.copy(draft = text)
    }

    private fun startDictation() {
        if (!Permissions.mic(this)) {
            _ui.value = _ui.value.copy(note = tr("মাইক্রোফোনের অনুমতি দিন", "Grant microphone permission"))
            openSettings()
            return
        }
        val phase = VoiceDock.dictation(this).ui.value.phase
        if (phase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED, DictationPhase.PROCESSING)) {
            assistantDictationStarted = true
            _ui.value = _ui.value.copy(dictationPhase = phase)
            switchToChat()
            return
        }
        assistantDictationStarted = true
        lastDictationDraft = ""
        _ui.value = _ui.value.copy(note = tr("মাইক চালু করার চেষ্টা হচ্ছে…", "Starting the microphone…"), dictationPhase = phase)
        switchToChat()
        // The microphone service itself starts the session after it is foregrounded.
        OverlayService.start(this, OverlayService.ACTION_START_DICTATION)
        lifecycleScope.launch {
            delay(1500)
            if (assistantDictationStarted && !OverlayService.foregroundOk) {
                assistantDictationStarted = false
                _ui.value = _ui.value.copy(dictationPhase = null, note = tr("মাইক চালু হয়নি — VoiceDock খুলে আবার চেষ্টা করুন", "Microphone could not start — open VoiceDock and try again"))
            }
        }
    }

    private fun explainScreen(question: String) {
        val blocker = ScreenAssistant.blocker(this)
        if (blocker != null) {
            _ui.value = _ui.value.copy(note = tr("স্ক্রিন ব্যাখ্যার জন্য অনুমতি লাগে: ", "Screen explain needs permission: ") + blocker)
            return
        }
        _ui.value = _ui.value.copy(busy = true, note = tr("অনুমতি অনুযায়ী Gemini-তে পাঠানো হচ্ছে…", "Sending to Gemini with your permission…"))
        ScreenAssistant.dismiss()
        assistantHelpPending = true
        lastHelpAnswer = ""
        lifecycleScope.launch {
            ScreenAssistant.ask(
                this@AssistantOverlayService, question,
                hideOverlay = {
                    bubble?.visibility = View.INVISIBLE
                    cardView?.visibility = View.INVISIBLE
                    chatView?.visibility = View.INVISIBLE
                },
                showOverlay = {
                    bubble?.visibility = View.VISIBLE
                    cardView?.visibility = View.VISIBLE
                    chatView?.visibility = View.VISIBLE
                },
            )
        }
    }

    private fun observeDictation() {
        lifecycleScope.launch {
            VoiceDock.dictation(this@AssistantOverlayService).ui.collect { d ->
                val bubbleOn = _dict.value.visible
                if (bubbleOn) {
                    // The bubble grows with the text: recognised words first, the cleaned draft at REVIEW.
                    val live = when {
                        dictFormatted -> _dict.value.liveText
                        d.phase == DictationPhase.REVIEW && d.draft.isNotBlank() -> d.draft
                        else -> d.live
                    }
                    _dict.value = _dict.value.copy(phase = d.phase, liveText = live, level = d.level)
                    updateBubble()
                }
                if (assistantDictationStarted) {
                    _ui.value = _ui.value.copy(dictationPhase = d.phase)
                    if (d.phase == DictationPhase.REVIEW) {
                        assistantDictationStarted = false
                        if (d.draft.isNotBlank() && d.draft != lastDictationDraft) {
                            lastDictationDraft = d.draft
                            if (!bubbleOn) {
                                _ui.value = _ui.value.copy(
                                    draft = d.draft,
                                    note = tr("রিভিউ করুন, তারপর ‘বসাও’ চাপুন।", "Review, then tap ‘Insert’."),
                                )
                                switchToChat()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeScreenHelp() {
        lifecycleScope.launch {
            ScreenAssistant.ui.collect { h ->
                if (!assistantHelpPending) return@collect
                if (h.answer.isNotBlank() && h.answer != lastHelpAnswer) {
                    assistantHelpPending = false
                    lastHelpAnswer = h.answer
                    _ui.value = _ui.value.copy(answer = h.answer, note = "", busy = false)
                    switchToChat()
                } else if (h.error != null && h.error != lastHelpAnswer) {
                    assistantHelpPending = false
                    lastHelpAnswer = h.error.orEmpty()
                    _ui.value = _ui.value.copy(note = h.error.orEmpty(), busy = false)
                }
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, "settings"))
        closeWindows()
    }

    private fun isBengali() = SettingsStore.state.value.uiLanguage == UiLanguage.BN

    /** Local midnight — "today" means the user's day, not UTC. */
    private fun dayStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("ভাসমান সহকারী", "Floating assistant"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("বাবল চালু থাকলে Android-এর বাধ্যতামূলক নোটিফিকেশন", "Android-required notification while the bubble is on")
            setShowBadge(false)
        })
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 3,
            Intent(this, AssistantOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(tr("VoiceDock সহকারী চালু", "VoiceDock assistant is on"))
            .setContentText(tr("বাবলে চাপ দিয়ে শুরু করুন · স্ক্রিন শুধু আপনার ট্যাপে পড়া হয়", "Tap the bubble · screen is read only on your tap"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("বন্ধ", "Close"), close)
            .build()
    }

    override fun onDestroy() {
        probeJob?.cancel()
        removeCard(); removeChat(); removeDictBubble(); removeBubble(); scrim?.detach()
        running = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "assistant"
        const val NOTIF_ID = 43
        const val ACTION_SHOW = "com.voicedock.ai.ASSISTANT_SHOW"
        const val ACTION_HIDE = "com.voicedock.ai.ASSISTANT_HIDE"
        const val ACTION_RESCAN = "com.voicedock.ai.ASSISTANT_RESCAN"
        const val ACTION_CLOSE = "com.voicedock.ai.ASSISTANT_CLOSE"
        /** v1.0.0 — the hotword fired. */
        const val ACTION_WAKE = "com.voicedock.ai.ASSISTANT_WAKE"
        const val EXTRA_WAKE_COMMAND = "wake_command"

        private const val COLOR_IDLE = 0xFF455A64.toInt()
        private const val COLOR_SCANNING = 0xFF3DDCBF.toInt()
        private const val COLOR_CONTEXT = 0xFF2E7D32.toInt()
        private const val COLOR_CHAT = 0xFF6A1B9A.toInt()
        private const val COLOR_DICT_ON = 0xFFE53935.toInt()
        private const val COLOR_DICT_READY = 0xFF2E7D32.toInt()

        @Volatile var running = false; private set

        /** Call from a visible Activity the first time (Android background-start rules). */
        fun start(ctx: Context, action: String = ACTION_SHOW) {
            val i = Intent(ctx, AssistantOverlayService::class.java).setAction(action)
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
            catch (e: Exception) { Log.w("VoiceDock", "assistant service start failed: ${e.javaClass.simpleName}") }
        }
    }
}

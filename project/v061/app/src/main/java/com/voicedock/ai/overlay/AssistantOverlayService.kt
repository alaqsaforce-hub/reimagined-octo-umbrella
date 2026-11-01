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
import com.voicedock.ai.security.SecretStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import com.voicedock.ai.search.SearchClient
import com.voicedock.ai.search.SearchCore
import com.voicedock.ai.search.YouTubeSearch
import com.voicedock.ai.schedule.Reminders
import com.voicedock.ai.schedule.TimeWords
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.MainActivity
import com.voicedock.ai.ai.AiReadinessCore
import com.voicedock.ai.appc.AppLauncher
import com.voicedock.ai.agents.TaskBoard
import com.voicedock.ai.agents.TaskBoardStore
import com.voicedock.ai.agents.TaskRunner
import com.voicedock.ai.agents.TerminalDecision
import com.voicedock.ai.agents.TerminalPolicy
import com.voicedock.ai.command.Choice
import com.voicedock.ai.device.CallerIdentity
import com.voicedock.ai.draft.DraftCore
import com.voicedock.ai.device.ChatInstanceCore
import com.voicedock.ai.device.DeviceAccess
import com.voicedock.ai.device.SimCore
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

    /**
     * v1.2.1 — commands are executed on the main thread, but a search or an AI summary takes seconds.
     * Work that can be slow runs on this scope (IO inside), and the answer is delivered back on Main.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** v1.3.0 — set while a task-board step is executing, so `done()` can report back to the runner. */
    private var runnerTaskId: String? = null
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
            // v1.3.0 — a task step (or the task's final line). Runs through the identical pipeline a spoken
            // command uses, so confirmation, fallbacks and honest failures all still apply.
            ACTION_RUN_TEXT -> {
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    addBubble()
                    _dict.value = _dict.value.copy(visible = true, liveText = text)
                    ensureBubbleVisible()
                    runCommand(text, intent?.getStringExtra(EXTRA_TASK_ID))
                }
            }
            ACTION_SAY -> {
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    addBubble()
                    _dict.value = _dict.value.copy(visible = true, answer = text, note = "", busy = false, busyLabel = "")
                    ensureBubbleVisible()
                }
            }
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
                // v1.3.1 — the draft card. Three explicit taps; none of them is a side effect of speaking.
                onDraftInsert = { insertDraftNow() },
                onDraftKeep = { keepDraft() },
                onDraftDiscard = { discardDraft() },
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

    /**
     * v1.3.1 — "ঢোকাও": the only path that puts a draft into another app, and it is always a tap (or a spoken
     * insert command) — never a side effect of finishing a sentence.
     */
    private fun insertDraftNow() {
        val text = _dict.value.draftText.ifBlank { _dict.value.liveText }.trim()
        if (text.isBlank()) { toast(DraftCore.nothingText(isBengali())); return }
        val micOn = VoiceDock.dictation(this).ui.value.micOn
        removeDictBubble()
        lifecycleScope.launch {
            if (micOn) runCatching { VoiceDock.dictation(this@AssistantOverlayService).finish() }
            delay(280) // our window must be gone so the app underneath owns the input focus
            val out = TextInserter.insert(this@AssistantOverlayService, text)
            toast(tr(out.result.bn, out.result.en))
            runCatching { VoiceDock.dictation(this@AssistantOverlayService).markInserted(out.destination) }
            _dict.value = DictationBubbleState()
            updateBubble()
        }
    }

    /** "রাখো" — the text is already in Draft history; the card just closes with an honest line. */
    private fun keepDraft() {
        val text = _dict.value.draftText
        _dict.value = _dict.value.copy(draftText = "", draftTarget = "", draftNote = "", note = DraftCore.keptText(isBengali()))
        updateBubble()
        if (text.isNotBlank()) runCatching { VoiceDock.dictation(this).markInserted("draft-history") }
    }

    /** ✕ — throw the composed draft away. The draft row itself stays in history (never silently deleted). */
    private fun discardDraft() {
        _dict.value = _dict.value.copy(draftText = "", draftTarget = "", draftNote = "", note = "")
        runCatching { VoiceDock.dictation(this).clear() }
        updateBubble()
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
    private fun runCommand(text: String, taskId: String? = null) {
        runnerTaskId = taskId
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
            // v1.2.0: "কখন?" gets three real examples instead of an empty card.
            spec.intent == CmdIntent.MSG_SCHEDULE && spec.slot(Slot.TIME).isNullOrBlank() ->
                mapOf(Slot.TIME to listOf("সন্ধ্যা ৭টায়", "রাতে", "২০ মিনিট পরে"))
            spec.intent == CmdIntent.REMINDER_ADD && spec.slot(Slot.TIME).isNullOrBlank() ->
                mapOf(Slot.TIME to listOf("৩টায়", "সন্ধ্যা ৭টায়", "২০ মিনিট পরে"))
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
            // v1.3.0 — if a task step produced this, the runner needs the truth to move on.
            runnerTaskId?.let { com.voicedock.ai.agents.TaskRunner.onStepResult(it, ok, text) }
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

            // ---- v1.3.0 device access -------------------------------------------------------------
            CmdIntent.SIM_NUMBERS -> {
                val sims = DeviceAccess.sims(this)
                val text = SimCore.myNumbers(sims, bn)
                val extra = if (sims.isEmpty() && !DeviceAccess.hasPhoneState(this)) {
                    tr("\n\n(‘ফোন স্টেট’ অনুমতি নেই — সেটিংস → সিস্টেম ও এজেন্ট থেকে দিন।)",
                        "\n\n(No ‘phone state’ permission — grant it in Settings → System & agent.)")
                } else ""
                done(text + extra, sims.any { !it.number.isNullOrBlank() }, "sim:numbers")
            }

            CmdIntent.CALLER_WHO -> {
                // Nothing here guesses: only a live ringing notification counts as "someone is calling".
                val ringing = runCatching {
                    com.voicedock.ai.notify.RemoteReply.liveAll()
                        .filter { com.voicedock.ai.notify.CallNotificationAction.looksLikeCall(it.notification) }
                        .maxByOrNull { it.postTime }
                }.getOrNull()
                if (ringing == null) {
                    done(
                        tr("এখন কোনো কল আসছে না। কে ফোন করছে জানতে চাইলে কল আসার সময় জিজ্ঞেস করুন।",
                            "No call is ringing right now. Ask again while a call is coming in."),
                        false, "identity:none",
                    )
                } else {
                    val n = ringing.notification
                    val title = n.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val body = (n.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty())
                    val number = (title + " " + body).filter { it.isDigit() || it == '+' }.trim()
                    val contacts = runCatching { com.voicedock.ai.telecom.ContactResolver(this).all() }.getOrDefault(emptyList())
                    val simToUse = SimCore.pick(DeviceAccess.sims(this), null)
                    val id = com.voicedock.ai.device.CallerIdentity.of(number.ifBlank { title }, contacts, simToUse)
                    done(com.voicedock.ai.device.CallerIdentity.incomingText(id, bn) + tr("\n\nধরতে ‘ধরো’, কাটতে ‘কেটে দাও’ বলুন।", "\n\nSay ‘answer’ to pick up, ‘decline’ to reject."),
                        true, "identity:ringing")
                }
            }

            CmdIntent.CHAT_OPEN -> {
                val hint = spec.slot(Slot.CHAT).orEmpty()
                val all = DeviceAccess.chatInstances(this)
                val picked = ChatInstanceCore.pick(all, hint)
                val whats = ChatInstanceCore.whatsapps(all)
                when {
                    all.isEmpty() -> done(
                        tr("কোনো চ্যাট অ্যাপ (হোয়াটসঅ্যাপ/মেসেঞ্জার) পাওয়া যায়নি।",
                            "No chat app (WhatsApp/Messenger) was found."), false, "chat:none",
                    )
                    picked == null -> done(
                        tr("একাধিক ইনস্ট্যান্স আছে — কোনটা খুলবো?\n", "There is more than one instance — which one?\n") +
                            ChatInstanceCore.choices(all).joinToString("\n") + tr("\n‘বিজনেস’ বা ‘ক্লোন’ বলে বলুন।", "\nSay ‘business’ or ‘clone’."),
                        false, "chat:ambiguous",
                    )
                    DeviceAccess.launchInstance(this, picked) -> done(
                        tr("${picked.label} খোলা হলো ✓", "Opened ${picked.label} ✓"), true, "chat:open",
                    )
                    else -> done(tr("${picked.label} খোলা যায়নি — লঞ্চার থেকে নিজে খুলুন।", "Could not open ${picked.label} — open it from the launcher."), false, "chat:failed")
                }
            }

            CmdIntent.TERMUX_RUN -> {
                val cmd = spec.slot(Slot.TEXT).orEmpty()
                val on = SettingsStore.state.value.termuxEnabled
                // The confirmation card (destructive intent) has already been shown, so pass confirmed = true
                // and keep the hard blocks in place.
                val verdict = TerminalPolicy.decide(cmd, on, confirmed = true)
                if (verdict.decision == TerminalDecision.BLOCK) {
                    done(if (bn) verdict.reasonBn else verdict.reasonEn, false, "termux:blocked")
                    _dict.value = _dict.value.copy(needsSetup = true)
                } else {
                    val present = DeviceAccess.termuxInstalled(this)
                    val reason = DeviceAccess.runTermux(this, cmd)
                    val text = TerminalPolicy.dispatchedText(cmd, present, bn) +
                        if (reason != null) tr("\n(পাঠানো যায়নি: $reason)", "\n(Not dispatched: $reason)") else ""
                    done(text, reason == null && present, "termux:run")
                }
            }

            CmdIntent.TASK_ADD -> {
                val body = spec.slot(Slot.TEXT).orEmpty()
                val steps = TaskBoard.parseSteps(body)
                if (steps.isEmpty()) {
                    done(tr("কী কাজ করবো ঠিক বুঝিনি — ‘কাজ দাও: …’ বলে বলুন।", "I did not catch the task — say ‘add task: …’."), false, "task:empty")
                } else {
                    val task = TaskBoardStore(this).add(steps.first().take(40), steps, System.currentTimeMillis())
                    if (task == null) {
                        done(tr("কাজটি সংরক্ষণ করা যায়নি।", "The task could not be saved."), false, "task:save")
                    } else {
                        val list = steps.mapIndexed { i, st -> "${SimCore.bn(i + 1)}. $st" }.joinToString("\n")
                        done(TaskBoard.startedText(task, bn) + "\n" + list, true, "task:add")
                        TaskRunner.start(this, task.id)
                    }
                }
            }

            CmdIntent.TASK_LIST -> {
                val tasks = TaskBoardStore(this).load()
                val text = if (tasks.isEmpty()) {
                    tr("কাজের তালিকা খালি — ‘কাজ দাও: …’ বলে একটা যোগ করুন।", "The task list is empty — say ‘add task: …’ to add one.")
                } else {
                    tasks.takeLast(6).reversed().joinToString("\n") { "• " + it.label(bn) }
                }
                done(text, tasks.isNotEmpty(), "task:list")
            }

            CmdIntent.TASK_CANCEL -> {
                val cancelled = TaskRunner.cancel(this, null)
                done(
                    if (cancelled == null) tr("বাতিল করার মতো চলমান কাজ নেই।", "No running task to cancel.")
                    else tr("বাতিল হয়েছে: ${cancelled.title}", "Cancelled: ${cancelled.title}"),
                    cancelled != null, "task:cancel",
                )
            }

            // ---- v1.3.2 content summaries -----------------------------------------------------------
            CmdIntent.MSG_THREAD -> {
                val who = spec.slot(Slot.CONTACT).orEmpty()
                if (!NotificationAccess.isGranted(this)) {
                    val msg = tr("নোটিফিকেশন অ্যাক্সেস নেই — সেটিংস → কমান্ডে চালু করুন।",
                        "Notification access is off — enable it in Settings → Commands.")
                    done(msg, false, "blocked")
                    _dict.value = _dict.value.copy(needsSetup = true, note = msg)
                } else {
                    val entries = runCatching { notifStore.all() }.getOrDefault(emptyList())
                    val thread = com.voicedock.ai.notify.ChatRollup.find(entries, dayStart(), who)
                    if (thread == null) {
                        done(com.voicedock.ai.notify.ChatRollup.notFound(who, bn), false, "thread:none")
                    } else {
                        val base = com.voicedock.ai.notify.ChatRollup.text(thread, bn)
                        // The log-based answer is shown immediately; a model may then *add* a summary, but
                        // only from the messages the notifications actually contained.
                        done(base, true, "thread:log")
                        if (thread.messages.size >= 3 && AiSetup.readiness(this).ok) {
                            lifecycleScope.launch {
                                _dict.value = _dict.value.copy(busy = true, busyLabel = tr("সারমর্ম লিখছি…", "Summarising…"))
                                val ai = runCatching {
                                    withContext(Dispatchers.IO) {
                                        AiSetup.router(this@AssistantOverlayService).complete(
                                            com.voicedock.ai.notify.ChatRollup.aiPrompt(thread, bn),
                                            listOf(com.voicedock.ai.ai.ChatMessage.user(
                                                com.voicedock.ai.notify.ChatRollup.aiMessages(thread))),
                                        ).text.trim()
                                    }
                                }.getOrNull()?.takeIf { it.isNotBlank() }
                                _dict.value = _dict.value.copy(
                                    busy = false, busyLabel = "",
                                    answer = if (ai == null) _dict.value.answer
                                    else ai + "\n\n" + tr(
                                        "(উৎস: ${thread.count}টা নোটিফিকেশন — অ্যাপ নিজে যা দেখিয়েছে)",
                                        "(source: ${thread.count} notifications — exactly what the app showed)",
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            CmdIntent.YT_SUMMARY -> {
                val raw = spec.slot(Slot.QUERY).orEmpty().ifBlank { spec.raw }
                val id = com.voicedock.ai.summaries.YtSummaryCore.videoId(raw)
                if (id == null) {
                    done(com.voicedock.ai.summaries.YtSummaryCore.failure("no_link", bn), false, "yt:nolink")
                } else {
                    _dict.value = _dict.value.copy(busy = true, busyLabel = tr("ভিডিওর তথ্য আনছি…", "Fetching the video…"))
                    lifecycleScope.launch {
                        val out = withContext(Dispatchers.IO) { com.voicedock.ai.summaries.YtSummary.fetch(id) }
                        val info = out.info
                        if (info == null) {
                            done(com.voicedock.ai.summaries.YtSummaryCore.failure(out.reason ?: "parse", bn), false, "yt:${out.reason}")
                        } else {
                            val ai = if (AiSetup.readiness(this@AssistantOverlayService).ok) {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        AiSetup.router(this@AssistantOverlayService).complete(
                                            com.voicedock.ai.summaries.YtSummaryCore.aiPrompt(bn),
                                            listOf(com.voicedock.ai.ai.ChatMessage.user(
                                                "TITLE: ${info.title}\nCHANNEL: ${info.channel}\n" +
                                                    "DESCRIPTION: ${info.description.take(1200)}")),
                                        ).text
                                    }
                                }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
                            } else null
                            done(com.voicedock.ai.summaries.YtSummaryCore.text(info, bn, ai), true, "yt:${if (ai == null) "meta" else "ai"}")
                        }
                    }
                }
            }

            CmdIntent.YT_TRANSCRIPT -> {
                val svc = InsertAccessibilityService.instance
                if (svc == null) {
                    done(tr("Accessibility চালু নেই — স্ক্রিনের ট্রান্সক্রিপ্ট পড়া যায় না (সেটিংস → সিস্টেম ও এজেন্ট)।",
                        "Accessibility is off — the on-screen transcript cannot be read (Settings → System & agent)."),
                        false, "transcript:noservice")
                    _dict.value = _dict.value.copy(needsSetup = true)
                } else {
                    val parsed = com.voicedock.ai.summaries.ScreenTranscript.clean(
                        runCatching { svc.screenText(12_000, expectedPackage = "com.google.android.youtube") }.getOrDefault(""),
                    )
                    when {
                        !parsed.confident || parsed.lines.isEmpty() -> done(
                            com.voicedock.ai.summaries.ScreenTranscript.missingPanel(bn), false, "transcript:missing",
                        )
                        else -> {
                            done(com.voicedock.ai.summaries.ScreenTranscript.text(parsed, bn, null), true,
                                "transcript:${parsed.lines.size}")
                            if (AiSetup.readiness(this).ok) {
                                lifecycleScope.launch {
                                    _dict.value = _dict.value.copy(busy = true, busyLabel = tr("সারমর্ম লিখছি…", "Summarising…"))
                                    val summary = runCatching {
                                        withContext(Dispatchers.IO) {
                                            AiSetup.router(this@AssistantOverlayService).complete(
                                                com.voicedock.ai.summaries.ScreenTranscript.aiPrompt(bn),
                                                listOf(com.voicedock.ai.ai.ChatMessage.user(
                                                    parsed.lines.joinToString("\n"))),
                                            ).text.trim()
                                        }
                                    }.getOrNull()?.takeIf { it.isNotBlank() }
                                    _dict.value = _dict.value.copy(
                                        busy = false, busyLabel = "",
                                        answer = com.voicedock.ai.summaries.ScreenTranscript.text(parsed, bn, summary),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- v1.4.0 MCP -------------------------------------------------------------------------
            CmdIntent.MCP_TOOLS, CmdIntent.MCP_RUN -> {
                val servers = SettingsStore.state.value.mcpServersSafe().filter { it.enabled }
                if (servers.isEmpty()) {
                    done(com.voicedock.ai.mcp.McpCore.noServers(bn), false, "mcp:noserver")
                    _dict.value = _dict.value.copy(needsSetup = true, note = tr(
                        "সেটিংস → MCP-এ ঠিকানা যোগ করুন।", "Add an address in Settings → MCP.",
                    ))
                } else {
                    _dict.value = _dict.value.copy(busy = true, busyLabel = tr("MCP সার্ভারে যাচ্ছি…", "Talking to the MCP server…"))
                    lifecycleScope.launch {
                        val wanted = if (spec.intent == CmdIntent.MCP_RUN) spec.slot(Slot.TEXT).orEmpty() else ""
                        val found = mutableListOf<com.voicedock.ai.mcp.McpCore.McpTool>()
                        var firstFailure: String? = null
                        withContext(Dispatchers.IO) {
                            for (sv in servers) {
                                val token = sv.tokenName?.let { SecretStore.get(this@AssistantOverlayService, it) }
                                val c = com.voicedock.ai.mcp.McpClient.connect(sv, token)
                                if (c.ok) found += c.tools
                                else if (firstFailure == null) firstFailure = c.reason
                            }
                        }
                        when {
                            found.isEmpty() -> done(
                                com.voicedock.ai.mcp.McpCore.failure(firstFailure ?: "not_mcp", bn), false,
                                "mcp:${firstFailure ?: "empty"}",
                            )
                            spec.intent == CmdIntent.MCP_TOOLS -> done(
                                com.voicedock.ai.mcp.McpCore.toolsAnswer(found.distinctBy { it.serverId + "/" + it.name }, bn),
                                true, "mcp:tools:${found.size}",
                            )
                            else -> {
                                // The tool has to be one the server just listed — a name is never invented.
                                val lower = wanted.lowercase()
                                val tool = found
                                    .filter { it.name.isNotBlank() && lower.contains(it.name.lowercase()) }
                                    .maxByOrNull { it.name.length }
                                    ?: found.firstOrNull { it.required.isEmpty() && lower.contains(it.name.lowercase()) }
                                if (tool == null) {
                                    // Name the tool the user actually said, so the answer explains itself.
                                    val m = Regex("mcp\\s*(?:দিয়ে|diye)?\\s*([a-zA-Z0-9_.-]{2,40})").find(wanted)
                                    val named = m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                                        ?: wanted.split(' ').firstOrNull().orEmpty()
                                    done(com.voicedock.ai.mcp.McpCore.unknownTool(named, found, bn), false, "mcp:notool")
                                } else {
                                    val server = servers.firstOrNull { it.id == tool.serverId }
                                    val args = com.voicedock.ai.mcp.McpCore.argumentsFor(
                                        tool, wanted.replace(tool.name, " ", ignoreCase = true).trim(),
                                    )
                                    val run = withContext(Dispatchers.IO) {
                                        val token = server?.tokenName?.let { SecretStore.get(this@AssistantOverlayService, it) }
                                        if (server == null) com.voicedock.ai.mcp.McpCore.ToolRun(false, "")
                                        else com.voicedock.ai.mcp.McpClient.call(server, tool.name, args, token)
                                    }
                                    if (!run.ok) {
                                        done(com.voicedock.ai.mcp.McpCore.failure("rpc_call", bn), false, "mcp:callfail")
                                    } else {
                                        done(com.voicedock.ai.mcp.McpCore.runAnswer(run, tool, bn), !run.isError,
                                            "mcp:run:${tool.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- v1.3.4 calendar --------------------------------------------------------------------
            CmdIntent.CAL_LIST -> {
                if (!com.voicedock.ai.calendar.CalendarClient.connected()) {
                    val msg = com.voicedock.ai.calendar.CalendarCore.scopeMissing(bn)
                    done(msg, false, "cal:notoken")
                    _dict.value = _dict.value.copy(needsSetup = true, note = tr(
                        "কানেক্টর স্ক্রিন খুলতে ‘সেটিংস’ চাপুন।", "Tap ‘Settings’ to open the Connectors screen.",
                    ))
                } else {
                    val now = System.currentTimeMillis()
                    val raw = spec.slot(Slot.TIME).orEmpty().ifBlank { spec.raw }
                    val range = com.voicedock.ai.calendar.CalendarCore.range(raw)
                    val (from, to) = com.voicedock.ai.calendar.CalendarCore.window(range, now, word = raw)
                    _dict.value = _dict.value.copy(busy = true, busyLabel = tr("ক্যালেন্ডার দেখছি…", "Reading the calendar…"))
                    lifecycleScope.launch {
                        val out = withContext(Dispatchers.IO) { com.voicedock.ai.calendar.CalendarClient.list(from, to) }
                        if (!out.ok) {
                            done(com.voicedock.ai.calendar.CalendarCore.failure(out.reason ?: "io", bn), false, "cal:${out.reason}")
                        } else {
                            done(
                                com.voicedock.ai.calendar.CalendarCore.list(
                                    out.events,
                                    com.voicedock.ai.calendar.CalendarCore.rangeLabel(range, now, bn, word = raw),
                                    bn,
                                ),
                                true, "cal:list:${out.events.size}",
                            )
                            if (SettingsStore.state.value.speakResults && out.events.isNotEmpty()) {
                                speaker.speak(
                                    com.voicedock.ai.calendar.CalendarCore.list(
                                        out.events.take(3),
                                        com.voicedock.ai.calendar.CalendarCore.rangeLabel(range, now, bn, word = raw),
                                        bn,
                                    ), bn,
                                )
                            }
                        }
                    }
                }
            }

            CmdIntent.CAL_ADD -> {
                val title = spec.slot(Slot.TEXT).orEmpty().trim()
                val raw = spec.slot(Slot.TIME).orEmpty().ifBlank { spec.raw }
                val guess = com.voicedock.ai.schedule.TimeWords.parse(raw, System.currentTimeMillis())
                if (title.isBlank()) {
                    done(com.voicedock.ai.calendar.CalendarCore.noTitle(bn), false, "cal:notitle")
                } else if (guess == null) {
                    done(com.voicedock.ai.calendar.CalendarCore.noTime(bn), false, "cal:notime")
                } else if (!com.voicedock.ai.calendar.CalendarClient.connected()) {
                    done(com.voicedock.ai.calendar.CalendarCore.scopeMissing(bn), false, "cal:notoken")
                    _dict.value = _dict.value.copy(needsSetup = true)
                } else {
                    // Duration: "২ ঘণ্টা" / "2 hours" when spoken, otherwise one hour.
                    val ascii = com.voicedock.ai.device.SimCore.asciiDigits(raw)
                    val hours = Regex("(\\d)\\s*(ঘণ্টা|hour)").find(ascii)?.groupValues?.get(1)?.toIntOrNull()
                    val mins = Regex("(\\d{1,3})\\s*(মিনিট|min)").find(ascii)?.groupValues?.get(1)?.toIntOrNull()
                    val length = when {
                        hours != null -> hours * 60L * 60_000L
                        mins != null -> mins * 60_000L
                        else -> 60L * 60_000L
                    }
                    val start = guess.atMillis
                    val end = start + length
                    _dict.value = _dict.value.copy(busy = true, busyLabel = tr("ক্যালেন্ডারে যোগ করছি…", "Adding to the calendar…"))
                    lifecycleScope.launch {
                        val out = withContext(Dispatchers.IO) {
                            com.voicedock.ai.calendar.CalendarClient.create(title, start, end)
                        }
                        done(
                            if (out.ok) com.voicedock.ai.calendar.CalendarCore.added(title, bn)
                            else com.voicedock.ai.calendar.CalendarCore.failure(out.reason ?: "io", bn),
                            out.ok, "cal:add:${if (out.ok) "ok" else out.reason}",
                        )
                        // Undo is honest here: the event really can be removed… except the app never deletes, by
                        // design. So the answer names the time instead of offering a false undo chip.
                        if (out.ok) {
                            _dict.value = _dict.value.copy(
                                note = com.voicedock.ai.calendar.CalendarCore.draftText(title, start, end, bn),
                            )
                        }
                    }
                }
            }

            CmdIntent.DRAFT_INSERT -> {
                if (_dict.value.draftText.isBlank() && _dict.value.liveText.isBlank()) {
                    done(DraftCore.nothingText(bn), false, "draft:none")
                } else {
                    done(tr("খসড়া বসানো হচ্ছে…", "Inserting the draft…"), true, "draft:insert")
                    insertDraftNow()
                }
            }

            CmdIntent.DRAFT_DISCARD -> {
                val had = _dict.value.draftText.isNotBlank()
                discardDraft()
                done(
                    if (had) tr("খসড়া বাতিল করা হলো", "Draft discarded") else DraftCore.nothingText(bn),
                    had, "draft:discard",
                )
            }

            CmdIntent.VOICE_OPEN -> {
                val i = Intent(this, com.voicedock.ai.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(com.voicedock.ai.ui.MainActivity.EXTRA_SCREEN, "voice")
                val ok = runCatching { startActivity(i) }.isSuccess
                done(
                    if (ok) tr("ভয়েস মোড খোলা হলো — বলুন, লেখা নিচে আসবে।", "Voice mode is open — speak and the words appear below.")
                    else tr("ভয়েস স্ক্রিন খোলা যায়নি।", "The voice screen could not be opened."),
                    ok, "voice:open",
                )
            }

            CmdIntent.CALL_PLACE -> {
                val who = spec.slot(Slot.CONTACT).orEmpty()
                // v1.3.0 — resolve who this is before dialling, and honour "সিম ২ দিয়ে".
                val contacts = runCatching { com.voicedock.ai.telecom.ContactResolver(this).all() }.getOrDefault(emptyList())
                val hit = runCatching {
                    if (who.filter { it.isDigit() }.length >= 6) null
                    else com.voicedock.ai.telecom.ContactResolver(this).resolve(who).firstOrNull()
                }.getOrNull()
                val target = hit?.number ?: who
                val wantedSlot = spec.slot(Slot.SIM)?.toIntOrNull()
                val sims = DeviceAccess.sims(this)
                val sim = if (wantedSlot == null) null else SimCore.pick(sims, wantedSlot)
                if (wantedSlot != null && sim == null) {
                    done(SimCore.explainMissing(sims, wantedSlot, bn), false, "sim:missing")
                    _dict.value = _dict.value.copy(needsSetup = true)
                } else if (sim != null) {
                    val id = com.voicedock.ai.device.CallerIdentity.of(target, contacts, sim)
                    val reason = DeviceAccess.placeCallOnSim(this, target, sim)
                    if (reason == null) {
                        done(tr("${com.voicedock.ai.device.CallerIdentity.describe(id, bn)} — ${sim.title} দিয়ে কল করা হচ্ছে ✓",
                            "Calling ${com.voicedock.ai.device.CallerIdentity.describe(id, bn)} on ${sim.title} ✓"), true, "place:sim${sim.displaySlot}")
                        // v1.3.1 — floating mode: a placed call does not close the bubble, and the user is told
                        // that the assistant is still there over the call screen.
                        _dict.value = _dict.value.copy(note = tr(
                            "বাবল খোলা থাকছে — কল চলাকালীনও ‘কেটে দাও’ বা অন্য কমান্ড দিতে পারবেন।",
                            "The bubble stays open — you can still say ‘decline’ or any command during the call.",
                        ))
                    } else {
                        // The SIM could not be aimed at: place the call normally and say so honestly.
                        val out = calls.place(target)
                        done(
                            tr("${com.voicedock.ai.device.CallerIdentity.describe(id, bn)} — ঠিক কোন সিম দিয়ে যাবে নিশ্চিত করা যায়নি (${reason}); সিস্টেমের ডিফল্ট সিমে কল হচ্ছে।",
                                "${com.voicedock.ai.device.CallerIdentity.describe(id, bn)} — could not pin the SIM (${reason}); calling on the system default SIM."),
                            out.ok, "place:simFallback",
                        )
                    }
                } else {
                // No SIM was named: exactly the v0.9.0 behaviour (system default SIM), unchanged.
                val number = spec.slot(Slot.CONTACT).orEmpty()
                val out = calls.place(number)
                done(
                    when {
                        out.ok && out.strategy == CallStrategy.TELECOM -> tr("$number নম্বরে কল করা হচ্ছে ✓ (বাবল খোলা থাকছে)",
                            "Calling $number ✓ (the bubble stays open)")
                        out.ok && out.strategy == CallStrategy.DIALER_OPENED ->
                            tr("কল করার অনুমতি নেই — ডায়ালারে $number বসানো হয়েছে, আপনি চাপ দিন।", "No call permission — the dialer is open with $number filled in.")
                        else -> tr("কল করা যায়নি (অনুমতি বা সিস্টেম বাধা)।", "Could not place the call (permission or system block).")
                    },
                    out.ok, "place:${out.strategy.name.lowercase()}",
                )
                if (!out.ok) _dict.value = _dict.value.copy(needsSetup = true)
                }
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
                } else if (reason == "no_notification" || reason == "no_action") {
                    // v1.3.3 — accessibility pass: with the chat already open, type it and press the app's own
                    // Send. Conservative: the app in front must prove it is a chat with a send button.
                    val pkg = live?.packageName
                    val attempt = com.voicedock.ai.send.AccessibilitySender.send(this, body, pkg, sender)
                    if (attempt == com.voicedock.ai.send.SendResult.SENT) {
                        done(com.voicedock.ai.send.SendCore.resultText(attempt, bn, sender), true, "accessibilitySend")
                    } else {
                        val msg = tr(
                            "সরাসরি উত্তর দেওয়ার সুযোগ নেই, আর Accessibility দিয়েও পাঠানো যায়নি — ${attempt.bn}.\n" +
                                "লেখাটা কপি করা হলো, চ্যাটে গিয়ে পেস্ট করে পাঠান।",
                            "No direct reply, and the accessibility route did not work — ${attempt.en}.\n" +
                                "The text was copied; paste it in the chat and send.",
                        )
                        com.voicedock.ai.insertion.ClipboardInsertion.insert(this, body)
                        done(msg, false, "accessibilitySend:${attempt.name.lowercase()}")
                    }
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

            CmdIntent.REMINDER_ADD -> {
                val goal = spec.slot(Slot.TEXT).orEmpty()
                val timeText = spec.slot(Slot.TIME) ?: spec.raw
                val now = System.currentTimeMillis()
                val guess = TimeWords.parse(timeText, now)
                if (guess == null) {
                    done(
                        tr(
                            "কখন মনে করিয়ে দেব ঠিক বুঝিনি — ‘৩টায়’ বা ‘২০ মিনিট পরে’ বলুন।",
                            "I did not catch the time — say ‘at 3’ or ‘in 20 minutes’.",
                        ),
                        false, "reminder:no_time",
                    )
                } else if (goal.isBlank()) {
                    done(tr("কী মনে করিয়ে দেব?", "Remind you about what?"), false, "reminder:no_goal")
                } else {
                    // The command path is not a coroutine; a single local SQLite insert is milliseconds, so it
                    // runs on the IO dispatcher and blocks briefly rather than leaving the answer unanswered.
                    val row = runCatching {
                        runBlocking(kotlinx.coroutines.Dispatchers.IO) { Reminders.add(this@AssistantOverlayService, goal, guess.atMillis, source) }
                    }.getOrNull()
                    if (row == null) {
                        done(tr("রিমাইন্ডার সেভ করা যায়নি।", "Could not save the reminder."), false, "reminder:save")
                    } else {
                        val exact = Reminders.exactAllowed(this)
                        val msg = Reminders.confirmText(row, bn, now, exact)
                        done(msg, true, "reminder:add")
                        if (SettingsStore.state.value.speakResults) speaker.speak(msg, bn)
                        if (!Permissions.notifications(this)) _dict.value = _dict.value.copy(needsSetup = true)
                    }
                }
            }

            CmdIntent.MSG_SCHEDULE -> {
                val target = spec.slot(Slot.CONTACT).orEmpty()
                val body = spec.slot(Slot.TEXT).orEmpty()
                val timeText = spec.slot(Slot.TIME) ?: spec.raw
                val now = System.currentTimeMillis()
                val guess = TimeWords.parse(timeText, now)
                when {
                    target.isBlank() || body.isBlank() ->
                        done(tr("কাকে কী পাঠাব, ঠিক বুঝিনি — আবার বলুন।", "I did not catch who and what — say it again."), false, "msgsched:slots")
                    guess == null ->
                        done(tr("কখন পাঠাব ঠিক বুঝিনি — ‘সন্ধ্যা ৭টায়’ বা ‘২০ মিনিট পরে’ বলুন।", "I did not catch the time — say ‘at 7 pm’ or ‘in 20 minutes’."), false, "msgsched:no_time")
                    else -> {
                        val plan = Reminders.planFor(this, target)
                        val row = runCatching {
                            runBlocking(Dispatchers.IO) { Reminders.addMessage(this@AssistantOverlayService, target, body, guess.atMillis, source) }
                        }.getOrNull()
                        if (row == null) {
                            done(tr("মেসেজের সময়সূচি সেভ করা যায়নি।", "Could not save the scheduled message."), false, "msgsched:save")
                        } else {
                            val whenText = TimeWords.label(row.triggerAt, now, bn)
                            val msg = com.voicedock.ai.jobs.MessageJobCore.scheduledText(target, body, whenText, plan, bn)
                            done(msg, true, "msgsched:add")
                            if (SettingsStore.state.value.speakResults) speaker.speak(msg, bn)
                        }
                    }
                }
            }

            CmdIntent.WEB_SEARCH -> {
                val q = spec.slot(Slot.QUERY).orEmpty().ifBlank { spec.raw }
                if (!SearchClient.configured(this)) {
                    // No provider: the browser is a real answer, and the text says what is missing.
                    SearchClient.openBrowser(this, q)
                    done(SearchCore.browserHint(q, bn), true, "search:browser")
                } else {
                    done(tr("‘$q’ খুঁজছি…", "Searching for ‘$q’…"), true, "search:start")
                    workScope.launch {
                        val out = withContext(Dispatchers.IO) { SearchClient.run(this@AssistantOverlayService, q) }
                        if (!out.ok) {
                            done(SearchClient.explain(out.reason, bn), false, "search:${out.reason}")
                        } else {
                            val summary = runCatching {
                                AiSetup.router(this@AssistantOverlayService)
                                    .complete(SearchCore.prompt(q, out.hits, bn), listOf(com.voicedock.ai.ai.ChatMessage.user(q)))
                                    .text.trim()
                            }.getOrNull()
                            val text = (summary?.takeIf { it.isNotBlank() } ?: SearchCore.digest(out.hits)) + SearchCore.sourcesLine(out.hits, bn)
                            done(text, true, if (summary != null) "search:ai" else "search:raw")
                            if (SettingsStore.state.value.speakResults) speaker.speak(summary ?: SearchCore.emptyText(bn), bn)
                        }
                    }
                }
            }

            CmdIntent.YT_SEARCH -> {
                val q = spec.slot(Slot.QUERY).orEmpty().ifBlank { spec.raw }
                val inApp = YouTubeSearch.open(this, q)
                done(YouTubeSearch.answer(q, inApp, bn), true, "yt:search")
            }

            CmdIntent.REMINDER_LIST -> {
                val rows = runCatching {
                    runBlocking(kotlinx.coroutines.Dispatchers.IO) { Reminders.pending(this@AssistantOverlayService) }
                }.getOrDefault(emptyList())
                val text = Reminders.listText(rows, bn)
                done(text, true, "reminder:list")
                if (SettingsStore.state.value.speakResults) speaker.speak(text, bn)
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
                    // v1.3.1 — a finished dictation becomes a visible draft card (never an insertion).
                    if (d.phase == DictationPhase.REVIEW) {
                        val composed = DraftCore.tidy(d.draft.ifBlank { d.live })
                        if (DraftCore.presentable(composed) && composed != _dict.value.draftText) {
                            val plan = DraftCore.plan(composed, InsertAccessibilityService.instance != null)
                            _dict.value = _dict.value.copy(
                                draftText = plan.text,
                                draftTarget = plan.targetLabel,
                                draftNote = if (isBengali()) plan.noteBn else plan.noteEn,
                            )
                        }
                    }
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

        /** v1.3.0 — the task runner hands one step of a task to the normal command pipeline. */
        const val ACTION_RUN_TEXT = "com.voicedock.ai.ASSISTANT_RUN_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TASK_ID = "task_id"
        /** v1.3.0 — show a line in the bubble without running anything (task finished / cancelled). */
        const val ACTION_SAY = "com.voicedock.ai.ASSISTANT_SAY"

        /** Run a sentence as if it had been spoken (used by the task runner). */
        fun runCommand(ctx: Context, text: String, taskId: String? = null) {
            val i = Intent(ctx, AssistantOverlayService::class.java)
                .setAction(ACTION_RUN_TEXT)
                .putExtra(EXTRA_TEXT, text)
            if (taskId != null) i.putExtra(EXTRA_TASK_ID, taskId)
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
            catch (e: Exception) { Log.w("VoiceDock", "run text dispatch failed: ${e.javaClass.simpleName}") }
        }

        /** Show a short status line in the bubble (no speech, nothing executed). */
        fun say(ctx: Context, text: String) {
            val i = Intent(ctx, AssistantOverlayService::class.java).setAction(ACTION_SAY).putExtra(EXTRA_TEXT, text)
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
            catch (e: Exception) { Log.w("VoiceDock", "say dispatch failed: ${e.javaClass.simpleName}") }
        }

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

package com.voicedock.ai.agents

import android.content.Context
import android.util.Log
import com.voicedock.ai.overlay.AssistantOverlayService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.3.0 — the background worker for the task board.
 *
 * A task is a list of ordinary sentences. Each one is handed to the *same* command pipeline a spoken command
 * goes through ([AssistantOverlayService] → offline grammar → model → confirmation → execution), and the
 * pipeline's own result comes back here through [onStepResult]. Nothing is re-implemented, so a task can do
 * exactly what a voice command can do — but the runner owns ordering, retry-once, persistence and reporting.
 *
 * Honest limits (stated in the app too):
 *  • it runs inside the app process while the assistant's foreground bubble is alive — if Android kills the
 *    process, the task stays QUEUED/RUNNING on disk and continues the next time the runner starts;
 *  • a step that needs the user's confirmation waits (up to [STEP_TIMEOUT_MS]); when the wait runs out the task
 *    is PAUSED with a Bengali note instead of being marked done.
 */
object TaskRunner {

    private const val TAG = "VoiceDock"
    const val STEP_TIMEOUT_MS = 90_000L
    private const val STEP_GAP_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = AtomicReference<CompletableDeferred<Pair<Boolean, String>>?>(null)
    private val activeTask = AtomicReference<String?>(null)

    @Volatile private var looping = false

    val busy: Boolean get() = looping

    /** Called from the bubble's `done(...)` once a step's command has really finished. */
    fun onStepResult(taskId: String, ok: Boolean, text: String) {
        if (activeTask.get() != taskId) return
        gate.get()?.complete(ok to text)
    }

    /** Fire-and-forget: pick up the oldest QUEUED task and work through it. */
    fun start(ctx: Context, onlyTaskId: String? = null) {
        if (looping) return
        looping = true
        val app = ctx.applicationContext
        val store = TaskBoardStore(app)
        // A finished run may have left a task marked RUNNING; put it back in the queue.
        store.update { tasks -> tasks.map { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.QUEUED) else it } }
        if (store.load().none { it.status == TaskStatus.QUEUED }) { looping = false; return }
        // Keep the process (and the bubble) alive while we work — this is what "background" means here.
        runCatching { AssistantOverlayService.start(app) }
        scope.launch {
            try {
                while (true) {
                    val task = (onlyTaskId?.let { id -> store.load().firstOrNull { it.id == id && it.status == TaskStatus.QUEUED } }
                        ?: TaskBoard.nextTask(store.load())) ?: break
                    runTask(app, store, task)
                    delay(STEP_GAP_MS)
                    if (onlyTaskId != null) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "task runner stopped: ${t.javaClass.simpleName}")
            } finally {
                looping = false
                activeTask.set(null)
            }
        }
    }

    private suspend fun runTask(app: Context, store: TaskBoardStore, start: AgentTask) {
        var task = start.copy(status = TaskStatus.RUNNING)
        store.replace(task)
        activeTask.set(task.id)
        while (true) {
            val step = TaskBoard.nextStep(task) ?: break
            val deferred = CompletableDeferred<Pair<Boolean, String>>()
            gate.set(deferred)
            try {
                AssistantOverlayService.runCommand(app, step.command, task.id)
            } catch (t: Throwable) {
                gate.set(null)
                task = TaskBoard.recordStep(task, step.command, false, "ডিসপ্যাচ ব্যর্থ")
                store.replace(task)
                continue
            }
            val result = withTimeoutOrNull(STEP_TIMEOUT_MS) { deferred.await() }
            gate.set(null)
            task = if (result == null) {
                // No answer from the pipeline: the user probably has to confirm something. Stop honestly.
                val paused = task.copy(
                    status = TaskStatus.PAUSED,
                    lastError = "ধাপ ${step.command.take(40)} — উত্তরের অপেক্ষায় (সম্ভবত অনুমতি দরকার)",
                )
                store.replace(paused)
                activeTask.set(null)
                return
            } else {
                TaskBoard.recordStep(task, step.command, result.first, result.second)
            }
            store.replace(task)
            if (task.status == TaskStatus.FAILED) break
            delay(STEP_GAP_MS)
        }
            activeTask.set(null)
        val line = when (task.status) {
            TaskStatus.DONE -> "✓ " + task.label(true)
            TaskStatus.FAILED -> "✗ " + task.label(true)
            TaskStatus.PAUSED -> "⏸ " + task.label(true)
            else -> task.label(true)
        }
        runCatching { AssistantOverlayService.say(app, line) }
    }

    /** Called by the bubble: "কাজ বাতিল" for the running task. */
    fun cancel(app: Context, taskId: String?): AgentTask? {
        val store = TaskBoardStore(app)
        val target = taskId?.let { store.byId(it) } ?: store.load().firstOrNull { it.status == TaskStatus.RUNNING }
        val cancelled = target?.let { TaskBoard.cancel(it) } ?: return null
        store.replace(cancelled)
        gate.get()?.complete(false to "বাতিল")
        return cancelled
    }
}

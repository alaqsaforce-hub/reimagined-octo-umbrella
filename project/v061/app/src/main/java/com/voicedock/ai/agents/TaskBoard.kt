package com.voicedock.ai.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * v1.3.0 — "কাজ দাও: …" — a task is a short list of commands the assistant runs **one after another**, in the
 * background, after the user's one instruction.
 *
 * Why steps are sentences, not intent ids: the whole command pipeline (offline grammar → model fallback →
 * confirmation → execution) already exists and is tested. A task that re-used it can do everything a spoken
 * command can, and a new intent automatically works in a task. The runner therefore only owns *ordering,
 * persistence, retry and reporting* — all of which are pure and unit-tested here.
 */
enum class TaskStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED, PAUSED }

data class TaskStep(
    val command: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val note: String = "",
)

data class AgentTask(
    val id: String,
    val title: String,
    val steps: List<TaskStep>,
    val createdAt: Long,
    val status: TaskStatus = TaskStatus.QUEUED,
    /** How many times the runner has tried the whole task (each step is retried once before failing). */
    val attempts: Int = 0,
    val lastError: String = "",
    val source: String = "voice",
) {
    val done: Int get() = steps.count { it.status == TaskStatus.DONE }
    val total: Int get() = steps.size
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total

    fun label(bengali: Boolean): String = if (bengali) {
        "$title — ${bn(done)}/${bn(total)} ধাপ" + when (status) {
            TaskStatus.QUEUED -> " (অপেক্ষায়)"
            TaskStatus.RUNNING -> " (চলছে)"
            TaskStatus.DONE -> " ✓"
            TaskStatus.FAILED -> " ✗ ${lastError.take(40)}"
            TaskStatus.CANCELLED -> " (বাতিল)"
            TaskStatus.PAUSED -> " (থামানো)"
        }
    } else {
        "$title — $done/$total steps" + when (status) {
            TaskStatus.QUEUED -> " (queued)"
            TaskStatus.RUNNING -> " (running)"
            TaskStatus.DONE -> " ✓"
            TaskStatus.FAILED -> " ✗ ${lastError.take(40)}"
            TaskStatus.CANCELLED -> " (cancelled)"
            TaskStatus.PAUSED -> " (paused)"
        }
    }

    private fun bn(v: Int): String = buildString {
        for (c in v.toString()) if (c in '0'..'9') append("০১২৩৪৫৬৭৮৯"[c - '0']) else append(c)
    }
}

object TaskBoard {

    const val MAX_STEPS = 8
    const val MAX_TASKS = 20

    /** "X; তারপর Y" / "X then Y" / "X। Y" → steps. Empty pieces are dropped, not stored. */
    fun parseSteps(text: String): List<String> {
        val parts = text
            .replace("তারপর", ";").replace("এরপর", ";").replace("পরে ", ";")
            .replace(" then ", ";").replace(" and then ", ";")
            .split(';', '।', '\n')
            .map { it.trim().trim(',', '.', ' ') }
            .filter { it.length > 1 }
        return parts.take(MAX_STEPS)
    }

    fun add(
        tasks: List<AgentTask>,
        title: String,
        steps: List<String>,
        now: Long,
        id: String,
        source: String = "voice",
    ): Pair<List<AgentTask>, AgentTask?> {
        if (steps.isEmpty()) return tasks to null
        val task = AgentTask(
            id = id,
            title = title.trim().ifBlank { steps.first().take(40) },
            steps = steps.take(MAX_STEPS).map { TaskStep(it) },
            createdAt = now,
            source = source,
        )
        val kept = (tasks + task).takeLast(MAX_TASKS)
        return kept to task
    }

    /** The next step that still needs to run, or null when the task is finished. */
    fun nextStep(task: AgentTask): TaskStep? = task.steps.firstOrNull { it.status == TaskStatus.QUEUED }

    /** The task the runner should pick up: first queued, oldest first. */
    fun nextTask(tasks: List<AgentTask>): AgentTask? =
        tasks.filter { it.status == TaskStatus.QUEUED }.minByOrNull { it.createdAt }

    /**
     * Record the outcome of one step.
     * @param ok false → the step is retried once; a second failure fails the task (never a silent skip).
     */
    fun recordStep(task: AgentTask, command: String, ok: Boolean, note: String = ""): AgentTask {
        val attemptOfStep = task.steps.count { it.command == command && it.note.startsWith("retry") }
        val steps = task.steps.map { s ->
            if (s.command != command) s
            else if (ok) s.copy(status = TaskStatus.DONE, note = note)
            else if (attemptOfStep == 0) s.copy(status = TaskStatus.QUEUED, note = "retry:${note.take(60)}")
            else s.copy(status = TaskStatus.FAILED, note = note.take(120))
        }
        val failed = steps.any { it.status == TaskStatus.FAILED }
        val allDone = steps.all { it.status == TaskStatus.DONE }
        return task.copy(
            steps = steps,
            status = when {
                failed -> TaskStatus.FAILED
                allDone -> TaskStatus.DONE
                else -> TaskStatus.RUNNING
            },
            attempts = task.attempts + 1,
            lastError = if (failed) note.take(120) else task.lastError,
        )
    }

    fun cancel(task: AgentTask): AgentTask = task.copy(
        status = TaskStatus.CANCELLED,
        steps = task.steps.map { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.CANCELLED) else it },
    )

    fun running(tasks: List<AgentTask>): AgentTask? = tasks.firstOrNull { it.status == TaskStatus.RUNNING }

    /** One line for the bubble when a task is dispatched. */
    fun startedText(task: AgentTask, bengali: Boolean): String = if (bengali) {
        "কাজ নিলাম: ${task.title} — ${task.total} ধাপ, একের পর এক চালাব। ফলাফল বাবলেতে দেখবেন।"
    } else {
        "Task accepted: ${task.title} — $task.total steps, run one after another. Results appear in the bubble."
    }

    // ---- persistence (the board survives a restart) -------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(tasks: List<AgentTask>): String = buildJsonArray {
        tasks.forEach { t ->
            add(buildJsonObject {
                put("id", t.id); put("title", t.title); put("createdAt", t.createdAt)
                put("status", t.status.name); put("attempts", t.attempts)
                put("lastError", t.lastError); put("source", t.source)
                put("steps", buildJsonArray {
                    t.steps.forEach { s ->
                        add(buildJsonObject {
                            put("command", s.command); put("status", s.status.name); put("note", s.note)
                        })
                    }
                })
            })
        }
    }.toString()

    fun decode(raw: String): List<AgentTask> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
            val o: JsonObject = e.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val steps = (o["steps"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { se ->
                val so = se.jsonObject
                val command = so["command"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TaskStep(
                    command = command,
                    status = runCatching { TaskStatus.valueOf(so["status"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrDefault(TaskStatus.QUEUED),
                    note = so["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            AgentTask(
                id = id,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                steps = steps,
                createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                status = runCatching { TaskStatus.valueOf(o["status"]?.jsonPrimitive?.contentOrNull.orEmpty()) }.getOrDefault(TaskStatus.QUEUED),
                attempts = o["attempts"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                lastError = o["lastError"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = o["source"]?.jsonPrimitive?.contentOrNull ?: "voice",
            )
        }
    }.getOrDefault(emptyList())
}

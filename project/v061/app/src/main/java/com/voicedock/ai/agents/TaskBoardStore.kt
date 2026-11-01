package com.voicedock.ai.agents

import android.content.Context
import java.io.File

/**
 * v1.3.0 — the board lives in one small JSON file, so it survives a restart without a Room version bump
 * (the reminders table is already used for scheduled messages; tasks are a different, shorter-lived thing).
 */
class TaskBoardStore(private val ctx: Context) {

    private val file = File(ctx.filesDir, "tasks.json")
    private val lock = Any()

    fun load(): List<AgentTask> = synchronized(lock) {
        runCatching { if (file.exists()) TaskBoard.decode(file.readText()) else emptyList() }
            .getOrDefault(emptyList())
    }

    fun save(tasks: List<AgentTask>) = synchronized(lock) {
        runCatching { file.writeText(TaskBoard.encode(tasks)) }
        Unit
    }

    fun update(fn: (List<AgentTask>) -> List<AgentTask>): List<AgentTask> = synchronized(lock) {
        val next = fn(load())
        runCatching { file.writeText(TaskBoard.encode(next)) }
        next
    }

    fun byId(id: String): AgentTask? = load().firstOrNull { it.id == id }

    fun add(title: String, steps: List<String>, now: Long, id: String = "t" + now): AgentTask? {
        var made: AgentTask? = null
        update { tasks ->
            val (list, task) = TaskBoard.add(tasks, title, steps, now, id)
            made = task
            list
        }
        return made
    }

    fun replace(task: AgentTask) = update { tasks -> tasks.map { if (it.id == task.id) task else it } }
}

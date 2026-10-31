package com.voicedock.ai.data.database

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.voicedock.ai.memory.MemoryStore
import com.voicedock.ai.notify.NotifStore
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * v0.10.0 — one-way import of the v0.7–v0.9 JSON stores into Room.
 *
 * Runs exactly once per install (flag in its own preferences file), in the background thread that opens
 * the database. Order is: read JSON → upsert rows → rename the file to `*.imported`. If anything throws,
 * the flag is NOT set, the JSON file is NOT touched, and the old files keep working — so a failed import
 * can never lose a memory.
 */
object LegacyImport {

    private const val TAG = "VoiceDockImport"
    private const val PREFS = "voicedock.legacy_import"
    private const val FLAG = "assistant_json_v1_done"

    fun runOnce(ctx: Context, db: AppDatabase, dir: File = ctx.filesDir) {
        val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FLAG, false)) return

        val memoryFile = File(dir, MemoryStore.FILE_NAME)
        val notifFile = File(dir, NotifStore.FILE_NAME)
        if (!memoryFile.exists() && !notifFile.exists()) {
            prefs.edit().putBoolean(FLAG, true).apply()
            return
        }

        val imported = runCatching {
            runBlocking {
                val dao = db.assistant()
                if (memoryFile.exists()) {
                    val entries = MemoryStore(dir).all()          // JSON path: no dao passed
                    entries.forEach { e ->
                        dao.upsertMemory(MemoryRow(e.id, e.text, e.createdAt, e.source))
                    }
                    dao.trimMemory(MemoryStore.MAX_ENTRIES)
                }
                if (notifFile.exists()) {
                    val entries = NotifStore(dir).all()
                    entries.forEach { n ->
                        dao.upsertNotif(
                            NotifRow(
                                key = n.key, pkg = n.pkg, appLabel = n.appLabel, sender = n.sender,
                                text = n.text, at = n.at, canReply = n.canReply, summary = n.summary,
                            ),
                        )
                    }
                    dao.trimNotifs(NotifStore.MAX)
                }
                true
            }
        }.getOrElse { e ->
            Log.w(TAG, "legacy import failed: ${e.javaClass.simpleName}")
            false
        }
        if (!imported) return

        // Keep the old files (renamed) instead of deleting them: the user can always be shown where the
        // data came from, and a re-import is possible by renaming them back.
        runCatching { if (memoryFile.exists()) memoryFile.renameTo(File(dir, "${MemoryStore.FILE_NAME}.imported")) }
        runCatching { if (notifFile.exists()) notifFile.renameTo(File(dir, "${NotifStore.FILE_NAME}.imported")) }
        prefs.edit().putBoolean(FLAG, true).apply()
        Log.i(TAG, "legacy JSON imported into Room")
    }
}

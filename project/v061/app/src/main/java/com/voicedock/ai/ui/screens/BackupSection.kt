package com.voicedock.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.backup.BackupCodec
import com.voicedock.ai.backup.BackupManager
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.6.5 — backup and restore of the user's own text (drafts + knowledge base) as a readable JSON
 * file through the system file picker. No cloud, no account, no background sync.
 *
 * The file never contains API keys, app passwords, tokens or OAuth sessions: [BackupCodec] has no
 * field for them. Import merges and never deletes.
 */
@Composable
fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) } // drafts, documents

    LaunchedEffect(Unit) {
        counts = withContext(Dispatchers.IO) {
            val db = VoiceDock.db(ctx)
            db.drafts().count() to db.knowledge().count()
        }
    }

    // collectAsState (not .value) so the card recomposes when the UI language changes.
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    val suggestedName = remember {
        "voicedock-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
    }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching {
                val json = BackupManager.exportJson(ctx)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("could not open the target file")
                }
                json.length
            }
            busy = false
            message = result.fold(
                onSuccess = { chars ->
                    if (bn) "ব্যাকআপ সংরক্ষিত হয়েছে (${chars / 1024} KB)। এটি সরাসরি পড়া যায় এমন JSON।"
                    else "Backup saved (${chars / 1024} KB). It is human-readable JSON."
                },
                onFailure = { e ->
                    if (bn) "ব্যাকআপ ব্যর্থ: ${e.message ?: "অজানা ত্রুটি"}" else "Backup failed: ${e.message ?: "unknown error"}"
                },
            )
        }
    }

    val pickFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        // Bound the read so a hostile file cannot exhaust memory.
                        val buf = CharArray(BackupCodec.MAX_JSON_CHARS + 1)
                        var n = 0
                        input.reader(Charsets.UTF_8).use { r ->
                            while (n < buf.size) {
                                val k = r.read(buf, n, buf.size - n)
                                if (k <= 0) break
                                n += k
                            }
                        }
                        String(buf, 0, n)
                    } ?: error("could not open the file")
                }
                BackupManager.importJson(ctx, text)
            }
            busy = false
            message = result.fold(
                onSuccess = { s ->
                    val head = s.describe(bn)
                    if (s.notes.isEmpty()) head else head + "\n" + s.notes.joinToString("\n")
                },
                onFailure = { e ->
                    if (bn) "রিস্টোর ব্যর্থ: ${e.message ?: "অজানা ত্রুটি"}" else "Restore failed: ${e.message ?: "unknown error"}"
                },
            )
            counts = withContext(Dispatchers.IO) {
                val db = VoiceDock.db(ctx)
                db.drafts().count() to db.knowledge().count()
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("ব্যাকআপ ও রিস্টোর", "Backup & restore"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("আপনার খসড়া ও নলেজ ডকুমেন্ট একটি পড়া-যায় এমন JSON ফাইলে রাখুন — বা অন্য ফোন থেকে ফিরিয়ে আনুন। API key, পাসওয়ার্ড বা টোকেন কখনো এই ফাইলে যায় না।",
                    "Keep your drafts and knowledge documents in a readable JSON file — or bring them back from another phone. API keys, passwords and tokens are never written to this file."),
                style = MaterialTheme.typography.bodySmall,
            )
            val c = counts
            Text(
                if (c == null) tr("গণনা করা হচ্ছে…", "Counting…")
                else tr("এই ফোনে: ${c.first} খসড়া · ${c.second} নলেজ ডকুমেন্ট", "On this phone: ${c.first} drafts · ${c.second} knowledge documents"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { message = null; saveTo.launch(suggestedName) },
                ) { Text(tr("ব্যাকআপ সংরক্ষণ", "Save backup")) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { message = null; pickFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                ) { Text(tr("ব্যাকআপ থেকে ফেরান", "Restore from backup")) }
            }
            if (busy) Text(tr("কাজ চলছে…", "Working…"), style = MaterialTheme.typography.labelSmall)
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                tr("ফাইলে আপনার লেখা সাধারণ টেক্সট হিসেবে থাকে — যেখানে রাখছেন, সেটি নিরাপদ কিনা ভেবে দেখুন। রিস্টোর কখনো কিছু মুছে ফেলে না; একই ডকুমেন্ট আবার যোগ হয় না।",
                    "The file holds your text as plain text — choose a safe place for it. Restore never deletes anything, and the same document is not added twice."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

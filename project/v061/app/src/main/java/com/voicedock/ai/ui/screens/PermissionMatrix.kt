package com.voicedock.ai.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.permissions.Access
import com.voicedock.ai.permissions.Feature
import com.voicedock.ai.permissions.PermissionCatalog
import com.voicedock.ai.permissions.PermissionGate
import com.voicedock.ai.permissions.PermissionItem
import com.voicedock.ai.permissions.PermissionProbe
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr

/**
 * v1.2.0 — **every** permission in one place, generated from [PermissionCatalog].
 *
 * v1.1.0's hand-written rows answered "does the bubble work?" but could not answer "is anything the app
 * declares still un-granted?" — a new `uses-permission` silently became a gap. This section is derived from
 * the catalog, so it cannot fall behind, and [com.voicedock.ai.permissions.ManifestAudit] fails the unit
 * tests if the manifest and the catalog disagree.
 *
 * Two buttons matter:
 *  * **সব অনুমতি একসাথে** — one `RequestMultiplePermissions` dialog for everything still missing;
 *  * per-row **টেস্ট** — a functional probe, because a listener can be listed as enabled yet not connected.
 */
@Composable
fun PermissionMatrix(resumeTick: Int) {
    val ctx = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    var tick by remember { mutableIntStateOf(0) }
    val rt = resumeTick + tick
    var results by remember { mutableStateOf(mapOf<String, String>()) }

    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    // Reading `rt` re-evaluates every probe after a dialog, a system settings trip, or app resume.
    val granted = remember(rt) { PermissionCatalog.actionable.associate { it.key to PermissionProbe.granted(ctx, it) } }
    fun isOn(item: PermissionItem): Boolean = granted[item.key] == true
    val summary = PermissionGate.summary { granted[it] == true }
    val missingRuntime = PermissionGate.requestable({ granted[it] == true }, Build.VERSION.SDK_INT)
    val specialMissing = PermissionGate.specialMissing { granted[it] == true }

    Text(tr("অনুমতি (সব এক জায়গায়)", "Permissions (all in one place)"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "${summary.granted}/${summary.total} টি দেওয়া আছে। " + PermissionGate.nextStep({ granted[it] == true }, true),
            "${summary.granted}/${summary.total} granted. " + PermissionGate.nextStep({ granted[it] == true }, false),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (summary.blockedFeatures.isNotEmpty()) {
        Text(
            tr("এখন যা বন্ধ: ", "Currently off: ") + summary.blockedFeatures.joinToString(", ") { Feature.label(it, bn) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (missingRuntime.isNotEmpty()) {
            TextButton(onClick = { runCatching { multi.launch(missingRuntime.toTypedArray()) } }) {
                Text(tr("সব অনুমতি একসাথে (${missingRuntime.size})", "Grant all at once (${missingRuntime.size})"))
            }
        }
        if (specialMissing.isNotEmpty()) {
            TextButton(onClick = {
                // One at a time on purpose: each of these is a different system screen.
                runCatching { PermissionProbe.settingsIntent(ctx, specialMissing.first())?.let { ctx.startActivity(it) } }
            }) { Text(tr("‘${specialMissing.first().titleBn}’ দিন", "Grant ‘${specialMissing.first().titleEn}’")) }
        }
    }

    PermissionCatalog.actionable.forEach { item ->
        val on = isOn(item)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (on) "✓ " else "✗ ") + tr(item.titleBn, item.titleEn),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (item.access == Access.SPECIAL) tr("সেটিংস", "settings") else tr("ডায়ালগ", "dialog"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(tr(item.whyBn, item.whyEn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (item.features.isNotEmpty()) {
                    Text(
                        tr("যা চালু করে: ", "Unlocks: ") + item.features.joinToString(", ") { Feature.label(it, bn) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!on) {
                        TextButton(onClick = {
                            val intent = PermissionProbe.settingsIntent(ctx, item)
                            if (intent != null) runCatching { ctx.startActivity(intent) }
                            else if (item.permission != null) runCatching { multi.launch(arrayOf(item.permission)) }
                            tick++
                        }) { Text(tr("দিন", "Grant")) }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            results = results + (item.key to PermissionProbe.test(ctx, item, bn))
                            tick++
                        }) { Text(tr("টেস্ট", "Test")) }
                    }
                }
                results[item.key]?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Text(
        tr(
            "নিজে থেকেই দেওয়া থাকে: " + PermissionCatalog.items.filter { it.access == Access.NORMAL }.joinToString(", ") { it.titleBn },
            "Granted at install: " + PermissionCatalog.items.filter { it.access == Access.NORMAL }.joinToString(", ") { it.titleEn },
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
}

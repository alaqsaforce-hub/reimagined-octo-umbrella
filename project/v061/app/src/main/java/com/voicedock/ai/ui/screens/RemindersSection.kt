package com.voicedock.ai.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.data.database.ReminderRow
import com.voicedock.ai.permissions.PermissionCatalog
import com.voicedock.ai.permissions.PermissionProbe
import com.voicedock.ai.schedule.AlarmPlanner
import com.voicedock.ai.schedule.Reminders
import com.voicedock.ai.schedule.TimeWords
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.launch

/**
 * v1.2.0 — the reminders that exist, with the honest exact/inexact line.
 *
 * A reminder lives in Room, so this list is the truth even if an alarm was missed (phone off, doze). The
 * user can cancel from here; the notification's Done/Snooze buttons do the rest.
 */
@Composable
fun RemindersSection(resumeTick: Int) {
    val ctx = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    var rows by remember { mutableStateOf<List<ReminderRow>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val exact = PermissionProbe.granted(ctx, PermissionCatalog.byKey("android.permission.SCHEDULE_EXACT_ALARM")!!)

    LaunchedEffect(resumeTick + tick) {
        rows = runCatching { Reminders.pending(ctx) }.getOrDefault(emptyList())
    }

    Text(tr("রিমাইন্ডার", "Reminders"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "বলুন: “৩টায় মনে করিয়ে দাও পানি খেতে” · “কাল সকাল ৯টায় মনে করিয়ে দাও” · “২০ মিনিট পরে মনে করিয়ে দাও”",
            "Say: “remind me at 3 to drink water” · “remind me tomorrow at 9” · “remind me in 20 minutes”",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        if (exact) {
            tr("✓ মিনিটে-মিনিটে সময়মতো বলবে", "✓ fires to the minute")
        } else {
            tr(
                "⚠ ‘ঠিক সময়ে রিমাইন্ডার’ অনুমতি নেই — কয়েক মিনিট দেরি হতে পারে। উপরের তালিকা থেকে দিন।",
                "⚠ no exact-alarm access — a reminder can be a few minutes late. Grant it in the list above.",
            )
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (!exact) {
        TextButton(onClick = {
            runCatching {
                Reminders.requestExactAlarmAccess(ctx)
            }
        }) { Text(tr("ঠিক সময়ের অনুমতি চাও", "Request exact-alarm access")) }
    }

    if (rows.isEmpty()) {
        Text(
            tr("এখন কোনো রিমাইন্ডার নেই।", "No reminders are set."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    rows.forEach { row ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    TimeWords.label(row.triggerAt, System.currentTimeMillis(), bn) + " — " + row.text,
                    style = MaterialTheme.typography.labelLarge,
                )
                val plan = AlarmPlanner.plan(System.currentTimeMillis(), row.triggerAt, exact)
                if (plan != null && !plan.exact) {
                    Text(
                        AlarmPlanner.note(plan, bn),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { Reminders.cancel(ctx, row.id) }
                            tick++
                        }
                    }) { Text(tr("বাতিল", "Cancel")) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { Reminders.snooze(ctx, row.id, 10) }
                                tick++
                            }
                        }) { Text(tr("১০ মিনিট পরে", "In 10 minutes")) }
                    }
                }
            }
        }
    }
}

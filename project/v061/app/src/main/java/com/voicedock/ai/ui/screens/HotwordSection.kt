package com.voicedock.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicedock.ai.assistant.AssistantModeCore
import com.voicedock.ai.hotword.DutyPlanner
import com.voicedock.ai.hotword.HotwordGate
import com.voicedock.ai.hotword.HotwordService
import com.voicedock.ai.hotword.WakePhrase
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr

/**
 * v1.0.0 — Settings → কমান্ড → ‘শাহরুখ’ ওয়েক-ওয়ার্ড.
 *
 * Every claim here is checkable: the switch reflects whether the microphone service is actually running,
 * the status line says why it is not listening (permission / screen off / dictation in progress), and the
 * battery-exemption button is offered only when it would change something.
 */
@Composable
fun HotwordSection(resumeTick: Int) {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val bn = s.uiLanguage == UiLanguage.BN
    var tick by remember { mutableIntStateOf(0) }
    val micOk = remember(tick, resumeTick) { Permissions.mic(ctx) }
    val running = remember(tick, resumeTick) { HotwordService.listening }
    val screenOn = remember(tick, resumeTick) {
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true }.getOrDefault(true)
    }
    val charging = remember(tick, resumeTick) {
        runCatching { (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging ?: false }.getOrDefault(false)
    }
    val batteryExempt = remember(tick, resumeTick) {
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false }.getOrDefault(false)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (ok) { HotwordService.start(ctx); SettingsStore.update { it.copy(hotwordEnabled = true) } }
    }

    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("‘শাহরুখ’ ওয়েক-ওয়ার্ড", "‘Shahrukh’ wake word"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            "“${WakePhrase.BN}” বললেই বাবল খুলে শুনতে শুরু করবে — বলিউড অভিনেতা শহরুখ খানের নামেই। “${WakePhrase.BN}, হোয়াটসঅ্যাপ খোলো” একসঙ্গে বললেও কাজ করবে।",
            "Say “${WakePhrase.EN}” and the bubble opens and starts listening — named after the actor Shahrukh Khan. “${WakePhrase.EN}, open WhatsApp” in one breath works too.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ডাকলে শুনুক", "Listen for the wake word"), Modifier.weight(1f))
        Switch(
            checked = s.hotwordEnabled,
            onCheckedChange = { v ->
                if (v && !Permissions.mic(ctx)) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    SettingsStore.update { it.copy(hotwordEnabled = v) }
                    if (v) HotwordService.start(ctx) else HotwordService.stop(ctx)
                    tick++
                }
            },
        )
    }

    Text(
        HotwordGate.status(s.hotwordEnabled, s.hotwordAlwaysOn, screenOn, bn),
        style = MaterialTheme.typography.bodySmall,
        color = if (s.hotwordEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )
    if (s.hotwordEnabled) {
        Text(
            tr(
                if (running) "মাইক খোলা আছে — উপরের নোটিফিকেশনটি Android-এর নিয়ম।"
                else "সার্ভিস চালু হয়নি — নোটিফিকেশন অনুমতি দিয়ে অ্যাপ থেকে আবার চালু করুন।",
                if (running) "The microphone is open — that notification is Android's rule."
                else "The service is not running — allow notifications and start it from the app again.",
            ),
            style = MaterialTheme.typography.labelSmall,
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("স্ক্রিন বন্ধ থাকলেও শুনুক", "Also listen with the screen off"), Modifier.weight(1f))
        Switch(
            checked = s.hotwordAlwaysOn,
            onCheckedChange = { v ->
                SettingsStore.update { it.copy(hotwordAlwaysOn = v) }
                if (v && s.hotwordEnabled) HotwordService.start(ctx)
                tick++
            },
            enabled = s.hotwordEnabled,
        )
    }
    Text(
        tr(
            "বন্ধ থাকলে শুধু স্ক্রিন জ্বালিয়ে শোনে — ব্যাটারি কম খরচ। চালু করলে ব্যাটারি-সেভিং বন্ধ করার অনুমতিও দিতে হবে, নাহলে Android কয়েক মিনিটেই সার্ভিসটি থামিয়ে দেবে।",
            "Off = only while the screen is on (less battery). On = also with the screen off; Android will then need the battery-optimisation exemption, otherwise it kills the service within minutes.",
        ),
        style = MaterialTheme.typography.labelSmall,
    )
    // v1.1.0 — the whole battery story in one line: the listening rhythm this phone will actually use.
    val duty = DutyPlanner.plan(screenOn, charging, s.hotwordAlwaysOn, s.hotwordLean)
    Text(
        (if (bn) duty.labelBn else duty.labelEn),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (HotwordService.listening) {
        Text(
            tr(
                "পরিসংখ্যান: ${HotwordService.cycles} বার শুনেছে · ${HotwordService.wakes} বার ডাক ধরা পড়েছে",
                "Stats: ${HotwordService.cycles} listen cycles · ${HotwordService.wakes} wake hits",
            ),
            style = MaterialTheme.typography.labelSmall,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ব্যাটারি বাঁচাতে থেমে থেমে শুনুক", "Duty-cycle to save battery"), Modifier.weight(1f))
        Switch(
            checked = s.hotwordLean,
            onCheckedChange = { v -> SettingsStore.update { it.copy(hotwordLean = v) }; tick++ },
        )
    }
    Text(
        tr(
            "চালু থাকলে স্ক্রিন বন্ধ অবস্থায় প্রায় ১০ সেকেন্ডে একবার শোনে (মাইক ~১০% সময় খোলা) — ডাক দিলে উত্তর দিতে কয়েক সেকেন্ড দেরি হতে পারে। চার্জে থাকলে বা স্ক্রিন জ্বললে পুরো সময় শোনে।",
            "When on, with the screen off it listens roughly once every 10 s (the mic is open ~10% of the time) — a wake word can take a few seconds longer to answer. While charging or with the screen on it listens continuously.",
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    if (!AssistantModeCore.acceptsVoice(s.mode)) {
        Text(
            tr("⚠ মোড ‘শুধু লেখা’ — মাইক খুলবে না। সেটিংস → মোড থেকে বদলান।", "⚠ Mode is ‘Text only’ — the microphone stays closed. Change it in Settings → Mode."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (s.hotwordAlwaysOn && !batteryExempt) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                runCatching {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    ctx.startActivity(i)
                }
            }) { Text(tr("ব্যাটারি-সেভিং থেকে বাদ দিন", "Exempt from battery optimisation")) }
        }
    }
    if (s.hotwordEnabled) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { HotwordService.start(ctx); tick++ }) {
                Text(tr("সার্ভিস আবার চালু করো", "Restart the listener"))
            }
            TextButton(onClick = { HotwordService.stop(ctx); tick++ }) {
                Text(tr("বন্ধ করো", "Stop"))
            }
        }
    }
    Text(
        tr(
            "গোপনীয়তা: শব্দ কোথাও পাঠানো হয় না — ফোনের নিজের স্পিচ সার্ভিস শুধু ‘শাহরুখ’ শব্দটি মেলায়, তারপর মাইক বন্ধ হয়ে বাবল খোলে। সার্ভিস বন্ধ করলে মাইকও বন্ধ।",
            "Privacy: no audio is uploaded — the phone's own speech service only compares the word “Shahrukh”, then the microphone closes and the bubble opens. Stopping the service closes the microphone.",
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

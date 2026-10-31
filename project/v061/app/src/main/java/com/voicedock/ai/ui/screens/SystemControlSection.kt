package com.voicedock.ai.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.ProviderSelfTest
import com.voicedock.ai.ai.ProviderTestResult
import com.voicedock.ai.hotword.HotwordService
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.media.MediaControl
import com.voicedock.ai.notify.NotificationAccess
import com.voicedock.ai.notify.NotifStore
import com.voicedock.ai.overlay.AssistantOverlayService
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.skills.Skill
import com.voicedock.ai.skills.Skills
import com.voicedock.ai.telecom.CallController
import com.voicedock.ai.tts.Speaker
import kotlinx.coroutines.launch

/**
 * v1.1.0 — Settings → সিস্টেম ও এজেন্ট.
 *
 * Three promises kept here, in this order:
 *  1. **Every permission the app can ask for is listed with what it unlocks** — and each row has a
 *     *test* button that actually exercises it, so "enabled" is verified instead of assumed.
 *  2. **AI providers that are already signed in are tested** (one `/models` call each, no tokens spent) and
 *     the result is shown per provider: working / failed / blocked / not configured.
 *  3. **The skill list is honest**: what works in this build, what is planned and for which version.
 */
@Composable
fun SystemControlSection(resumeTick: Int) {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val bn = s.uiLanguage == UiLanguage.BN
    var tick by remember { mutableIntStateOf(0) }
    val rt = resumeTick + tick
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val callLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val scope = rememberCoroutineScope()

    /** Result of the last "test" press, per capability id. */
    var results by remember { mutableStateOf(mapOf<String, String>()) }
    var testing by remember { mutableStateOf(false) }
    var providerResults by remember { mutableStateOf(emptyList<ProviderTestResult>()) }

    fun record(id: String, block: () -> String) {
        results = results + (id to runCatching(block).getOrElse { tr("পরীক্ষা করা যায়নি: ${it.javaClass.simpleName}", "Could not test: ${it.javaClass.simpleName}") })
        tick++
    }

    Text(tr("সিস্টেম ও এজেন্ট", "System & agent"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "যে অনুমতিগুলো দরকার, সেগুলো এখানেই দেওয়া যায় — আর প্রতিটির পাশে ‘টেস্ট’ চেপে দেখা যায় সত্যিই কাজ করছে কি না।",
            "Every permission the assistant needs is granted from here — and each row has a *test* so “enabled” can be checked instead of assumed.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    val capabilities = buildList {
        add(Cap(
            id = "overlay",
            titleBn = "বাবল (অন্য অ্যাপের উপরে)",
            titleEn = "Bubble (over other apps)",
            whyBn = "বাবল ও ডিক্টেশন দেখানোর জন্য",
            whyEn = "to show the bubble and the dictation",
            ok = Permissions.overlay(ctx),
            grantLabelBn = "অনুমতি দাও", grantLabelEn = "Allow",
            grant = { runCatching { ctx.startActivity(Permissions.overlaySettings(ctx)) }; "" },
            test = { if (!Permissions.overlay(ctx)) tr("✗ অনুমতি নেই", "✗ not granted") else { AssistantOverlayService.start(ctx, AssistantOverlayService.ACTION_SHOW); tr("✓ বাবল চালু করার অনুরোধ পাঠানো হলো — স্ক্রিনে বাবল দেখা উচিত", "✓ asked to start the bubble — it should appear on screen") } },
        ))
        add(Cap(
            id = "accessibility",
            titleBn = "অ্যাক্সেসিবিলিটি (স্ক্রিন পড়া/নিয়ন্ত্রণ)",
            titleEn = "Accessibility (read/control the screen)",
            whyBn = "স্ক্রিনের লেখা পড়া, ট্যাপ ও স্ক্রল করার জন্য",
            whyEn = "to read the screen, tap and scroll",
            ok = Permissions.accessibility(ctx),
            grantLabelBn = "সেটিংস খোলো", grantLabelEn = "Open settings",
            grant = { runCatching { ctx.startActivity(Permissions.accessibilitySettings()) }; "" },
            test = {
                if (InsertAccessibilityService.instance != null) tr("✓ সার্ভিস সংযুক্ত — স্ক্রিন পড়া যাচ্ছে", "✓ service connected — the screen can be read")
                else tr("✗ চালু আছে কিন্তু সংযুক্ত নয় — অ্যাক্সেসিবিলিটি বন্ধ করে আবার চালু করুন", "✗ enabled but not connected — toggle accessibility off and on")
            },
        ))
        add(Cap(
            id = "notif",
            titleBn = "নোটিফিকেশন অ্যাক্সেস",
            titleEn = "Notification access",
            whyBn = "মেসেজ সারমর্ম, উত্তর, কল ধরা, মিউজিক নিয়ন্ত্রণ",
            whyEn = "messages, replies, answering calls, media control",
            ok = NotificationAccess.isGranted(ctx),
            grantLabelBn = "সেটিংস খোলো", grantLabelEn = "Open settings",
            grant = { NotificationAccess.openSettings(ctx); "" },
            test = {
                val n = runCatching { NotifStore(ctx.filesDir, com.voicedock.ai.VoiceDock.db(ctx).assistant()).all().size }.getOrDefault(0)
                val media = MediaControl.sessionCount(ctx)
                when {
                    !NotificationAccess.isGranted(ctx) -> tr("✗ অনুমতি নেই", "✗ not granted")
                    media < 0 -> tr("✗ সংরক্ষণ করা যাচ্ছে না", "✗ cannot read notifications")
                    else -> tr("✓ কাজ করছে — $n টি নোটিফিকেশন সংরক্ষিত, $media টি মিডিয়া সেশন দেখা যাচ্ছে", "✓ working — $n notifications stored, $media media sessions visible")
                }
            },
        ))
        add(Cap(
            id = "mic",
            titleBn = "মাইক্রোফোন",
            titleEn = "Microphone",
            whyBn = "ডিক্টেশন ও ‘শাহরুখ’ ডাক",
            whyEn = "dictation and the “Shahrukh” wake word",
            ok = Permissions.mic(ctx),
            grantLabelBn = "অনুমতি দাও", grantLabelEn = "Allow",
            grant = { micLauncher.launch(Manifest.permission.RECORD_AUDIO); "" },
            test = {
                val available = SpeechRecognizer.isRecognitionAvailable(ctx)
                when {
                    !Permissions.mic(ctx) -> tr("✗ অনুমতি নেই", "✗ not granted")
                    !available -> tr("✗ ফোনে স্পিচ রিকগনাইজার নেই", "✗ no speech recogniser on this phone")
                    else -> tr("✓ মাইক ও রিকগনাইজার ঠিক আছে", "✓ microphone and recogniser are ready")
                }
            },
        ))
        add(Cap(
            id = "calls",
            titleBn = "কল ও কন্টাক্ট",
            titleEn = "Calls & contacts",
            whyBn = "ফোন করা, কল ধরা/কাটা, নাম থেকে নম্বর",
            whyEn = "place, answer, decline, name → number",
            ok = Permissions.contacts(ctx),
            grantLabelBn = "অনুমতি দাও", grantLabelEn = "Allow",
            grant = {
                callLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS))
                ""
            },
            test = {
                val calls = CallController(ctx)
                val contacts = runCatching {
                    ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { it.count } ?: 0
                }.getOrDefault(-1)
                when {
                    contacts < 0 -> tr("✗ কন্টাক্ট পড়া যাচ্ছে না", "✗ contacts cannot be read")
                    !calls.canAnswer() -> tr("✗ কল ধরার অনুমতি নেই", "✗ answer permission missing")
                    else -> tr("✓ ঠিক আছে — $contacts টি কন্টাক্ট পড়া যাচ্ছে", "✓ ready — $contacts contacts readable")
                }
            },
        ))
        add(Cap(
            id = "hotword",
            titleBn = "‘শাহরুখ’ ওয়েক-ওয়ার্ড",
            titleEn = "‘Shahrukh’ wake word",
            whyBn = "হাত না লাগিয়েই ডাক দেওয়ার জন্য",
            whyEn = "to call the assistant without touching the phone",
            ok = HotwordService.listening,
            grantLabelBn = "চালু করো", grantLabelEn = "Turn on",
            grant = {
                if (!Permissions.mic(ctx)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else { SettingsStore.update { it.copy(hotwordEnabled = true) }; HotwordService.start(ctx) }
                ""
            },
            test = {
                if (HotwordService.listening) tr(
                    "✓ সার্ভিস চলছে — ${HotwordService.cycles} বার শুনেছে, ${HotwordService.wakes} বার ডাক শনাক্ত",
                    "✓ service running — ${HotwordService.cycles} listen cycles, ${HotwordService.wakes} wake hits",
                ) else tr("✗ সার্ভিস চলছে না", "✗ the service is not running")
            },
        ))
        add(Cap(
            id = "speech",
            titleBn = "কথা বলা (TTS)",
            titleEn = "Speaking (TTS)",
            whyBn = "উত্তর জোরে পড়ে শোনানোর জন্য",
            whyEn = "to read answers aloud",
            ok = true,
            grantLabelBn = "", grantLabelEn = "",
            grant = { "" },
            test = {
                val sp = Speaker(ctx)
                val ok = sp.speak(tr("পরীক্ষা — শোনা যাচ্ছে?", "Test — can you hear me?"), bn)
                if (!ok) tr("✗ এই ফোনে ভয়েস পাওয়া যাচ্ছে না (Google TTS ইনস্টল করুন)", "✗ no usable voice on this phone (install Google TTS)")
                else tr("✓ একটা পরীক্ষামূলক বাক্য বলা হচ্ছে", "✓ a test sentence is being spoken")
            },
        ))
        if (Build.VERSION.SDK_INT >= 29) {
            add(Cap(
                id = "assistant",
                titleBn = "ডিজিটাল সহকারী (হোম লম্বা চাপ)",
                titleEn = "Digital assistant (long-press home)",
                whyBn = "হোম বাটন চেপেই ডাক",
                whyEn = "call it from the home gesture",
                ok = runCatching { ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false),
                grantLabelBn = "সেট করো", grantLabelEn = "Set",
                grant = {
                    if (Build.VERSION.SDK_INT >= 29) runCatching {
                        val rm = ctx.getSystemService(RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    }
                    ""
                },
                test = {
                    val held = runCatching { ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)
                    if (held) tr("✓ সেট করা আছে — হোম লম্বা চেপে দেখুন", "✓ set — long-press home to check")
                    else tr("✗ সেট করা নেই", "✗ not set")
                },
            ))
        }
        add(Cap(
            id = "battery",
            titleBn = "ব্যাটারি-সেভিং থেকে বাদ",
            titleEn = "Battery-optimisation exemption",
            whyBn = "স্ক্রিন বন্ধ থাকলেও ডাক শোনার জন্য",
            whyEn = "to keep listening with the screen off",
            ok = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) == true }.getOrDefault(false),
            grantLabelBn = "অনুমতি দাও", grantLabelEn = "Allow",
            grant = {
                if (Build.VERSION.SDK_INT >= 23) runCatching {
                    ctx.startActivity(android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}")))
                }
                ""
            },
            test = {
                val exempt = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) == true }.getOrDefault(false)
                if (exempt) tr("✓ বাদ দেওয়া আছে", "✓ exempt") else tr("○ এখনো বাদ দেওয়া নেই", "○ not exempt yet")
            },
        ))
    }

    capabilities.forEach { cap -> CapabilityRow(cap, bn, results[cap.id], onTest = { record(cap.id) { cap.test() } }) }

    // ---- AI providers that are already set up ----
    HorizontalDivider(Modifier.padding(top = 10.dp))
    Text(tr("AI প্রোভাইডার — সত্যিই কাজ করছে?", "AI providers — do they actually work?"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            "সাইন-ইন করা বা কি দেওয়া সব প্রোভাইডারে একটি ছোট পরীক্ষামূলক কল যায় (কোনো টোকেন খরচ হয় না)। ফলাফল নিচে দেখা যায়।",
            "Every provider that has a key or a signed-in account gets one small test call (no tokens spent). Results appear below.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (testing) return@TextButton
            testing = true
            scope.launch {
                providerResults = runCatching { ProviderSelfTest.run(ctx, AiProvider.entries) }.getOrDefault(emptyList())
                testing = false
            }
        }) { Text(if (testing) tr("পরীক্ষা চলছে…", "Testing…") else tr("সব প্রোভাইডার টেস্ট করো", "Test all providers")) }
    }
    if (providerResults.isNotEmpty()) {
        Text(
            com.voicedock.ai.ai.ProviderSelfTestCore.summarize(providerResults, bn),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        providerResults.forEach { r ->
            val (label, detail) = r.provider.label to com.voicedock.ai.ai.ProviderSelfTestCore.detailFor(r.status, r.detail, bn)
            Text("• $label — $detail", style = MaterialTheme.typography.labelSmall)
        }
    }

    // ---- local models ----
    HorizontalDivider(Modifier.padding(top = 10.dp))
    Text(tr("লোকাল মডেল (ইন্টারনেট ছাড়াই)", "Local models (no internet)"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            "ফোন বা একই Wi-Fi-এর কম্পিউটারে Ollama / llama.cpp / LM Studio চালিয়ে Settings → AI → Custom-এ http://192.168.x.x:11434/v1 বসান। ছোট মডেল: qwen2.5:1.5b (≈1 GB RAM, দ্রুত) · gemma2:2b (≈2 GB) · llama3.2:3b (≈3 GB, বেশি বুদ্ধিমান)। ৪ GB র‍্যামের ফোনে ১.৫B-ই নিরাপদ। কথা শোনার জন্য Whisper মডেল আগেই আছে (Settings → Whisper)।",
            "Run Ollama / llama.cpp / LM Studio on the phone or a computer on the same Wi-Fi, then put http://192.168.x.x:11434/v1 into Settings → AI → Custom. Small models: qwen2.5:1.5b (≈1 GB RAM, fast) · gemma2:2b (≈2 GB) · llama3.2:3b (≈3 GB, smarter). On a 4 GB phone, 1.5B is the safe choice. Whisper models for listening are already built in (Settings → Whisper).",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    // ---- skills ----
    HorizontalDivider(Modifier.padding(top = 10.dp))
    Text(tr("স্কিল", "Skills"), style = MaterialTheme.typography.titleSmall)
    Text(Skills.summary(bn), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Skills.byGroup().forEach { (group, skills) ->
        Text(tr(group.titleBn, group.titleEn), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        skills.forEach { sk -> SkillRow(sk, bn) }
    }
}

private data class Cap(
    val id: String,
    val titleBn: String,
    val titleEn: String,
    val whyBn: String,
    val whyEn: String,
    val ok: Boolean,
    val grantLabelBn: String,
    val grantLabelEn: String,
    /** Goes to the system screen / requests the permission. Returns a message to show, usually "". */
    val grant: () -> String,
    /** Actually exercises the capability and reports the truth. */
    val test: () -> String,
)

@Composable
private fun CapabilityRow(cap: Cap, bn: Boolean, result: String?, onTest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(cap.titleBn, cap.titleEn), style = MaterialTheme.typography.labelLarge)
            Text(tr(cap.whyBn, cap.whyEn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                if (cap.ok) tr("✓ দেওয়া আছে", "✓ granted") else tr("✗ দেওয়া নেই", "✗ not granted"),
                style = MaterialTheme.typography.labelMedium,
                color = if (cap.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (cap.grantLabelBn.isNotBlank() && !cap.ok) {
                    TextButton(onClick = { cap.grant() }) { Text(tr(cap.grantLabelBn, cap.grantLabelEn)) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onTest) { Text(tr("টেস্ট", "Test")) }
                }
            }
            if (result != null) {
                Text(result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, bn: Boolean) {
    Text(
        (if (skill.ready) "✓ " else "○ ") + tr(skill.nameBn, skill.nameEn) +
            if (!skill.ready) "  (" + tr(skill.noteBn, skill.noteEn) + ")" else "",
        style = MaterialTheme.typography.labelSmall,
        color = if (skill.ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
    )
}

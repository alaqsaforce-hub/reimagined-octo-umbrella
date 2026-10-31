package com.voicedock.ai.ui.screens

import android.Manifest
import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicedock.ai.hotword.HotwordService
import com.voicedock.ai.hotword.WakePhrase
import com.voicedock.ai.notify.NotificationAccess
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr

/**
 * v1.0.0 — the first-run guide.
 *
 * The rule the whole app is built on: nothing is asked for without saying what it is for, and nothing is
 * required. Every step is skippable, every step shows ✓/✗ truthfully, and the two that decide whether the
 * assistant works at all (overlay + microphone) say so in the first line instead of hiding behind wording.
 *
 * One screen, five steps, no wizard that traps the user: "পরে করবো" always leaves.
 */
@Composable
fun OnboardingScreen(resumeTick: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val callLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val rt = resumeTick + tick

    val overlay = remember(rt) { Permissions.overlay(ctx) }
    val notifAccess = remember(rt) { NotificationAccess.isGranted(ctx) }
    val mic = remember(rt) { Permissions.mic(ctx) }
    val hotword = remember(rt) { SettingsStore.state.value.hotwordEnabled }
    val calls = remember(rt) { Permissions.contacts(ctx) }
    val assistantRole = remember(rt) {
        if (Build.VERSION.SDK_INT < 29) null else runCatching {
            ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("VoiceDock তৈরি করা যাক", "Let's set up VoiceDock"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr(
                "প্রতিটি অনুমতি কী কাজে লাগে তা লেখা আছে — যা না দিলে সেটি শুধু কাজ করবে না, বাকি অ্যাপ চলবে। যেকোনো সময় ‘পরে করবো’ চাপতে পারো।",
                "Each permission says what it is for. Anything you skip simply will not work — the rest of the app still does. You can leave with ‘Later’ at any time.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Step(
            n = "1",
            title = tr("অন্য অ্যাপের উপরে বাবল", "The bubble over other apps"),
            why = tr(
                "এটি ছাড়া বাবল দেখানোই সম্ভব নয় — এটিই অ্যাপের মূল কাজ।",
                "Without it the bubble cannot appear at all — this is the core of the app.",
            ),
            ok = overlay,
            okText = tr("দেওয়া আছে ✓", "Granted ✓"),
            noText = tr("দেওয়া হয়নি", "Not granted"),
            actionLabel = tr("অনুমতি দাও", "Allow"),
            action = { runCatching { ctx.startActivity(Permissions.overlaySettings(ctx)) } },
        )

        Step(
            n = "2",
            title = tr("মাইক্রোফোন", "Microphone"),
            why = tr(
                "ডিক্টেশন ও ‘${WakePhrase.BN}’ ডাক শোনার জন্য। মাইক শুধু লেখা/ডাক শোনার সময় খোলা থাকে।",
                "For dictation and for hearing “${WakePhrase.EN}”. The microphone is open only while listening.",
            ),
            ok = mic,
            okText = tr("দেওয়া আছে ✓", "Granted ✓"),
            noText = tr("দেওয়া হয়নি", "Not granted"),
            actionLabel = tr("অনুমতি দাও", "Allow"),
            action = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )

        Step(
            n = "3",
            title = tr("নোটিফিকেশন অ্যাক্সেস (ঐচ্ছিক)", "Notification access (optional)"),
            why = tr(
                "মেসেজের সারমর্ম, সরাসরি উত্তর, কল ধরা এবং গান নিয়ন্ত্রণ — এই চারটি এর উপর চলে।",
                "Message digest, direct reply, answering calls and media control all run on this.",
            ),
            ok = notifAccess,
            okText = tr("চালু ✓", "On ✓"),
            noText = tr("বন্ধ", "Off"),
            actionLabel = tr("সেটিংস খোলো", "Open settings"),
            action = { NotificationAccess.openSettings(ctx) },
        )

        Step(
            n = "4",
            title = tr("ফোন ও কন্টাক্ট (ঐচ্ছিক)", "Calls and contacts (optional)"),
            why = tr(
                "“মাকে ফোন করো” বললে নাম থেকে নম্বর বের করতে এবং কল ধরতে/কাটতে দরকার।",
                "Needed to turn “call mom” into a number, and to answer or decline a call.",
            ),
            ok = calls,
            okText = tr("দেওয়া আছে ✓", "Granted ✓"),
            noText = tr("দেওয়া হয়নি", "Not granted"),
            actionLabel = tr("অনুমতি দাও", "Allow"),
            action = {
                callLauncher.launch(
                    arrayOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.ANSWER_PHONE_CALLS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_CONTACTS,
                    ),
                )
            },
        )

        Step(
            n = "5",
            title = tr("‘${WakePhrase.BN}’ ওয়েক-ওয়ার্ড (ঐচ্ছিক)", "‘${WakePhrase.EN}’ wake word (optional)"),
            why = tr(
                "চালু করলে “${WakePhrase.BN}” বলেই বাবল খুলবে — মাইক খোলা থাকবে বলে একটি নোটিফিকেশন দেখাবে।",
                "When on, saying “${WakePhrase.EN}” opens the bubble — the open microphone shows as a notification.",
            ),
            ok = hotword,
            okText = tr("চালু ✓", "On ✓"),
            noText = tr("বন্ধ", "Off"),
            actionLabel = tr("চালু করো", "Turn on"),
            action = {
                if (!Permissions.mic(ctx)) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else { SettingsStore.update { it.copy(hotwordEnabled = true) }; HotwordService.start(ctx); tick++ }
            },
        )

        if (assistantRole != null) {
            Step(
                n = "6",
                title = tr("ডিজিটাল সহকারী (ঐচ্ছিক)", "Digital assistant (optional)"),
                why = tr(
                    "হোম বাটন লম্বা চেপেই বাবল খুলবে। অন্য সহকারী (Google) বদলে যাবে — ফিরিয়েও দেওয়া যাবে।",
                    "Long-press home opens the bubble. It replaces any other assistant (e.g. Google) — reversible.",
                ),
                ok = assistantRole,
                okText = tr("সেট করা আছে ✓", "Set ✓"),
                noText = tr("সেট করা নেই", "Not set"),
                actionLabel = tr("সেট করো", "Set"),
                action = {
                    // RoleManager itself is API 29+: the whole request lives behind the version check.
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching {
                            val rm = ctx.getSystemService(RoleManager::class.java)
                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                                ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                            }
                        }
                    }
                },
            )
        }

        Text(
            tr(
                "ডেটা কোথাও যায় না: সব লেখা, মনে রাখা কথা ও মেসেজের সারমর্ম ফোনেই থাকে। AI ব্যবহার করলে সেটি আলাদা করে জানানো হয়।",
                "No data leaves the phone: text, memories and message summaries stay local. Using an AI provider is stated separately.",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text(tr("পরে করবো", "Later")) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(tr("শুরু করো →", "Start →")) }
            }
        }
    }
}

@Composable
private fun Step(
    n: String,
    title: String,
    why: String,
    ok: Boolean,
    okText: String,
    noText: String,
    actionLabel: String,
    action: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text("$n. $title", style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ok) okText else noText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    if (!ok) TextButton(onClick = action) { Text(actionLabel) }
                }
            }
        }
    }
}

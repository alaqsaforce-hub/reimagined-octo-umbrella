package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicedock.ai.mcp.McpClient
import com.voicedock.ai.mcp.McpCore
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.4.0 — MCP servers, added by the user and never assumed.
 *
 * The honest framing is on the screen itself: a tool server can do anything *it* can do, so VoiceDock lists the
 * tools, runs one only when asked by name, and always shows a confirmation card first. The address is stored
 * with the other non-secret settings; the token goes to the Keystore-backed [SecretStore].
 */
@Composable
fun McpSection() {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val bn = s.uiLanguage == UiLanguage.BN
    val servers = s.mcpServersSafe()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Text(tr("MCP (টুল সার্ভার)", "MCP (tool servers)"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "MCP হলো এমন সার্ভার যা অ্যাপকে টুল দেয় (ফাইল পড়া, GitHub ইস্যু তৈরি ইত্যাদি)। অ্যাপ শুধু সার্ভার যেসব টুল " +
                "বলে সেগুলোই দেখায় — নিজে নাম বানায় না — আর কোনো টুল চালানোর আগে সবসময় নিশ্চিত করে জিজ্ঞেস করে, " +
                "কারণ টুলটি কী করবে তা অ্যাপের জানার উপায় নেই। টোকেন এনক্রিপ্টেড ভাবে ফোনেই থাকে।",
            "MCP is a server that hands the app tools (read files, open a GitHub issue, …). VoiceDock shows only the " +
                "tools a server actually lists — it never invents a name — and asks for confirmation before running " +
                "one, because the app cannot know what a third-party tool does. Tokens stay encrypted on the phone.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    if (servers.isEmpty()) {
        Text(tr("এখনো কোনো সার্ভার নেই।", "No server yet."), style = MaterialTheme.typography.labelSmall)
    }

    servers.forEach { sv ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(sv.label, style = MaterialTheme.typography.bodyMedium)
                Text(McpCore.redact(sv.url), style = MaterialTheme.typography.labelSmall)
                if (sv.tokenName != null) {
                    Text(
                        if (SecretStore.has(ctx, sv.tokenName)) tr("✓ টোকেন সংরক্ষিত", "✓ token saved")
                        else tr("টোকেন দেওয়া হয়নি", "no token"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Switch(
                checked = sv.enabled,
                onCheckedChange = { on ->
                    SettingsStore.update { st ->
                        st.copy(mcpServers = st.mcpServers.map { if (it.id == sv.id) it.copy(enabled = on) else it })
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = testing == null,
                onClick = {
                    testing = sv.id
                    status = null
                    scope.launch {
                        val t = sv.tokenName?.let { SecretStore.get(ctx, it) }
                        val out = withContext(Dispatchers.IO) { McpClient.connect(sv, t) }
                        testing = null
                        status = if (out.ok) {
                            if (bn) "✓ ${out.tools.size}টি টুল: ${out.tools.take(6).joinToString(", ") { it.name }}"
                            else "✓ ${out.tools.size} tools: ${out.tools.take(6).joinToString(", ") { it.name }}"
                        } else {
                            McpCore.failure(out.reason, bn)
                        }
                    }
                },
            ) { Text(if (testing == sv.id) tr("যাচ্ছি…", "Contacting…") else tr("টুল দেখুন", "List tools")) }
            TextButton(onClick = {
                sv.tokenName?.let { SecretStore.remove(ctx, it) }
                SettingsStore.update { st -> st.copy(mcpServers = st.mcpServers.filterNot { it.id == sv.id }) }
                McpClient.forget(sv.id)
                status = null
            }) { Text(tr("মুছুন", "Remove"), color = MaterialTheme.colorScheme.error) }
        }
    }

    OutlinedTextField(
        value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("নাম (যেমন files)", "Name (e.g. files)")) },
    )
    OutlinedTextField(
        value = url, onValueChange = { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("ঠিকানা (https://…/mcp)", "Address (https://…/mcp)")) },
    )
    OutlinedTextField(
        value = token, onValueChange = { token = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("টোকেন (ঐচ্ছিক)", "Token (optional)")) },
        visualTransformation = PasswordVisualTransformation(),
    )
    Button(
        enabled = url.length >= 12,
        onClick = {
            val made = McpCore.serverFrom(name, url)
            if (made == null) {
                status = McpCore.failure("no_url", bn)
            } else {
                val tokenName = if (token.isBlank()) null else "mcp_token_${made.id}"
                tokenName?.let { SecretStore.put(ctx, it, token) }
                val withToken = made.copy(tokenName = tokenName)
                SettingsStore.update { st -> st.copy(mcpServers = McpCore.sanitize(st.mcpServers + withToken)) }
                name = ""; url = ""; token = ""
                status = tr("যোগ হয়েছে — এখন ‘টুল দেখুন’ চেপে যাচাই করুন।", "Added — tap ‘List tools’ to verify.")
            }
        },
    ) { Text(tr("সার্ভার যোগ করুন", "Add server")) }

    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

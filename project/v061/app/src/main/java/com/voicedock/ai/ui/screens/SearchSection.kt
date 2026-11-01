package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicedock.ai.search.SearchClient
import com.voicedock.ai.search.SearchProvider
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.2.1 — internet search, with the provider chosen (and paid for) by the user.
 *
 * Nothing is scraped and nothing is proxied through a server of ours: Brave/Tavily use the user's own key,
 * SearxNG is a URL the user runs. With none configured the assistant still answers — it opens the browser and
 * says so, which is more useful than an error.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSection() {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val bn = s.uiLanguage == UiLanguage.BN
    val provider = SearchProvider.byId(s.searchProvider)
    var keyInput by remember(provider) { mutableStateOf("") }
    var searx by remember(s.searxUrl) { mutableStateOf(s.searxUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(tr("ইন্টারনেট সার্চ", "Internet search"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "‘খোঁজো আজকের আবহাওয়া’ বললে ফোনের ব্রাউজার খুলবে; নিচের কোনো একটি দিলে ফলাফল পড়ে সারমর্মও বলবে। কোনো সার্ভার সেট না থাকলেও উত্তর মেলে — শুধু সারমর্মটা মেলে না।",
            "Say ‘search today’s weather’ and it opens the browser; with one of these set it also summarises the results. With none set you still get an answer — just not a summary.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchProvider.entries.forEach { p ->
            FilterChip(
                selected = provider == p,
                onClick = { SettingsStore.update { it.copy(searchProvider = p.id) }; status = null },
                label = { Text(p.label) },
            )
        }
    }

    if (provider.needsKey) {
        TextButton(onClick = {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(provider.keyUrl),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text(tr("${provider.label} key বানান →", "Get a ${provider.label} key →")) }
        if (SecretStore.has(ctx, SearchClient.keyName(provider))) {
            Text(tr("✓ key সংরক্ষিত (এনক্রিপ্টেড)", "✓ key saved (encrypted)"), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { SecretStore.remove(ctx, SearchClient.keyName(provider)); status = null }) {
                Text(tr("key মুছুন", "Remove key"), color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it.trim() }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${provider.label} API key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            enabled = keyInput.length >= 8,
            onClick = { SecretStore.put(ctx, SearchClient.keyName(provider), keyInput); keyInput = ""; status = null },
        ) { Text(tr("key সংরক্ষণ", "Save key")) }
    }

    if (provider == SearchProvider.SEARXNG) {
        OutlinedTextField(
            value = searx, onValueChange = { searx = it.trim() }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("SearxNG ঠিকানা (যেমন 192.168.0.4:8888)", "SearxNG URL (e.g. 192.168.0.4:8888)")) },
        )
        OutlinedButton(onClick = { SettingsStore.update { it.copy(searxUrl = searx) }; status = null }) {
            Text(tr("ঠিকানা সংরক্ষণ", "Save URL"))
        }
    }

    OutlinedButton(
        enabled = provider != SearchProvider.NONE && !testing,
        onClick = {
            testing = true
            status = null
            scope.launch {
                val out = withContext(Dispatchers.IO) { SearchClient.run(ctx, "বাংলাদেশের রাজধানী") }
                status = if (out.ok) {
                    tr(
                        "✓ কাজ করছে — ${out.hits.size} টি ফলাফল: ${out.hits.first().title.take(50)}",
                        "✓ working — ${out.hits.size} results: ${out.hits.first().title.take(50)}",
                    )
                } else {
                    "✗ " + SearchClient.explain(out.reason, bn)
                }
                testing = false
            }
        },
    ) { Text(if (testing) tr("খুঁজছি…", "Searching…") else tr("টেস্ট সার্চ", "Test search")) }

    status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    if (provider == SearchProvider.NONE) {
        Text(
            tr("কোনো সার্চ সার্ভার নেই — শুধু ব্রাউজার খুলবে।", "No search provider — the browser will open instead."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

package dev.codex.go.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codex.go.R
import dev.codex.go.i18n.*
import dev.codex.go.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable fun PluginsScreen(manager: PluginManager) {
    val context = LocalContext.current
    val plugins by manager.installed.collectAsStateWithLifecycle()
    val refreshing by manager.refreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) { manager.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = LocalBentoColors.current.lavender, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Extension, null, tint = LocalBentoColors.current.onLavender)
                Text(tr(R.string.plugin_tools), style = MaterialTheme.typography.titleLarge, color = LocalBentoColors.current.onLavender)
                Text(tr(R.string.plugin_intro), style = MaterialTheme.typography.bodyMedium, color = LocalBentoColors.current.onLavender)
                FilledTonalButton(onClick = manager::refresh, enabled = !refreshing) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.plugin_refresh)) }
            }
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (plugins.isEmpty() && !refreshing) Panel(tr(R.string.plugin_empty)) { Text(tr(R.string.plugin_install_hint), style = MaterialTheme.typography.bodyMedium) }
        plugins.forEach { plugin -> key(plugin.key) {
            Panel(plugin.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.packageName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (plugin.version.isNotBlank()) Text(plugin.version, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(plugin.enabled, { enabled -> runCatching { manager.setEnabled(plugin.key, enabled); results.remove(plugin.key) }.onFailure { results[plugin.key] = it.message.orEmpty() } }, enabled = plugin.error == null,
                        modifier = Modifier.semantics { contentDescription = tr(R.string.plugin_enable_arg, plugin.name) })
                }
                plugin.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                plugin.manifest?.let { manifest ->
                    if (manifest.description.isNotBlank()) Text(manifest.description, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(tr(R.string.plugin_tools_count_arg, manifest.tools.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    manifest.tools.forEach { tool -> Text(tool.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch {
                            testing[plugin.key] = true
                            try { manager.ping(plugin.key); results[plugin.key] = tr(R.string.plugin_connected) }
                            catch (error: TimeoutCancellationException) { results[plugin.key] = tr(R.string.timed_out) }
                            catch (error: CancellationException) { results[plugin.key] = tr(R.string.plugin_call_cancelled) }
                            catch (error: Exception) { results[plugin.key] = error.message.orEmpty() }
                            finally { testing[plugin.key] = false }
                        } }, enabled = plugin.enabled && testing[plugin.key] != true) { Text(tr(if (testing[plugin.key] == true) R.string.plugin_testing else R.string.plugin_test)) }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${plugin.packageName}"))) }) { Text(tr(R.string.plugin_app_info)) }
                    }
                    results[plugin.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } }
        Text(tr(R.string.plugin_new_chats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

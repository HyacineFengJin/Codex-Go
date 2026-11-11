package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.codex.go.shell.*
import dev.codex.go.protocol.objects
import kotlinx.coroutines.launch

@Composable fun ShellAccessPanel(access: ShellAccess, select: (ShellBackend) -> Unit, connect: (ShellBackend) -> Unit, test: suspend () -> org.json.JSONObject) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var wiredHelp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val icons = mapOf(ShellBackend.Wireless to Icons.Outlined.Wifi, ShellBackend.Wired to Icons.Outlined.Usb,
        ShellBackend.Shizuku to Icons.Outlined.VerifiedUser, ShellBackend.Root to Icons.Outlined.Key)
    Panel("Android Shell") {
        ShellBackend.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { backend ->
                    val selected = access.selected == backend
                    val tone = when (backend) {
                        ShellBackend.Wireless -> colors.tertiaryContainer to colors.onTertiaryContainer
                        ShellBackend.Wired -> LocalBentoColors.current.peach to LocalBentoColors.current.onPeach
                        ShellBackend.Shizuku -> LocalBentoColors.current.lavender to LocalBentoColors.current.onLavender
                        ShellBackend.Root -> colors.secondaryContainer to colors.onSecondaryContainer
                    }
                    val foreground = if (selected) colors.onPrimary else tone.second
                    Surface(onClick = { select(backend) }, color = if (selected) colors.primary else tone.first, contentColor = foreground,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 8.dp),
                        modifier = Modifier.weight(1f).semantics { contentDescription = tr(R.string.select_arg , backend.displayLabel); this.selected = selected }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(icons.getValue(backend), null, Modifier.size(22.dp))
                                Spacer(Modifier.weight(1f))
                                if (selected) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = foreground)
                            }
                            Text(backend.displayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(if (backend in access.ready) tr(R.string.connected) else if (backend in access.connecting) tr(R.string.connecting) else tr(R.string.not_connected_c74894), style = MaterialTheme.typography.labelSmall, color = foreground)
                        }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!access.connected) Text(access.selected.displayDetail, style = MaterialTheme.typography.bodyMedium)
                Text(metadata(access.status(access.selected)), style = MaterialTheme.typography.bodySmall, color = if (access.connected) colors.primary else colors.onSurfaceVariant)
                if (access.selected == ShellBackend.Wireless) Text(tr(R.string.pair_or_reconnect_above), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                else Button(onClick = { if (access.selected == ShellBackend.Wired) { if (access.wiredCommand.isBlank()) connect(ShellBackend.Wired); wiredHelp = true } else connect(access.selected) },
                    enabled = access.selected !in access.connecting, modifier = Modifier.fillMaxWidth()) {
                    Text(when (access.selected) { ShellBackend.Wired -> tr(R.string.computer_adb_setup_guide); ShellBackend.Shizuku -> tr(R.string.authorize_connect_shizuku); else -> tr(R.string.request_root_access) })
                }
            }
        }
        if (access.connected) TextButton(enabled = !testing, onClick = {
            testing = true
            scope.launch {
                try { val result = test(); testResult = result.optJSONArray("contentItems")?.objects()?.firstOrNull()?.optString("text") ?: tr(R.string.no_result_received) }
                catch (error: Exception) { testResult = error.message ?: tr(R.string.connection_test_failed) }
                finally { testing = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) tr(R.string.testing) else tr(R.string.test_shell_connection)) }
    }
    if (testResult != null) AlertDialog(onDismissRequest = { testResult = null }, title = { Text(tr(R.string.shell_connection_test)) }, text = { Text(testResult.orEmpty(), Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }, confirmButton = { TextButton(onClick = { testResult = null }) { Text(tr(R.string.done)) } })
    if (wiredHelp) AlertDialog(onDismissRequest = { wiredHelp = false }, icon = { Icon(Icons.Outlined.Usb, null) }, title = { Text(tr(R.string.start_shell_from_a_computer)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr(R.string.text_1_connect_with_usb_and_allow_usb_debugging_2_run_the_command_belo))
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                Text(access.wiredCommand.ifBlank { tr(R.string.preparing_command) }, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(metadata(access.status(ShellBackend.Wired)), style = MaterialTheme.typography.bodySmall, color = colors.primary)
            Text(tr(R.string.this_command_is_valid_only_during_the_current_wait_you_can_unplug), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton(onClick = { connect(ShellBackend.Wired) }) { Text(tr(R.string.regenerate_start_command)) }
        }
    }, confirmButton = { Button(enabled = access.wiredCommand.isNotBlank(), onClick = { copy(context, access.wiredCommand) }) { Text(tr(R.string.copy_start_command)) } }, dismissButton = { TextButton(onClick = { wiredHelp = false }) { Text(tr(R.string.done)) } })
}

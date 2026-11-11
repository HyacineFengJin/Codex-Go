@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codex.go.phone.PhoneAccessService
import dev.codex.go.runtime.*
import dev.codex.go.shell.ShellBackend

@Composable fun QuickControls(state: EngineState, engine: EngineService, start: () -> Unit,
    device: () -> Unit, settings: () -> Unit, close: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val accessibility by PhoneAccessService.connected.collectAsStateWithLifecycle()
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        overlayAllowed = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    fun wireless() {
        if (context.getSharedPreferences("codex_go", 0).getInt("lastWirelessPort", 0) > 0) engine.reconnectWireless()
        else engine.startPairing()
        DeviceSettings.wireless(context)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) wireless() else error = tr(R.string.pairing_notifications_are_off_use_the_pairing_window_or_manual_po)
    }
    fun openWireless() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else wireless()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .9f)) {
            Row(Modifier.padding(horizontal = 22.dp).padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoIcon(Icons.Outlined.Bolt)
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.quick_controls), style = MaterialTheme.typography.headlineSmall)

                }
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, tr(R.string.close_quick_controls)) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = colors.primaryContainer, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.phase == EnginePhase.Preparing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(if (state.phase == EnginePhase.Ready) Icons.Outlined.CheckCircle else Icons.Outlined.RocketLaunch, null)
                        Column(Modifier.weight(1f)) {
                            Text(if (state.phase == EnginePhase.Ready) tr(R.string.ready) else if (state.phase == EnginePhase.Preparing) tr(R.string.preparing_5f2ed7) else tr(R.string.not_started), style = MaterialTheme.typography.headlineSmall)
                            if (state.phase == EnginePhase.Preparing) Text(metadata(state.status), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.phase != EnginePhase.Ready) Button(onClick = start, enabled = state.phase != EnginePhase.Preparing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = tr(R.string.start_local_codex) }) { Text(tr(R.string.start_codex)) }
                    else TextButton(onClick = engine::prepareForUse, modifier = Modifier.align(Alignment.End).semantics { contentDescription = tr(R.string.restore_existing_connections) }) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr(R.string.reconnect))
                    }
                }
            }
            Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ControlStatus(Icons.Outlined.TouchApp, tr(R.string.screen_control), when {
                        state.phoneEnabled -> if (accessibility) tr(R.string.ready_accessibility) else tr(R.string.ready_adb)
                        accessibility || state.adbConnected -> if (state.phase == EnginePhase.Ready) tr(R.string.paused_for_this_session) else tr(R.string.waiting_for_startup)
                        else -> tr(R.string.not_connected)
                    }, state.phoneEnabled)
                    if (!accessibility) OutlinedButton(onClick = { DeviceSettings.accessibility(context) }, Modifier.fillMaxWidth()) { Text(tr(R.string.connect_accessibility)) }
                    if (state.phase == EnginePhase.Ready && !state.phoneEnabled && (accessibility || state.adbConnected))
                        TextButton(onClick = { engine.setPhoneEnabled(true) }) { Text(tr(R.string.resume_phone_capability)) }
                    HorizontalDivider()
                    ControlStatus(Icons.Outlined.Terminal, "Android Shell", when {
                        state.shellEnabled -> tr(R.string.ready_arg , state.shellAccess.selected.displayLabel)
                        state.shellAccess.connected -> tr(R.string.connected_arg , state.shellAccess.selected.displayLabel)
                        else -> state.shellAccess.status(state.shellAccess.selected)
                    }, state.shellEnabled)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.shellAccess.selected == ShellBackend.Wireless && !state.adbConnected)
                            FilledTonalButton(onClick = ::openWireless, modifier = Modifier.weight(1f)) { Text(tr(R.string.connect_wireless_debugging)) }
                        if (state.shellAccess.selected !in setOf(ShellBackend.Wireless) && !state.shellAccess.connected)
                            FilledTonalButton(onClick = { if (state.shellAccess.selected == ShellBackend.Wired) device() else engine.connectShellBackend(state.shellAccess.selected) }, modifier = Modifier.weight(1f)) { Text(tr(R.string.connect_arg , state.shellAccess.selected.displayLabel)) }
                        OutlinedButton(onClick = device, modifier = Modifier.weight(1f).semantics { contentDescription = tr(R.string.connection_details) }) { Text(tr(R.string.more_options)) }
                    }
                    if (state.phase == EnginePhase.Ready && state.shellAccess.connected && !state.shellEnabled)
                        TextButton(onClick = { engine.setShellEnabled(true) }) { Text(tr(R.string.resume_shell_capability)) }
                }
            }
            Surface(color = LocalBentoColors.current.lavender, contentColor = LocalBentoColors.current.onLavender, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(R.string.floating_chat), style = MaterialTheme.typography.titleMedium)
                            if (!overlayAllowed) Text(tr(R.string.floating_window_permission_needed), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.chatOverlayEnabled, { enabled -> engine.setChatOverlayEnabled(enabled); if (enabled && !overlayAllowed) DeviceSettings.overlay(context) }, modifier = Modifier.semantics { contentDescription = tr(R.string.quick_floating_chat) })
                    }
                    if (state.chatOverlayEnabled && !overlayAllowed) TextButton(onClick = { DeviceSettings.overlay(context) }) { Text(tr(R.string.allow_floating_windows)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.auto_prepare), style = MaterialTheme.typography.titleMedium)
                }
                Switch(state.autoPrepare, engine::setAutoPrepare, modifier = Modifier.semantics { contentDescription = tr(R.string.prepare_automatically_next_time) })
            }
            error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = settings, Modifier.fillMaxWidth().semantics { contentDescription = tr(R.string.account_more_settings) }) { Text(tr(R.string.more_settings)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }
}

@Composable private fun ControlStatus(icon: ImageVector, title: String, detail: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(metadata(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

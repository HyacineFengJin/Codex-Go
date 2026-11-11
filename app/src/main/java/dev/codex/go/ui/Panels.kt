@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardType
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codex.go.BuildConfig
import dev.codex.go.phone.PhoneAccessService
import dev.codex.go.phone.ReadingPreview
import dev.codex.go.protocol.*
import dev.codex.go.runtime.*
import dev.codex.go.shell.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun SettingsScreen(state: EngineState, start: () -> Unit, engine: EngineService, quickChat: () -> Unit) {
    var logs by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf(false) }
    var recover by remember { mutableStateOf<ConfigurationTarget?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(engine::exportDiagnostics) }
    val idle = !state.busy && !state.loadingHistory && !state.accountBusy && state.sessionMutation == null
    val notifications = context.getSystemService(android.app.NotificationManager::class.java)
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var notificationsAllowed by remember { mutableStateOf(notifications.areNotificationsEnabled()) }
    var batteryExempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsAllowed = notifications.areNotificationsEnabled()
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LanguageSetting(engine)
        Panel(tr(R.string.local_engine)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.weight(1f)) { Text(tr(R.string.auto_prepare), style = MaterialTheme.typography.titleMedium) }
                Switch(state.autoPrepare, engine::setAutoPrepare, modifier = Modifier.semantics { contentDescription = tr(R.string.prepare_automatically_next_time) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.phase == EnginePhase.Preparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                Text(metadata(state.status), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.phase == EnginePhase.Failed) Text(when (state.startupStage) {
                "configuration" -> tr(R.string.configuration_check_or_recovery_failed_your_files_and_backups_wer)
                "handshake" -> tr(R.string.if_the_logs_report_a_config_toml_error_restore_the_configuration)
                "install" -> tr(R.string.check_free_space_and_retry_if_it_still_fails_repair_the_bundled_e)
                "launch" -> tr(R.string.the_bundled_environment_could_not_start_retry_then_use_the_data_p)
                "account", "models" -> tr(R.string.check_the_network_and_retry_export_diagnostics_to_inspect_the_fai)
                "history" -> tr(R.string.could_not_read_history_export_diagnostics_before_clearing_any_app)
                else -> tr(R.string.retry_startup_if_disconnections_continue_export_diagnostics_and_r)
            }, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            if (state.phase == EnginePhase.Ready) OutlinedButton(onClick = { confirmStop = true }, Modifier.fillMaxWidth()) { Text(tr(R.string.stop_local_environment)) }
            else Button(onClick = start, enabled = state.phase != EnginePhase.Preparing, modifier = Modifier.fillMaxWidth()) { Text(if (state.phase == EnginePhase.Failed) tr(R.string.retry_startup) else tr(R.string.start_local_codex)) }
        }
        Panel(tr(R.string.chatgpt_account)) {
            Text(metadata(state.account), style = MaterialTheme.typography.bodyMedium)
            if (state.failure?.kind == FailureKind.Authentication) Text(tr(R.string.authentication_failed_refresh_the_account_or_sign_in_again), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            if (state.loginPending) { Text(tr(R.string.waiting_for_device_code_verification), color = MaterialTheme.colorScheme.primary); TextButton(onClick = engine::cancelLogin) { Text(tr(R.string.cancel_sign_in)) } }
            else {
                Button(onClick = engine::login, enabled = state.phase == EnginePhase.Ready && idle, modifier = Modifier.fillMaxWidth()) { Text(if (state.signedIn) tr(R.string.sign_in_to_chatgpt_again) else tr(R.string.sign_in_with_device_code)) }
                if (state.signedIn) TextButton(onClick = { confirmLogout = true }, enabled = state.phase == EnginePhase.Ready && idle, modifier = Modifier.fillMaxWidth()) { Text(if (state.accountBusy) tr(R.string.signing_out) else tr(R.string.sign_out_of_chatgpt)) }
                TextButton(onClick = engine::refreshAccount, enabled = state.phase == EnginePhase.Ready && idle && !state.refreshingAccount && state.networkAvailable, modifier = Modifier.fillMaxWidth()) { Text(if (state.refreshingAccount) tr(R.string.refreshing_account) else tr(R.string.refresh_account)) }
            }
        }
        ExpandablePanel(tr(R.string.repair_diagnostics), initiallyExpanded = state.phase == EnginePhase.Failed) {
            OutlinedButton(onClick = { logs = true }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.BugReport, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.view_logs)) }
            OutlinedButton(onClick = { export.launch("codex-go-diagnostics-${BuildConfig.VERSION_NAME}.json") }, enabled = !state.exportingDiagnostics, modifier = Modifier.fillMaxWidth()) { Text(if (state.exportingDiagnostics) tr(R.string.exporting_diagnostics) else tr(R.string.export_diagnostics)) }
            Text(tr(R.string.excludes_chats_files_and_sign_in_credentials), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { confirmRepair = true }, enabled = idle && !state.loginPending && state.phase != EnginePhase.Preparing, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(tr(R.string.repair_environment)) }
        }
        ExpandablePanel(tr(R.string.history_images_experimental), summary = if (state.historyCompression) tr(R.string.on) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.compress_older_images), style = MaterialTheme.typography.titleMedium)
                    Text(tr(R.string.read_originals_on_demand_changing_this_restarts_the_engine), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.historyCompression, engine::setHistoryCompression, enabled = idle && !state.importing && !state.loginPending && state.phase != EnginePhase.Preparing,
                    modifier = Modifier.semantics { contentDescription = tr(R.string.compress_history_images) })
            }
            Text(metadata(state.historyImageStatus), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (state.historyImageStats.compressed > 0 || state.historyImageStats.restored > 0) Text(tr(R.string.last_request_arg_older_images_arg_kb_previews_arg_original_reads , state.historyImageStats.compressed, state.historyImageStats.previewBytes / 1024, state.historyImageStats.restored), style = MaterialTheme.typography.bodySmall)
        }
        ExpandablePanel(tr(R.string.restore_configuration), initiallyExpanded = state.configurationWarning != null) {
            Text(tr(R.string.back_up_and_restore_then_restart_with_a_new_chat), style = MaterialTheme.typography.bodySmall)
            state.configurationWarning?.let { Text(it, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val available = idle && !state.importing && !state.loginPending && state.phase != EnginePhase.Preparing
            OutlinedButton(onClick = { recover = ConfigurationTarget.Defaults }, enabled = available, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.restore_defaults)) }
            OutlinedButton(onClick = { recover = ConfigurationTarget.LastGood }, enabled = available && state.configuration.lastGood, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.restore_last_working_configuration)) }
            OutlinedButton(onClick = { recover = ConfigurationTarget.Previous }, enabled = available && state.configuration.previous, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.restore_previous_configuration)) }
        }
        Panel(tr(R.string.background_activity)) {
            OutlinedButton(onClick = quickChat, Modifier.fillMaxWidth()) { Text(tr(R.string.floating_chat_notification_reply)) }
            Text(if (notificationsAllowed) tr(R.string.notifications_on) else tr(R.string.notifications_off), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(tr(R.string.notification_settings)) }
            Text(if (batteryExempt) tr(R.string.unrestricted_background_activity) else tr(R.string.battery_optimization_may_limit_background_activity), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text(tr(R.string.battery_settings)) }
        }
        ExpandablePanel(tr(R.string.about), "Codex Go ${BuildConfig.VERSION_NAME}") { Text(tr(R.string.codex_0_165_0_arg_android_11_community_client_not_affiliated_with , BuildConfig.RUNTIME_ABI), style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(tr(R.string.stop_the_local_environment)) }, text = { Text(tr(R.string.the_current_task_will_be_interrupted_files_and_chat_history_will)) }, confirmButton = { TextButton(onClick = { confirmStop = false; engine.stopEngine() }) { Text(tr(R.string.stop)) } }, dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(tr(R.string.cancel)) } })
    if (confirmRepair) AlertDialog(onDismissRequest = { confirmRepair = false }, title = { Text(tr(R.string.repair_the_bundled_environment)) }, text = { Text(tr(R.string.stop_the_engine_reinstall_the_bundled_linux_environment_then_rest)) }, confirmButton = { TextButton(onClick = { confirmRepair = false; engine.repairEngine() }) { Text(tr(R.string.keep_data_repair)) } }, dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text(tr(R.string.cancel)) } })
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text(tr(R.string.sign_out_of_chatgpt_a36805)) }, text = { Text(tr(R.string.remove_sign_in_credentials_from_this_phone_chats_drafts_and_files)) }, confirmButton = { TextButton(onClick = { confirmLogout = false; engine.logout() }) { Text(tr(R.string.sign_out)) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(tr(R.string.cancel)) } })
    recover?.let { target -> AlertDialog(onDismissRequest = { recover = null }, title = { Text(when (target) { ConfigurationTarget.Defaults -> tr(R.string.restore_default_configuration); ConfigurationTarget.LastGood -> tr(R.string.restore_the_last_working_configuration); ConfigurationTarget.Previous -> tr(R.string.restore_the_previous_configuration) }) }, text = { Text(tr(R.string.back_up_the_current_account_configuration_replace_it_and_restart)) }, confirmButton = { TextButton(onClick = { recover = null; engine.recoverConfiguration(target) }) { Text(tr(R.string.back_up_restore)) } }, dismissButton = { TextButton(onClick = { recover = null }) { Text(tr(R.string.cancel)) } }) }
    if (logs) AlertDialog(onDismissRequest = { logs = false }, title = { Text(tr(R.string.logs)) }, text = { Text(state.logs.joinToString("\n\n").ifBlank { tr(R.string.no_logs_yet) }, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { logs = false }) { Text(tr(R.string.close)) } })
}

@Composable fun DeviceScreen(
    state: EngineState, allow: (Boolean) -> Unit, allowShell: (Boolean) -> Unit,
    pair: () -> Unit, reconnect: () -> Unit, connectPort: (Int) -> Unit,
    observe: suspend () -> org.json.JSONObject, selectShell: (ShellBackend) -> Unit,
    connectShell: (ShellBackend) -> Unit, testShell: suspend () -> org.json.JSONObject, pairingOverlay: (Boolean) -> Unit, cancelPairing: () -> Unit,
    setPreRead: (Boolean) -> Unit, testPreRead: suspend () -> org.json.JSONObject,
    setModelPreRead: (Boolean) -> Unit, selectPreReadModel: (String) -> Unit,
    suggestion: (String) -> Unit,
) {
    val accessibility by PhoneAccessService.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = state.adbConnected || accessibility
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingHelp by remember { mutableStateOf(false) }
    var manualPort by remember { mutableStateOf(false) }
    var readingPreview by remember { mutableStateOf<ReadingPreview?>(null) }
    var readingBusy by remember { mutableStateOf(false) }
    var preReadModels by remember { mutableStateOf(false) }
    var usePairingOverlay by rememberSaveable { mutableStateOf(context.getSharedPreferences("codex_go", 0).getBoolean("pairingOverlay", true)) }
    fun openWireless() {
        pairingOverlay(usePairingOverlay && Settings.canDrawOverlays(context))
        pair()
        runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openWireless() else error = tr(R.string.pairing_through_notifications_needs_notification_permission_allow)
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openWireless()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Panel(tr(R.string.connection_status)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if (state.phoneEnabled) tr(R.string.phone_control_ready) else if (connected) { if (state.phase == EnginePhase.Ready) tr(R.string.phone_capability_paused) else tr(R.string.phone_connected_waiting_for_the_engine) } else tr(R.string.phone_control_not_connected), fontWeight = FontWeight.Medium) }
                if (connected && state.phase == EnginePhase.Ready) TextButton(onClick = { allow(!state.phoneEnabled) }) { Text(if (state.phoneEnabled) tr(R.string.pause_phone_capability) else tr(R.string.resume_phone_capability)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if (state.shellEnabled) tr(R.string.android_shell_ready) else if (state.shellAccess.connected) { if (state.phase == EnginePhase.Ready) tr(R.string.shell_capability_paused) else tr(R.string.shell_connected_waiting_for_the_engine) } else tr(R.string.android_shell_not_connected), fontWeight = FontWeight.Medium); Text(state.shellAccess.selected.displayLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.shellAccess.connected && state.phase == EnginePhase.Ready) TextButton(onClick = { allowShell(!state.shellEnabled) }) { Text(if (state.shellEnabled) tr(R.string.pause_shell_capability) else tr(R.string.resume_shell_capability)) }
            }
        }
        BentoPair(first = {
        Panel(tr(R.string.accessibility)) {
            Text(if (accessibility) tr(R.string.connected) else tr(R.string.not_enabled), fontWeight = FontWeight.Medium)
            Text(tr(R.string.observe_tap_and_type), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { DeviceSettings.accessibility(context) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = tr(R.string.open_accessibility_settings) }) { Text(tr(R.string.settings)) }
        }
        }, second = {
        Panel(tr(R.string.wireless_debugging)) {
            Text(if (state.adbConnected) tr(R.string.connected) else metadata(state.adbStatus), style = MaterialTheme.typography.bodyMedium)
            Text(tr(R.string.shell_screen_control), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { pairingHelp = true }, Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentDescription = tr(R.string.pair_this_phone) }) { Text(tr(R.string.pair)) }
            Row {
                TextButton(onClick = reconnect, modifier = Modifier.semantics { contentDescription = tr(R.string.already_paired_reconnect) }) { Text(tr(R.string.reconnect)) }
                TextButton(onClick = { manualPort = true }, modifier = Modifier.semantics { contentDescription = tr(R.string.manual_port) }) { Text(tr(R.string.port)) }
            }
            if (state.adbSearching) TextButton(onClick = cancelPairing) { Text(tr(R.string.stop_pairing_discovery)) }
            if (!accessibility) Text(tr(R.string.chinese_input_needs_accessibility), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        })
        ShellAccessPanel(state.shellAccess, selectShell, connectShell, testShell)
        ExpandablePanel(tr(R.string.screen_pre_read_experimental), summary = if (state.phonePreRead) tr(R.string.on) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(tr(R.string.pre_read_first), style = MaterialTheme.typography.titleMedium)
                    Text(tr(R.string.read_controls_first_view_the_image_when_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.phonePreRead, setPreRead, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = tr(R.string.pre_read_first) })
            }
            Text(tr(R.string.applies_to_new_chats), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(tr(R.string.lightweight_model_pre_read), style = MaterialTheme.typography.titleSmall)
                    Text(tr(R.string.extract_facts_only_the_main_model_decides), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.phoneModelPreRead, setModelPreRead, enabled = state.phase == EnginePhase.Ready && state.phonePreRead && !state.busy && !readingBusy && state.models.any { it.id == state.phonePreReadModel && it.supportsImages }, modifier = Modifier.semantics { contentDescription = tr(R.string.lightweight_model_pre_read) })
            }
            OutlinedButton(onClick = { preReadModels = true }, enabled = !state.busy && !readingBusy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = tr(R.string.choose_pre_read_model) }) {
                Text(tr(R.string.pre_read_model) + (state.models.firstOrNull { it.id == state.phonePreReadModel }?.name ?: state.phonePreReadModel))
            }
            Text(tr(R.string.uses_your_current_account_quota_falls_back_to_the_original_image), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.phase != EnginePhase.Ready) Text(tr(R.string.start_local_codex_to_choose_and_enable_a_pre_read_model), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = state.phoneEnabled && !state.busy && !readingBusy, onClick = {
                readingBusy = true
                scope.launch {
                    try {
                        val result = testPreRead()
                        readingPreview = ReadingPreview.from(result)
                    } catch (failure: Exception) { error = failure.message }
                    finally { readingBusy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (readingBusy) tr(R.string.pre_reading) else if (state.phoneModelPreRead) tr(R.string.test_assisted_pre_read) else tr(R.string.test_pre_read)) }
        }
        OutlinedButton(enabled = state.phoneEnabled && !testing, onClick = {
            testing = true
            scope.launch {
                try {
                    val result = observe()
                    val image = result.optJSONArray("contentItems")?.objects()?.firstOrNull { it.optString("type") == "inputImage" }
                    if (image == null) error(result.optJSONArray("contentItems")?.optJSONObject(0)?.optString("text") ?: tr(R.string.no_screenshot_received))
                    val bytes = Base64.decode(image.getString("imageUrl").substringAfter(','), Base64.DEFAULT)
                    preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (failure: Exception) { error = failure.message }
                finally { testing = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (testing) tr(R.string.observing_screen) else tr(R.string.test_screen_observation)) }
        TextButton(onClick = { suggestion(tr(R.string.observe_the_current_phone_screen_and_tell_me_what_you_see_discuss)) }, enabled = state.phoneEnabled, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.try_in_chat)) }
    }
    if (pairingHelp) AlertDialog(onDismissRequest = { pairingHelp = false }, title = { Text(tr(R.string.pair_this_phone)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr(R.string.open_developer_options_wireless_debugging_pair_device_with_pairin))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr(R.string.persistent_pairing_window), style = MaterialTheme.typography.titleSmall); Text(tr(R.string.enter_directly_when_allowed_use_the_notification_if_hidden), style = MaterialTheme.typography.bodySmall) }
            Switch(usePairingOverlay, { usePairingOverlay = it }, modifier = Modifier.semantics { contentDescription = tr(R.string.persistent_pairing_window) })
        }
        if (usePairingOverlay && !Settings.canDrawOverlays(context)) Text(tr(R.string.allow_floating_windows_first_notification_pairing_still_works_wit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }, confirmButton = { TextButton(onClick = {
        pairingHelp = false
        if (usePairingOverlay && !Settings.canDrawOverlays(context)) overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
        else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openWireless()
    }) { Text(tr(R.string.open_pairing)) } }, dismissButton = { TextButton(onClick = { pairingHelp = false }) { Text(tr(R.string.cancel)) } })
    if (manualPort) {
        var port by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { manualPort = false }, title = { Text(tr(R.string.connect_wireless_debugging)) }, text = { Column {
            Text(tr(R.string.enter_the_connection_port_on_the_wireless_debugging_page_not_the), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(port, { port = it }, Modifier.padding(top = 14.dp), label = { Text(tr(R.string.connection_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        } }, confirmButton = { TextButton(enabled = port.toIntOrNull()?.let { it in 1..65535 } == true, onClick = { connectPort(port.toInt()); manualPort = false }) { Text(tr(R.string.connect)) } }, dismissButton = { TextButton(onClick = { manualPort = false }) { Text(tr(R.string.cancel)) } })
    }
    if (preview != null) AlertDialog(onDismissRequest = { preview = null }, title = { Text(tr(R.string.screen_observed)) }, text = { Image(preview!!.asImageBitmap(), tr(R.string.current_screenshot), Modifier.fillMaxWidth().heightIn(max = 440.dp)) }, confirmButton = { TextButton(onClick = { preview = null }) { Text(tr(R.string.done)) } })
    readingPreview?.let { reading -> ReadingResultDialog(reading) { readingPreview = null } }
    if (preReadModels) PreReadModelDialog(state, { selectPreReadModel(it); preReadModels = false }) { preReadModels = false }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text(tr(R.string.not_completed)) }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text(tr(R.string.close)) } })
}

@Composable private fun ReadingResultDialog(reading: ReadingPreview, close: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val report = reading.report
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.pre_read_result)) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (!reading.success) tr(R.string.read_failed) else if (report == null && reading.imageIncluded) tr(R.string.original_image_this_backend_has_no_structural_pre_read) else if (reading.imageIncluded) tr(R.string.structure_original_image) else tr(R.string.structure_first_original_available_on_request), style = MaterialTheme.typography.titleMedium)
            if (reading.success && report != null) {
                val source = if (report.optString("source") == "web_accessibility") tr(R.string.web_accessibility_semantics) else tr(R.string.accessibility_semantics)
                Text(tr(R.string.arg_arg_nodes , source, report.optJSONArray("elements")?.length() ?: 0), style = MaterialTheme.typography.bodyMedium)
                report.optJSONObject("modelPreRead")?.let { helper ->
                    Text(tr(R.string.helper_model_arg , helper.optString("model")), style = MaterialTheme.typography.bodyMedium)
                    val failure = when (helper.optString("failure")) { "timeout" -> tr(R.string.timed_out); "tool_attempt" -> tr(R.string.returned_a_tool_call); "image_too_large" -> tr(R.string.image_exceeds_the_helper_limit); "unavailable" -> tr(R.string.model_or_service_unavailable); else -> tr(R.string.result_failed_format_validation) }
                    Text(if (helper.optString("status") == "succeeded") tr(R.string.pre_read_complete_arg_ms , helper.optLong("elapsedMs")) else tr(R.string.helper_pre_read_failed_original_image_included_arg , failure), style = MaterialTheme.typography.bodySmall)
                    helper.optJSONObject("facts")?.let { facts ->
                        for ((key, title) in listOf("texts" to tr(R.string.recognized_text), "visuals" to tr(R.string.visual_facts), "uncertainties" to tr(R.string.uncertainties))) {
                            val values = facts.optJSONArray(key)
                            if ((values?.length() ?: 0) > 0) {
                                Text(title, fontWeight = FontWeight.Medium)
                                for (i in 0 until values!!.length()) Text(values.optString(i), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                report.optJSONObject("metrics")?.let { metrics ->
                    val imageKiB = (metrics.optLong("originalPngBytes") + 1023) / 1024
                    Text(tr(R.string.capture_pre_read_arg_ms_original_arg_kib , metrics.optLong("totalMs"), imageKiB), style = MaterialTheme.typography.bodySmall)
                    Text(tr(R.string.capture_arg_ms_structure_arg_ms_encode_arg_ms , metrics.optLong("captureMs"), metrics.optLong("structureMs"), metrics.optLong("encodeMs")), style = MaterialTheme.typography.bodySmall)
                    if (metrics.optInt("attempts", 1) > 1) Text(tr(R.string.includes_arg_capture_attempts , metrics.optInt("attempts")), style = MaterialTheme.typography.bodySmall)
                }
                val reasons = report.optJSONArray("imageReasons")
                if ((reasons?.length() ?: 0) > 0) {
                    Text(tr(R.string.why_the_image_was_included), fontWeight = FontWeight.Medium)
                    for (i in 0 until reasons!!.length()) Text("• ${reasons.optString(i)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(tr(R.string.these_are_local_timings_and_sizes_not_model_token_usage_or_saving), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { details = !details }) { Text(if (details) tr(R.string.hide_raw_report) else tr(R.string.view_raw_report)) }
            }
            if (details || report == null || !reading.success) SelectionContainer {
                Text(reading.raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.done)) } })
}

@Composable private fun PreReadModelDialog(state: EngineState, select: (String) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val models = state.models.filter { it.supportsImages && (query.isBlank() || "${it.name} ${it.id}".contains(query, true)) }
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.choose_pre_read_model)) }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {

            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), placeholder = { Text(tr(R.string.search_pre_read_models)) }, singleLine = true)
            if (models.isEmpty()) Text(tr(R.string.no_image_models_available_start_codex_or_check_the_model_list), style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(models, key = Model::id) { model ->
                    Surface(modifier = Modifier.semantics { contentDescription = tr(R.string.use_arg_for_pre_read , model.id) }, shape = RoundedCornerShape(18.dp), color = if (model.id == state.phonePreReadModel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, onClick = { select(model.id) }) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            Text(model.id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.done)) } })
}

@Composable fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accents = LocalBentoColors.current
    val icon = when (title) { tr(R.string.local_engine), "Android Shell" -> Icons.Outlined.Terminal; tr(R.string.chatgpt_account) -> Icons.Outlined.PersonOutline; tr(R.string.repair_diagnostics) -> Icons.Outlined.Build; tr(R.string.restore_configuration) -> Icons.Outlined.Restore; tr(R.string.background_activity) -> Icons.Outlined.NotificationsNone; tr(R.string.accessibility) -> Icons.Outlined.AccessibilityNew; tr(R.string.wireless_debugging) -> Icons.Outlined.DeveloperMode; else -> Icons.Outlined.TouchApp }
    val (background, foreground) = when (title) {
        tr(R.string.local_engine), tr(R.string.connection_status) -> colors.primaryContainer to colors.onPrimaryContainer
        tr(R.string.accessibility), tr(R.string.wireless_debugging) -> colors.tertiaryContainer to colors.onTertiaryContainer
        tr(R.string.chatgpt_account) -> accents.peach to accents.onPeach
        tr(R.string.floating_chat), tr(R.string.notification_reply) -> accents.lavender to accents.onLavender
        else -> colors.surfaceContainerLowest to colors.onSurface
    }
    Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp), color = background, contentColor = foreground) {
        Column(Modifier.fillMaxWidth().padding(if (title in setOf(tr(R.string.accessibility), tr(R.string.wireless_debugging))) 16.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(22.dp)); Text(title, Modifier.weight(1f).padding(start = 10.dp), style = if (title in setOf(tr(R.string.accessibility), tr(R.string.wireless_debugging))) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(16.dp)); content()
        }
    }
}

@Composable fun WorkspaceScreen(root: File, externalRevision: Int = 0, importFiles: () -> Unit = {}, reference: (File) -> Unit = {}) {
    var directory by remember { mutableStateOf(root) }
    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var editing by remember { mutableStateOf<File?>(null) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(directory, revision, externalRevision) {
        loading = true
        runCatching { withContext(Dispatchers.IO) {
            check(directory.canonicalPath == root.canonicalPath || directory.canonicalPath.startsWith(root.canonicalPath + File.separator)) { tr(R.string.this_link_points_outside_the_workspace) }
            directory.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: error(tr(R.string.could_not_read_directory))
        } }.onSuccess { files = it }.onFailure { error = it.message }
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(dev.codex.go.files.Project.guestRoot(root) + directory.path.removePrefix(root.path), Modifier.weight(1f).padding(start = 10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { revision++ }) { Icon(Icons.Outlined.Refresh, tr(R.string.refresh_files), Modifier.size(20.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (directory != root) TextButton(onClick = { directory = directory.parentFile ?: root }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(16.dp)); Text(tr(R.string.up_one_level)) }
            else Text(tr(R.string.items_arg , files.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { TextButton(onClick = importFiles) { Text(tr(R.string.import_files)) }; TextButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(tr(R.string.new_file)) } }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (files.isEmpty() && !loading) Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
            Text(tr(R.string.your_first_creation_belongs_here), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files, key = File::getPath) { file ->
                ListItem(headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(if (file.isDirectory) tr(R.string.folder) else "${(file.length() / 1024.0).let { String.format(java.util.Locale.ROOT, "%.1f", it) }} KB", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable {
                        val canonical = file.canonicalPath
                        if (!canonical.startsWith(root.canonicalPath + File.separator)) error = tr(R.string.this_link_points_outside_the_workspace)
                        else if (file.isDirectory) directory = file else previewPath = dev.codex.go.files.WorkspaceFiles(root).guest(file)
                    })
            }
        }
        Text(tr(R.string.app_private_workspace), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text(tr(R.string.new_file)) }, text = { Column { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(tr(R.string.for_example_idea_md)) }); invalid?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = {
            if (name.isBlank() || name in listOf(".", "..") || name.contains('/') || name.contains('\\') || name.contains('\u0000')) invalid = tr(R.string.enter_a_valid_filename)
            else scope.launch { runCatching { withContext(Dispatchers.IO) { val file = File(directory, name); check(file.createNewFile()) { tr(R.string.a_file_with_this_name_already_exists) }; file } }.onSuccess { creating = false; revision++; editing = it }.onFailure { invalid = it.message } }
        }) { Text(tr(R.string.create)) } }, dismissButton = { TextButton(onClick = { creating = false }) { Text(tr(R.string.cancel)) } })
    }
    editing?.let { file -> FileEditor(file, { editing = null }, { editing = null; revision++ }) }
    previewPath?.let { path -> dev.codex.go.files.WorkspaceFiles(root).resolve(path)?.let { file -> FilePreview(file, root, { previewPath = null }, reference, { previewPath = null; editing = it }) } }
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text(tr(R.string.could_not_open)) }, text = { Text(error.orEmpty()) }, confirmButton = { TextButton(onClick = { error = null }) { Text(tr(R.string.close)) } })
}

@Composable private fun FileEditor(file: File, close: () -> Unit, saved: () -> Unit) {
    var text by remember(file.path) { mutableStateOf("") }
    var original by remember(file.path) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(file) {
        runCatching { withContext(Dispatchers.IO) { check(file.length() <= 256 * 1024) { tr(R.string.this_file_exceeds_256_kb_use_codex_to_process_it) }; file.readText().also { check(!it.contains('\u0000')) { tr(R.string.this_file_is_not_editable_text) } } } }.onSuccess { text = it; original = it; ready = true }.onFailure { error = it.message }
    }
    fun leave() { if (text != original) discard = true else close() }
    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.Outlined.Close, tr(R.string.close_file)) } }, actions = {
            TextButton(enabled = ready && !saving, onClick = {
                saving = true
                scope.launch { runCatching { withContext(Dispatchers.IO) {
                    check(file.readText() == original) { tr(R.string.this_file_changed_elsewhere_close_it_and_open_it_again_before_edi) }
                    val temp = File(file.parentFile, ".${file.name}.saving")
                    temp.writeText(text)
                    java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } }.onSuccess { saved() }.onFailure { error = it.message }; saving = false }
            }) { Text(if (saving) tr(R.string.saving) else tr(R.string.save)) }
        }) }) { padding ->
            Column(Modifier.padding(padding).imePadding().fillMaxSize()) {
                error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
                if (ready) TextField(text, { text = it }, Modifier.fillMaxSize(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp))
                else if (error == null) CircularProgressIndicator(Modifier.padding(24.dp))
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(tr(R.string.discard_unsaved_changes)) }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.discard_changes)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(tr(R.string.keep_editing)) } })
}

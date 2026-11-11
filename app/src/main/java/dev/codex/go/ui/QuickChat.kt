package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codex.go.runtime.*

@Composable fun QuickChatScreen(state: EngineState, engine: EngineService, start: () -> Unit, conversation: () -> Unit) {
    val context = LocalContext.current
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationsAllowed by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        overlayAllowed = Settings.canDrawOverlays(context)
        notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    fun settings(action: String) {
        runCatching { context.startActivity(Intent(action, Uri.parse("package:${context.packageName}"))) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.busy) Text(tr(R.string.new_messages_will_be_queued), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (state.phase != EnginePhase.Ready) Button(onClick = start, enabled = state.phase != EnginePhase.Preparing, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.start_local_codex)) }
        Panel(tr(R.string.floating_chat)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.show_a_bubble_outside_the_app), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.chatOverlayEnabled, onCheckedChange = { enabled -> engine.setChatOverlayEnabled(enabled); if (enabled && !overlayAllowed) settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }, modifier = Modifier.semantics { contentDescription = tr(R.string.floating_chat_bubble) })
            }
            if (!overlayAllowed) OutlinedButton(onClick = { engine.setChatOverlayEnabled(true); settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.allow_floating_windows)) }
        }
        Panel(tr(R.string.notification_reply)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.type_in_notifications), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.quickReplyEnabled, onCheckedChange = engine::setQuickReplyEnabled, modifier = Modifier.semantics { contentDescription = tr(R.string.persistent_notification_reply) })
            }
            if (!notificationsAllowed) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.allow_system_notifications)) }
        }
        if (state.quickFeedback.isNotBlank()) Text(metadata(state.quickFeedback), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (state.quickMessages.isNotEmpty()) Panel(tr(R.string.pending_messages_arg , state.quickMessages.size)) {
            state.quickMessages.forEach { message ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("${metadata(message.source)} · ${message.destination}", style = MaterialTheme.typography.labelMedium)
                Text(message.text, maxLines = 6, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { if (engine.applyQuickMessage(message.id)) conversation() }, enabled = !state.importing && !state.loadingHistory) { Text(tr(R.string.add_to_current_draft)) }
                    TextButton(onClick = { engine.removeQuickMessage(message.id) }) { Text(tr(R.string.remove)) }
                }
            }
        }
    }
}

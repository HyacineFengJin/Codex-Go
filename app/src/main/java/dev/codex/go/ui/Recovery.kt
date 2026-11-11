package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.codex.go.runtime.*

@Composable fun RecoveryPanel(state: EngineState, engine: EngineService, settings: () -> Unit, models: () -> Unit) {
    if (!state.networkAvailable) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(tr(R.string.offline_draft_saved_send_manually_when_connected), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    }
    val failure = state.failure ?: return
    val restored = failure.kind == FailureKind.Engine && state.phase == EnginePhase.Ready
    var details by remember(failure) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (restored) tr(R.string.previous_task_needs_review) else metadata(failure.title), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (restored) tr(R.string.the_local_environment_recovered_review_the_chat_and_files_before) else metadata(failure.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (failure.kind) {
                    FailureKind.Authentication -> TextButton(onClick = settings, enabled = !state.busy) { Text(tr(R.string.go_to_account)) }
                    FailureKind.Model -> TextButton(onClick = models, enabled = !state.busy) { Text(tr(R.string.choose_model)) }
                    FailureKind.Engine, FailureKind.Configuration -> TextButton(onClick = settings, enabled = !state.busy) { Text(tr(R.string.check_local_environment)) }
                    else -> Unit
                }
                if (state.failedSubmission != null && !state.busy && state.phase == EnginePhase.Ready) TextButton(onClick = engine::retrySubmission, enabled = state.networkAvailable) { Text(tr(R.string.retry_last_message)) }
                TextButton(onClick = { details = !details }) { Text(if (details) tr(R.string.hide_details) else tr(R.string.error_details)) }
                if (!state.busy) TextButton(onClick = engine::dismissFailure) { Text(tr(R.string.collapse)) }
            }
            if (details) Text(failure.details, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

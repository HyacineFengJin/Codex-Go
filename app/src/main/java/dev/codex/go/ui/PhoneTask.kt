package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import dev.codex.go.runtime.EngineState

@Composable fun PhoneTaskPanel(state: EngineState, pause: (String) -> Unit, resume: () -> Unit, stop: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (state.phonePaused) tr(R.string.your_action_is_needed) else tr(R.string.using_phone_arg , metadata(state.phoneAction)), style = MaterialTheme.typography.labelLarge)
            state.handoffReason?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp) }
            Row {
                TextButton(onClick = { if (state.phonePaused) resume() else pause(tr(R.string.you_paused_phone_control)) }) { Text(if (state.phonePaused) tr(R.string.resume_phone_control) else tr(R.string.pause_phone_control)) }
                TextButton(onClick = stop) { Text(tr(R.string.stop_task)) }
            }
        }
    }
}

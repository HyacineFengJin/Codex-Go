package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.codex.go.runtime.EngineState

@Composable fun QueuePanel(state: EngineState, withdraw: (String) -> Unit, resume: () -> Unit) {
    if (state.queue.isEmpty()) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp).heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.arg_arg_queued , if (state.queuePaused) tr(R.string.queue_paused) else tr(R.string.send_after_this_turn), state.queue.size), Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.browsing && (state.queuePaused || !state.busy)) TextButton(onClick = resume) { Text(tr(R.string.resume_queue), fontSize = 11.sp) }
            }
            state.queue.forEach { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.draft.text.ifBlank { message.draft.attachments.joinToString { it.name } }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    TextButton(onClick = { withdraw(message.id) }, enabled = !state.browsing) { Text(tr(R.string.withdraw), fontSize = 11.sp) }
                }
            }
        }
    }
}

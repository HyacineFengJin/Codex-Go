package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.codex.go.files.*
import dev.codex.go.runtime.*
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable fun ShareInboxScreen(state: EngineState, engine: EngineService, applied: () -> Unit) {
    val context = LocalContext.current
    val inbox = remember { ShareInbox(context) }
    var reviewing by remember { mutableStateOf<Pair<String, Attachment>?>(null) }
    var discarding by remember { mutableStateOf<String?>(null) }
    val editable = !state.importing && !state.loadingHistory && !state.accountBusy && state.sessionMutation == null && state.phase != EnginePhase.Preparing
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            BentoHeading(tr(R.string.share_inbox), Icons.Outlined.Inbox)
            if (state.busy) Text(tr(R.string.a_task_is_running_add_to_the_current_draft_or_keep_this_share_for), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (state.shares.isEmpty()) item { Text(tr(R.string.share_to_codex_go_from_another_app)) }
        items(state.shares, key = SharedDraft::id) { entry ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppLanguage.locale(androidx.compose.ui.platform.LocalContext.current)).format(Date(entry.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.draft.text.isNotBlank()) SelectionContainer {
                        Text(entry.draft.text, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                    }
                    entry.draft.attachments.forEach { file ->
                        OutlinedButton(onClick = { reviewing = entry.id to file }, modifier = Modifier.fillMaxWidth()) { Text("${if (file.image) tr(R.string.image) else tr(R.string.file)} · ${file.name} · ${file.bytes / 1024} KB") }
                    }
                    val interrupted = inbox.route(entry.id)
                    if (interrupted != null) {
                        Text(tr(R.string.previous_assignment_is_incomplete_arg , state.projects.firstOrNull { it.id == interrupted.second }?.displayName.orEmpty()), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { engine.returnToShareTarget(entry.id) }, enabled = editable) { Text(tr(R.string.return_to_previous_share_target)) }
                    }
                    Text(tr(R.string.target_arg_arg , state.project.displayName, state.displayTitle), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { if (engine.applyShare(entry.id, false)) applied() }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.add_to_current_draft)) }
                    OutlinedButton(onClick = { if (engine.applyShare(entry.id, true)) applied() }, enabled = editable && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.add_to_a_new_chat_draft)) }
                    TextButton(onClick = { discarding = entry.id }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.remove_from_inbox)) }
                }
            }
        }
    }
    reviewing?.let { (id, attachment) -> val materials = inbox.materialsFor(id); materials.resolve(attachment)?.let { file -> FilePreview(file, if (file.path.startsWith(materials.staging.path + File.separator)) materials.staging else materials.workspace, { reviewing = null }, privatePreview = true) } }
    discarding?.let { id -> AlertDialog(onDismissRequest = { discarding = null }, title = { Text(tr(R.string.remove_this_share)) }, text = { Text(tr(R.string.this_share_and_its_staged_files_will_be_removed_files_already_con)) }, confirmButton = { TextButton(onClick = { engine.discardShare(id); discarding = null }) { Text(tr(R.string.remove)) } }, dismissButton = { TextButton(onClick = { discarding = null }) { Text(tr(R.string.cancel)) } }) }
}

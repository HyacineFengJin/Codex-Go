package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codex.go.runtime.Session

@Composable fun SessionActions(session: Session, archived: Boolean, close: () -> Unit, rename: (String, String) -> Unit, archive: (String) -> Unit, restore: (String) -> Unit) {
    var action by remember(session.id) { mutableStateOf("") }
    var name by remember(session.id) { mutableStateOf(session.title) }
    val valid = name.trim().let { it.isNotEmpty() && !it.any(Char::isISOControl) }
    when (action) {
        "rename" -> AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.rename_chat)) }, text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr(R.string.chat_name)) }, supportingText = { Text("${name.trim().length} / 120") }, isError = !valid)
        }, confirmButton = { TextButton(enabled = valid, onClick = { rename(session.id, name); close() }) { Text(tr(R.string.save_name)) } }, dismissButton = { TextButton(onClick = close) { Text(tr(R.string.cancel)) } })
        "archive" -> AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.archive_this_chat)) }, text = { Text(tr(R.string.remove_it_from_recent_chats_restore_it_under_archived_files_and_d)) }, confirmButton = { TextButton(onClick = { archive(session.id); close() }) { Text(tr(R.string.archive)) } }, dismissButton = { TextButton(onClick = close) { Text(tr(R.string.cancel)) } })
        else -> AlertDialog(onDismissRequest = close, title = { Text(session.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (archived) Button(onClick = { restore(session.id) }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.restore_open)) }
                else {
                    OutlinedButton(onClick = { action = "rename" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.rename)) }
                    OutlinedButton(onClick = { action = "archive" }, modifier = Modifier.fillMaxWidth()) { Text(tr(R.string.archive_chat)) }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(tr(R.string.close)) } })
    }
}

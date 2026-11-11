package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.go.files.Project
import dev.codex.go.runtime.*

@Composable fun ProjectsScreen(state: EngineState, engine: EngineService, selected: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    val colors = MaterialTheme.colorScheme
    val browse = (!state.busy || state.runningTask?.threadId != null) && !state.importing && !state.loadingHistory && !state.accountBusy && state.sessionMutation == null && state.phase != EnginePhase.Preparing
    val available = !state.browsing && !state.busy && !state.importing && !state.loadingHistory && !state.accountBusy && !state.loginPending && state.sessionMutation == null && state.phase != EnginePhase.Preparing
    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BentoHeading(tr(R.string.your_projects), Icons.Outlined.Workspaces)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = colors.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1.4f)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr(R.string.current_project), style = MaterialTheme.typography.labelMedium)
                            Text(state.project.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Surface(color = colors.tertiaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(tr(R.string.workspaces), style = MaterialTheme.typography.labelMedium); Text(tr(R.string.arg , state.projects.size), style = MaterialTheme.typography.titleLarge) }
                    }
                }
                if (state.busy) Text(tr(R.string.the_task_in_arg_continues_you_can_browse_other_projects_and_prepa , state.runningTask?.project?.displayName.orEmpty()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Button(onClick = { creating = true }, enabled = available, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.create_project)) }
            }
        }
        items(state.projects, key = Project::id) { project ->
            Surface(color = if (project.id == state.project.id) colors.secondaryContainer else colors.surfaceContainerLowest, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { BentoIcon(Icons.Outlined.FolderOpen, size = 36.dp); if (project.id == state.project.id) Icon(Icons.Outlined.CheckCircle, tr(R.string.current_project), Modifier.size(18.dp), tint = colors.primary) }
                    Text(project.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

                    OutlinedButton(onClick = { if (engine.selectProject(project.id)) selected() }, enabled = browse, modifier = Modifier.fillMaxWidth().semantics { contentDescription = tr(R.string.enter_project_arg , project.displayName) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text(tr(R.string.enter_project), maxLines = 1) }
                    TextButton(onClick = { renaming = project }, enabled = available, modifier = Modifier.semantics { contentDescription = tr(R.string.rename_project_arg , project.displayName) }) { Text(tr(R.string.rename)) }
                }
            }
        }
    }
    if (creating || renaming != null) {
        var name by remember(creating, renaming?.id) { mutableStateOf(renaming?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { creating = false; renaming = null }, title = { Text(if (creating) tr(R.string.create_project) else tr(R.string.rename_project)) }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr(R.string.project_name)) }) }, confirmButton = {
            TextButton(enabled = name.isNotBlank() && available, onClick = {
                val success = if (creating) engine.createProject(name) else engine.renameProject(renaming!!.id, name)
                if (success) { val opened = creating; creating = false; renaming = null; if (opened) selected() }
            }) { Text(if (creating) tr(R.string.create_enter) else tr(R.string.save_project_name)) }
        }, dismissButton = { TextButton(onClick = { creating = false; renaming = null }) { Text(tr(R.string.cancel)) } })
    }
}

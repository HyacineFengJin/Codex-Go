@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codex.go.runtime.*

@Composable fun ModelPicker(state: EngineState, engine: EngineService, start: () -> Unit, close: () -> Unit) {
    val editable = state.phase == EnginePhase.Ready && !state.busy && !state.browsing && !state.loadingHistory && !state.accountBusy
    val model = state.models.firstOrNull { it.id == state.selectedModel }
    var query by remember { mutableStateOf("") }
    val matches = remember(state.models, query) { state.models.filter { query.isBlank() || listOf(it.name, it.id, it.description).any { part -> part.contains(query.trim(), ignoreCase = true) } } }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.93f).navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BentoIcon(Icons.Outlined.Tune)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tr(R.string.choose_model), style = MaterialTheme.typography.titleLarge)
                    Text(if (state.busy) tr(R.string.adjust_after_the_task_ends) else tr(R.string.current_arg , modelName(state)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = engine::refreshAccount, enabled = editable && !state.refreshingAccount && state.networkAvailable) { Icon(Icons.Outlined.Refresh, tr(R.string.refresh_models)) }
            }
            if (state.phase != EnginePhase.Ready) {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(R.string.start_codex_to_choose_a_model).replace("Codex", state.engine.label), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { close(); start() }, enabled = state.phase != EnginePhase.Preparing) { Text(if (state.phase == EnginePhase.Preparing) tr(R.string.preparing) else tr(R.string.agent_start_arg, state.engine.label)) }
                    }
                }
            }
            if (model != null && model.efforts.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr(R.string.reasoning_effort), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                            Text(model.effort(state.selectedEffort)?.let(::effortLabel).orEmpty(), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = state.selectedEffort == null, onClick = { engine.selectEffort(null) }, enabled = editable, label = { Text(tr(R.string.model_default)) })
                            model.efforts.forEach { option -> FilterChip(selected = state.selectedEffort == option.id, onClick = { engine.selectEffort(option.id) }, enabled = editable, label = { Text(effortLabel(option.id)) }) }
                        }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp), placeholder = { Text(tr(R.string.search_model_name_or_id)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            if (state.refreshingAccount) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item { ModelChoice(tr(R.string.engine_default), "", null, state.selectedModel.isEmpty(), editable) { engine.selectModel("") } }
                if (state.models.isEmpty()) item { Text(if (state.phase == EnginePhase.Ready) tr(R.string.no_models_yet_refresh_the_list) else "", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                else if (matches.isEmpty()) item { Text(tr(R.string.no_matching_models), Modifier.padding(16.dp), color = colors.onSurfaceVariant) }
                items(matches, key = Model::id) { option -> ModelChoice(option.name, option.description, option.id, option.id == state.selectedModel, editable) { engine.selectModel(option.id) } }
                if (state.selectedModel.isNotEmpty() && model == null) item { Text(tr(R.string.this_chat_uses_arg_which_is_not_in_the_current_list_you_can_choos , state.selectedModel), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
            Button(onClick = close, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = PaddingValues(16.dp)) { Text(tr(R.string.done)) }
        }
    }
}

@Composable private fun ModelChoice(title: String, detail: String, id: String?, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (id != null && id != title) Text(id, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (selected && detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

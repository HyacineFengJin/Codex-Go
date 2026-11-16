package dev.codex.go.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import androidx.compose.ui.unit.dp
import dev.codex.go.R
import dev.codex.go.i18n.*
import dev.codex.go.runtime.*

@Composable fun EngineSetting(state: EngineState, engine: EngineService) {
    val context = LocalContext.current
    val available = File(context.applicationInfo.nativeLibraryDir, "libguest_agy.so").isFile
    Panel(tr(R.string.agent_engine)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentEngine.entries.forEach { kind ->
                FilterChip(selected = state.engine == kind, onClick = { engine.selectEngine(kind) }, label = { Text(kind.label) },
                    enabled = (kind == AgentEngine.Codex || available) && !state.busy && !state.importing && !state.loadingHistory && !state.accountBusy && !state.loginPending && state.phase != EnginePhase.Preparing)
            }
        }
        if (!available) Text(tr(R.string.agent_runtime_missing), style = MaterialTheme.typography.bodySmall)
        Text(tr(R.string.agent_separate_sessions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

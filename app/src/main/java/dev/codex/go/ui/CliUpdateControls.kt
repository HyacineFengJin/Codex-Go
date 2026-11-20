package dev.codex.go.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.codex.go.R
import dev.codex.go.i18n.tr
import dev.codex.go.runtime.AppUpdates
import dev.codex.go.runtime.EnginePhase
import dev.codex.go.runtime.EngineService
import dev.codex.go.runtime.EngineState
import dev.codex.go.runtime.InstalledCli

@Composable
internal fun CliUpdateControls(state: EngineState, engine: EngineService) {
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    var installedVersion by remember { mutableStateOf<String?>(null) }
    var browserFailed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        installedVersion = InstalledCli.find(context)?.version
        onPauseOrDispose { }
    }
    val label = tr(R.string.use_independent_cli)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            val running = state.runningIndependentCli
            val installed = installedVersion
            val status = when {
                running != null -> tr(R.string.cli_pack_running, running)
                installed != null -> tr(R.string.cli_pack_installed, installed)
                else -> tr(R.string.cli_pack_not_installed)
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (installedVersion != null || state.runningIndependentCli != null) {
            Switch(state.useIndependentCli, engine::setIndependentCli,
                enabled = !state.busy && state.phase != EnginePhase.Preparing,
                modifier = Modifier.semantics { contentDescription = label })
        }
    }
    TextButton(enabled = !state.busy && state.phase != EnginePhase.Preparing, onClick = {
        browserFailed = runCatching { browser.openUri(AppUpdates.RELEASES) }.isFailure
    }) { Text(tr(R.string.download_cli_pack)) }
    if (browserFailed) Text(tr(R.string.app_update_browser_failed), style = MaterialTheme.typography.bodySmall)
}

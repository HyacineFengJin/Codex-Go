package dev.codex.go.ui

import android.content.ActivityNotFoundException
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import dev.codex.go.BuildConfig
import dev.codex.go.R
import dev.codex.go.i18n.tr
import dev.codex.go.runtime.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateControls() {
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    if (BuildConfig.RUNTIME_ABI == "arm64-v8a") {
        TextButton(enabled = !checking, onClick = {
            checking = true
            status = null
            version = null
            scope.launch {
                try {
                    version = AppUpdates.latest(BuildConfig.VERSION_NAME)
                    status = version?.let { tr(R.string.app_update_available, it) } ?: tr(R.string.app_update_current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = tr(R.string.app_update_failed)
                } finally {
                    checking = false
                }
            }
        }) { Text(tr(if (checking) R.string.app_update_checking else R.string.check_for_app_updates)) }
    }
    TextButton(onClick = {
        val target = version?.let { "${AppUpdates.RELEASES}/tag/v$it" } ?: AppUpdates.RELEASES
        try {
            browser.openUri(target)
        } catch (_: IllegalArgumentException) {
            status = tr(R.string.app_update_browser_failed)
        } catch (_: ActivityNotFoundException) {
            status = tr(R.string.app_update_browser_failed)
        }
    }) { Text(tr(R.string.app_update_releases)) }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

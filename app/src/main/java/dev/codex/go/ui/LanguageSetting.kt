package dev.codex.go.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.codex.go.R
import dev.codex.go.i18n.*
import dev.codex.go.runtime.EngineService

@Composable fun LanguageSetting(engine: EngineService) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val selected = when (AppLanguage.selection(context).substringBefore('-')) { "en" -> "en"; "zh" -> "zh-Hans"; else -> "" }
    val options = listOf("" to tr(R.string.follow_system), "en" to tr(R.string.language_english), "zh-Hans" to tr(R.string.language_chinese))
    var choosing by remember { mutableStateOf(false) }
    val colors = LocalBentoColors.current
    Surface(onClick = { choosing = true }, shape = RoundedCornerShape(24.dp), color = colors.lavender, contentColor = colors.onLavender) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Language, null)
            if (configuration.fontScale >= 1.3f) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(R.string.language), style = MaterialTheme.typography.titleMedium)
                Text(options.first { it.first == selected }.second, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(tr(R.string.language), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(options.first { it.first == selected }.second, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(tr(R.string.language_title)) }, text = {
        Column {
            options.forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected == tag, role = Role.RadioButton, onClick = {
                    choosing = false
                    if (selected != tag) { AppLanguage.set(context, tag); engine.refreshLanguage(); AppLanguage.recreateLegacyActivity(context) }
                }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == tag, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(tr(R.string.cancel)) } })
}

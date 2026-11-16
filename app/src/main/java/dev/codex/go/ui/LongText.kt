@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.codex.go.ui

import dev.codex.go.R
import dev.codex.go.i18n.tr
import dev.codex.go.protocol.TextPages
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

/** Long messages keep a small inline preview and an explicit, complete reader. */
@Composable fun ReadableText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
    markdown: Boolean = false, openFile: (String) -> Unit = {}) {
    val long = remember(text) { TextPages.needsReader(text) }
    var reading by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        val shown = if (long) remember(text) { TextPages.preview(text) } else text
        if (markdown) Markdown(shown, openFile) else Text(shown, style = style)
        if (long) TextButton(onClick = { reading = true }) { Text(tr(R.string.read_full_text)) }
    }
    if (reading) TextReader(text) { reading = false }
}

@Composable private fun TextReader(text: String, close: () -> Unit) {
    val pages = remember(text) { TextPages(text) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, pages.size - 1)
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentText by rememberUpdatedState(text)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val content = currentText
            scope.launch {
                try { withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = tr(R.string.export_failed_arg, failure.message.orEmpty()) }
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(modifier = Modifier.imePadding(), topBar = {
            TopAppBar(title = { Text(tr(R.string.full_text)) }, navigationIcon = { TextButton(onClick = close) { Text(tr(R.string.close)) } },
                actions = { TextButton(onClick = { export.launch("message.txt") }) { Text(tr(R.string.export)) } })
        }) { insets ->
            Column(Modifier.padding(insets).fillMaxSize().padding(horizontal = 16.dp)) {
                Row {
                    OutlinedTextField(query, { query = it; found = -1; error = null }, label = { Text(tr(R.string.find_in_text)) },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        found = text.indexOf(query, (found + 1).coerceAtLeast(0), ignoreCase = true).takeIf { it >= 0 }
                            ?: text.indexOf(query, ignoreCase = true)
                        if (found >= 0) { selected = pages.pageForOffset(found); error = null } else error = tr(R.string.text_not_found)
                    }, enabled = query.isNotEmpty()) { Text(tr(R.string.find_next)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                key(page) {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(pages.page(page), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { selected = 0 }, enabled = page > 0) { Icon(Icons.Outlined.FirstPage, tr(R.string.first_page)) }
                    IconButton(onClick = { selected = page - 1 }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, tr(R.string.previous_page)) }
                    Text(tr(R.string.text_page_arg, "${page + 1}", "${pages.size}"), modifier = Modifier.padding(top = 14.dp))
                    IconButton(onClick = { selected = page + 1 }, enabled = page < pages.size - 1) { Icon(Icons.Outlined.ChevronRight, tr(R.string.next_page)) }
                    IconButton(onClick = { selected = pages.size - 1 }, enabled = page < pages.size - 1) { Icon(Icons.Outlined.LastPage, tr(R.string.last_page)) }
                }
            }
        }
    }
}

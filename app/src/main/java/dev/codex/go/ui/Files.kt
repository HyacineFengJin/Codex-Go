@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.codex.go.files.*
import dev.codex.go.protocol.ChatItem
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private fun sizeLabel(bytes: Long) = if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${(bytes / 1024.0).toInt()} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)

@Composable private fun FileThumbnail(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (!mimeType(file).startsWith("image/")) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(if (mimeType(file).startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable fun AttachmentStrip(attachments: List<Attachment>, root: File, remove: (String) -> Unit, preview: (File) -> Unit) {
    if (attachments.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(attachments) { attachment ->
            val file = WorkspaceFiles(root).resolve(attachment.path)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(178.dp)) {
                Row(Modifier.clickable(enabled = file != null) { file?.let(preview) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (file != null) FileThumbnail(file, Modifier.size(36.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 10.dp)) {
                        Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        Text(if (file == null) tr(R.string.file_removed) else sizeLabel(attachment.bytes) + " · " + file.extension.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { remove(attachment.path) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, tr(R.string.remove_attachment_arg , attachment.name), Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable fun Deliverables(item: ChatItem, root: File, revision: Int, open: (File) -> Unit) {
    val files by produceState<List<File>>(emptyList(), item.text, item.files, revision) {
        value = withContext(Dispatchers.IO) {
            val workspace = WorkspaceFiles(root)
            (item.files.mapNotNull(workspace::resolve) + workspace.referenced(item.text)).distinctBy(File::getPath)
        }
    }
    if (files.isEmpty()) return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            Surface(onClick = { open(file) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileThumbnail(file, Modifier.size(46.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(file.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(tr(R.string.arg_tap_to_preview_or_export , sizeLabel(file.length())), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, tr(R.string.open_file_arg , file.name), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable fun FilePreview(file: File, root: File, close: () -> Unit, reference: ((File) -> Unit)? = null, edit: ((File) -> Unit)? = null, privatePreview: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(file.path) { mutableStateOf<String?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val mime = remember(file.path) { mimeType(file) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) {
            saving = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    check(WorkspaceFiles(root).resolve(WorkspaceFiles(root).guest(file)) != null) { tr(R.string.file_removed) }
                    context.contentResolver.openOutputStream(uri, "wt").use { output -> checkNotNull(output); file.inputStream().use { it.copyTo(output) } }
                } }.onFailure { error = tr(R.string.export_failed_arg , it.message) }
                saving = false
            }
        }
    }
    LaunchedEffect(file.path, file.lastModified()) {
        if (!mime.startsWith("image/")) text = withContext(Dispatchers.IO) {
            runCatching { dev.codex.go.files.TextFiles.read(file) }.onFailure {
                if (it is dev.codex.go.files.TextFileMemoryException) error = tr(R.string.text_editor_memory_budget)
            }.getOrNull()
        }
    }
    fun open(share: Boolean) {
        runCatching {
            check(WorkspaceFiles(root).resolve(WorkspaceFiles(root).guest(file)) != null) { tr(R.string.file_removed) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(file.name, uri)
            context.startActivity(Intent.createChooser(intent, if (share) tr(R.string.share_arg , file.name) else tr(R.string.open_arg , file.name)))
        }.onFailure { error = tr(R.string.could_not_open_arg , it.message) }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, tr(R.string.close_preview)) } })
                Text("${sizeLabel(file.length())} · $mime", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp)) {
                    if (mime.startsWith("image/")) FileThumbnail(file, Modifier.fillMaxSize())
                    else if (text != null) androidx.compose.foundation.text.selection.SelectionContainer {
                        ReadableText(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()), style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp))
                    } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(56.dp))
                        Text(tr(R.string.view_this_file_in_another_app), Modifier.padding(top = 16.dp))
                    }
                }
                if (privatePreview) Text(tr(R.string.after_adding_it_to_the_draft_open_or_export_it_from_the_workspace), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
                else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(false) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp)); Text(tr(R.string.open)) }
                    OutlinedButton(onClick = { export.launch(file.name) }, enabled = !saving) { Text(tr(R.string.export)) }
                    OutlinedButton(onClick = { open(true) }) { Text(tr(R.string.share)) }
                    reference?.let { OutlinedButton(onClick = { it(file); close() }) { Text(tr(R.string.add_to_chat)) } }
                    if (text != null) edit?.let { OutlinedButton(onClick = { it(file) }) { Text(tr(R.string.edit)) } }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

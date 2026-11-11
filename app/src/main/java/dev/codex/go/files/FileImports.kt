package dev.codex.go.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun importFiles(context: Context, root: File, uris: List<Uri>): List<Attachment> {
    require(uris.size <= WorkspaceFiles.MAX_FILES) { "每次最多导入 8 个文件" }
    val directory = File(root, "imports/${UUID.randomUUID()}")
    require(directory.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "导入目录不在工作区内" }
    check(directory.mkdirs()) { "无法创建导入目录" }
    try {
        val workspace = WorkspaceFiles(root)
        return uris.mapIndexed { index, uri ->
            var name = "attachment"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (size >= 0 && !cursor.isNull(size)) require(cursor.getLong(size) <= WorkspaceFiles.MAX_FILE_BYTES) { "单个文件最多 64 MB" }
                }
            }
            val folder = File(directory, index.toString()).apply { mkdirs() }
            val file = File(folder, WorkspaceFiles.safeName(name))
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "无法读取选中的文件" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= WorkspaceFiles.MAX_FILE_BYTES) { "单个文件最多 64 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            Attachment(workspace.guest(file), file.name, context.contentResolver.getType(uri) ?: mimeType(file), file.length())
        }
    } catch (error: Throwable) { directory.deleteRecursively(); throw error }
}

fun mimeType(file: File): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: when (file.extension.lowercase()) {
    "md", "kt", "py", "toml", "yaml", "yml", "log", "csv", "sh", "ts", "tsx", "jsx" -> "text/plain"
    else -> "application/octet-stream"
}

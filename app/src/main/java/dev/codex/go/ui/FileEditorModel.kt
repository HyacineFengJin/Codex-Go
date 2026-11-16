package dev.codex.go.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.codex.go.R
import dev.codex.go.files.TextFiles
import dev.codex.go.files.TextFileMemoryException
import dev.codex.go.i18n.tr
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*

/** Keep large editor buffers outside saved-instance Bundles across Activity recreation. */
class FileEditorModel : ViewModel() {
    var text by mutableStateOf("")
    var original by mutableStateOf(""); private set
    var ready by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var path: String? = null
    private var load: Job? = null

    fun open(file: File) {
        if (path == file.path) return
        reset(); path = file.path
        load = viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { TextFiles.read(file) }
                text = content; original = content; ready = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = tr(if (failure is TextFileMemoryException) R.string.text_editor_memory_budget else R.string.this_file_is_not_editable_text) }
        }
    }
    fun reset() {
        load?.cancel(); load = null; path = null
        text = ""; original = ""; ready = false; saving = false; saved = false; error = null
    }
    fun save(file: File) {
        if (!ready || saving || path != file.path) return
        // Capture both values before dispatch: input and lifecycle events cannot change this write.
        val baseline = original
        val candidate = text
        saving = true; error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(TextFiles.read(file) == baseline) { tr(R.string.this_file_changed_elsewhere_close_it_and_open_it_again_before_edi) }
                    val temp = File.createTempFile(".codex-edit-", ".saving", file.parentFile)
                    try {
                        temp.writeText(candidate)
                        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { temp.delete() }
                }
                original = candidate; saved = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { saving = false }
        }
    }
}

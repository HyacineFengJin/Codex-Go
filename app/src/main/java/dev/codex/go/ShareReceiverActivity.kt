package dev.codex.go

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.codex.go.files.*
import dev.codex.go.ui.GoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Keep transient URI grants in the receiving activity until private copies are committed. */
class ShareReceiverActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))
    private val receiver: ShareReceiverModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver.receive(intent)
        setContent { GoTheme {
            val result by receiver.result.collectAsState()
            LaunchedEffect(result) {
                if (result == "saved" && !receiver.delivered) {
                    receiver.delivered = true
                    startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).setAction("OPEN_SHARES").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (result == null || result == "saved") tr(R.string.saving_share) else tr(R.string.could_not_receive_share), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    if (result == null || result == "saved") CircularProgressIndicator()
                    else { Text(result.orEmpty()); TextButton(onClick = { finish() }) { Text(tr(R.string.back)) } }
                    Text(tr(R.string.choose_a_chat_and_edit_after_saving), Modifier.padding(top = 20.dp))
                }
            }
        } }
    }
}

class ShareReceiverModel(application: Application, saved: SavedStateHandle) : AndroidViewModel(application) {
    val result = MutableStateFlow<String?>(null)
    var delivered = false
    private var started = false
    private val batchId = saved.get<String>("batchId") ?: java.util.UUID.randomUUID().toString().also { saved["batchId"] = it }
    fun receive(intent: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) { tr(R.string.unsupported_share_action) }
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                    @Suppress("DEPRECATION")
                    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                        else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    val clip = intent.clipData
                    val uris = (streams + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }).distinct()
                    require(uris.all { it.scheme == "content" }) { tr(R.string.share_files_through_a_system_file_provider) }
                    val context = getApplication<Application>()
                    val inbox = ShareInbox(context)
                    if (inbox.received(batchId)) return@withContext
                    val root = ShareInbox.staging(context)
                    val files = if (uris.isEmpty()) emptyList() else importFiles(context, root, uris)
                    try { inbox.add(SharedDraft(id = batchId, draft = Draft(text, files))) }
                    catch (error: Throwable) {
                        files.mapNotNull { WorkspaceFiles(root).resolve(it.path)?.parentFile?.parentFile }.distinct().forEach { it.deleteRecursively() }
                        throw error
                    }
                }
                result.value = "saved"
            } catch (error: Throwable) { if (error is CancellationException) throw error; result.value = error.message ?: tr(R.string.could_not_read_the_shared_content_try_sharing_again) }
        }
    }
}

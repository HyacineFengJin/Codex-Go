package dev.codex.go.shell

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import dev.codex.go.BuildConfig
import dev.codex.go.adb.WirelessDebug
import dev.codex.go.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Selects one explicit shell provider; accessibility/wireless Computer Use remain independent. */
class AndroidShell(private val context: Context, private val wireless: WirelessDebug, private val changed: (ShellAccess) -> Unit) {
    private val preferences = context.getSharedPreferences("codex_go", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = CommandRunner()
    private val mutex = Mutex()
    private var access = ShellAccess()
    private var remote: ICommandService? = null
    private var shizukuBound = false
    private var shizukuTimeout: Job? = null
    private var connectJob: Job? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuCommandService::class.java))
        .daemon(false).processNameSuffix("android_shell").tag("codex_go_shell").version(BuildConfig.VERSION_CODE)
    private val wired: WiredBridge = WiredBridge(scope) { connected, message ->
        update(ShellBackend.Wired, false, message)
        if (connected) scope.launch {
            try { verify(wired.execute("id", 5000), false); update(ShellBackend.Wired, true, tr(R.string.connected_adb_shell)) }
            catch (error: Exception) { update(ShellBackend.Wired, false, tr(R.string.identity_verification_failed_restart_the_bridge)); wired.close() }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shizukuTimeout?.cancel()
            remote = ICommandService.Stub.asInterface(binder)
            scope.launch {
                try { verify(shizukuExecute("id", 5000), false); update(ShellBackend.Shizuku, true, if (Shizuku.getUid() == 0) tr(R.string.connected_root) else tr(R.string.connected_adb_shell)) }
                catch (error: Exception) { update(ShellBackend.Shizuku, false, tr(R.string.connection_did_not_finish_arg , error.javaClass.simpleName)) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; shizukuBound = false; update(ShellBackend.Shizuku, false, tr(R.string.shizuku_service_disconnected)) }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        update(ShellBackend.Shizuku, false, tr(R.string.shizuku_running))
        if (preferences.getBoolean("autoPrepare", true)) reconnectGranted()
    }
    private val binderDead = Shizuku.OnBinderDeadListener { remote = null; shizukuBound = false; update(ShellBackend.Shizuku, false, tr(R.string.shizuku_stopped)) }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == 902) { if (result == PackageManager.PERMISSION_GRANTED) bindShizuku() else update(ShellBackend.Shizuku, false, tr(R.string.permission_not_granted_change_it_in_shizuku)) }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived); Shizuku.addBinderDeadListener(binderDead); Shizuku.addRequestPermissionResultListener(permission)
        scope.launch {
            while (isActive) {
                delay(20_000)
                if (wired.connected) try { wired.ping() } catch (error: Exception) { wired.close(); update(ShellBackend.Wired, false, tr(R.string.wired_bridge_disconnected)) }
                if (ShellBackend.Shizuku in access.ready && (!Shizuku.pingBinder() || runCatching { Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED }.getOrDefault(true))) {
                    remote = null; update(ShellBackend.Shizuku, false, tr(R.string.shizuku_stopped_or_permission_was_revoked))
                }
            }
        }
    }
    val selected get() = access.selected
    val connected get() = access.connected
    private fun update(backend: ShellBackend, ready: Boolean, message: String, busy: Boolean = false) {
        if (backend == ShellBackend.Root && !busy) preferences.edit().putBoolean("rootConnectedBefore", ready).apply()
        access = access.copy(ready = if (ready) access.ready + backend else access.ready - backend,
            messages = access.messages + (backend to message), connecting = if (busy) access.connecting + backend else access.connecting - backend)
        changed(access)
    }
    fun wirelessChanged(ready: Boolean, message: String) = update(ShellBackend.Wireless, ready, message)
    fun select(backend: ShellBackend) { access = access.copy(selected = backend); changed(access) }
    /** Reuse existing system grants; never open a new Shizuku permission prompt. */
    fun reconnectGranted() {
        if (connected || selected in access.connecting) return
        when (selected) {
            ShellBackend.Shizuku -> if (!shizukuBound && Shizuku.pingBinder() &&
                runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) bindShizuku()
            ShellBackend.Root -> if (preferences.getBoolean("rootConnectedBefore", false)) connect(ShellBackend.Root)
            else -> Unit
        }
    }
    fun connect(backend: ShellBackend) {
        if (backend in access.connecting) return
        when (backend) {
            ShellBackend.Wireless -> Unit
            ShellBackend.Wired -> {
                val command = wired.prepare()
                access = access.copy(wiredCommand = command)
                update(backend, false, tr(R.string.waiting_for_the_computer_start_command_expires_in_5_minutes))
            }
            ShellBackend.Shizuku -> {
                if (!Shizuku.pingBinder()) { update(backend, false, tr(R.string.install_and_start_shizuku_first)); return }
                runCatching {
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizuku()
                    else { update(backend, false, tr(R.string.waiting_for_shizuku_permission), true); Shizuku.requestPermission(902) }
                }.onFailure { update(backend, false, tr(R.string.allow_codex_go_in_shizuku)) }
            }
            ShellBackend.Root -> {
                update(backend, false, tr(R.string.waiting_for_su_manager_approval), true)
                connectJob = scope.launch {
                    try { verify(rootExecute("id", 30_000), true); update(backend, true, tr(R.string.authorized_uid_0)) }
                    catch (error: Exception) { if (error is CancellationException) throw error; update(backend, false, tr(R.string.root_access_not_granted_check_su_and_its_permissions)) }
                }
            }
        }
    }
    private fun bindShizuku() {
        if (shizukuBound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        remote = null; update(ShellBackend.Shizuku, false, tr(R.string.connecting_to_shizuku), true)
        runCatching {
            Shizuku.bindUserService(args, connection); shizukuBound = true
            shizukuTimeout?.cancel(); shizukuTimeout = scope.launch { delay(15_000); if (remote == null) { update(ShellBackend.Shizuku, false, tr(R.string.connection_timed_out_check_shizuku_and_retry)); runCatching { Shizuku.unbindUserService(args, connection, true) }; shizukuBound = false } }
        }.onFailure { shizukuBound = false; update(ShellBackend.Shizuku, false, tr(R.string.shizuku_connection_failed_try_again)) }
    }
    private fun verify(result: String, root: Boolean) {
        val value = JSONObject(result)
        check(value.getInt("exitCode") == 0 && Regex(if (root) "(?:^|\\s)uid=0\\b" else "(?:^|\\s)uid=(?:0|2000)\\b").containsMatchIn(value.getString("stdout"))) { tr(R.string.unexpected_execution_identity) }
    }
    private suspend fun rootExecute(command: String, timeout: Long): String {
        val id = UUID.randomUUID().toString()
        return try { runInterruptible(Dispatchers.IO) { runner.execute(id, command, timeout, true) } }
        finally { runner.cancel(id) }
    }
    private suspend fun shizukuExecute(command: String, timeout: Long): String {
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { tr(R.string.shizuku_permission_expired) }
        val service = remote ?: error(tr(R.string.shizuku_is_not_connected)); val id = UUID.randomUUID().toString()
        return withOptionalTimeout(replyTimeout(timeout)) {
            suspendCancellableCoroutine { continuation ->
                thread(isDaemon = true, name = "codex-shizuku-call") {
                    try { val result = service.execute(id, command, timeout); if (continuation.isActive) continuation.resume(result) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
                continuation.invokeOnCancellation { thread(isDaemon = true) { runCatching { service.cancel(id) } } }
            }
        }
    }
    suspend fun execute(command: String, timeout: Long, expected: ShellBackend): JSONObject = mutex.withLock {
        require(command.isNotBlank())
        require(timeout >= 0)
        check(expected == selected && connected) { tr(R.string.shell_backend_changed_retry_using_the_current_connection) }
        if (selected == ShellBackend.Wireless) return@withLock wireless.executeShell(command, timeout)
        val result = JSONObject(when (selected) {
            ShellBackend.Wired -> wired.execute(command, timeout)
            ShellBackend.Shizuku -> shizukuExecute(command, timeout)
            ShellBackend.Root -> rootExecute(command, timeout)
            else -> error(tr(R.string.unknown_shell_backend))
        })
        val output = tr(R.string.backend_arg_stdout_arg_stderr_arg_exitcode_arg , expected.label, result.optString("stdout"), result.optString("stderr"), result.getInt("exitCode")) +
            (if (result.optBoolean("timedOut")) tr(R.string.command_timed_out) else "") + (if (result.optBoolean("truncated")) tr(R.string.output_truncated_at_the_display_limit) else "")
        obj("success" to (result.getInt("exitCode") == 0), "contentItems" to array(obj("type" to "inputText", "text" to output)))
    }
    fun close() {
        Shizuku.removeBinderReceivedListener(binderReceived); Shizuku.removeBinderDeadListener(binderDead); Shizuku.removeRequestPermissionResultListener(permission)
        if (shizukuBound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        remote = null; wired.close(); runner.close(); scope.cancel()
    }
}

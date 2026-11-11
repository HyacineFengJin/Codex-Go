package dev.codex.go.shell

import dev.codex.go.i18n.*
import dev.codex.go.R

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WiredBridge(private val scope: CoroutineScope, private val changed: (Boolean, String) -> Unit) {
    private var listener: ServerSocket? = null
    private var peer: Socket? = null
    private var output: DataOutputStream? = null
    private var listenerJob: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    val connected get() = output != null && peer?.isClosed == false
    fun prepare(): String {
        close()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).also { listener = it; it.soTimeout = 1000 }
        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (isActive && System.currentTimeMillis() < deadline && peer == null) {
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    try {
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream()); val writer = DataOutputStream(socket.getOutputStream())
                        if (input.readUTF() != token) { socket.close(); continue }
                        synchronized(this@WiredBridge) { peer = socket; output = writer }
                        writer.writeUTF("CODEX_GO_SHELL_V1"); writer.flush(); socket.soTimeout = 135_000
                        withContext(Dispatchers.Main.immediate) { changed(true, tr(R.string.bridge_connected_verifying_identity)) }
                        while (isActive) {
                            val id = input.readUTF(); val length = input.readInt()
                            require(length in 0..400_000)
                            val bytes = ByteArray(length); input.readFully(bytes)
                            pending.remove(id)?.complete(bytes.toString(Charsets.UTF_8))
                        }
                    } finally { socket.close() }
                }
            } catch (error: Exception) {
                if (isActive && listener === server) withContext(Dispatchers.Main.immediate) { changed(false, tr(R.string.wired_bridge_disconnected_start_it_again)) }
            } finally {
                runCatching { server.close() }
                if (listener === server) {
                    output = null; peer = null
                    pending.values.forEach { it.completeExceptionally(IllegalStateException(tr(R.string.wired_bridge_disconnected))) }; pending.clear()
                    if (isActive) withContext(Dispatchers.Main.immediate) { changed(false, tr(R.string.bridge_wait_expired_generate_a_new_start_command)) }
                }
            }
        }
        return "adb shell 'CLASSPATH=\"\$(pm path dev.codex.go | cut -d: -f2)\" app_process /system/bin dev.codex.go.shell.WiredShellDaemon ${server.localPort} $token </dev/null >/dev/null 2>&1 &'"
    }
    private fun write(action: String, id: String, command: String? = null, timeout: Long = 0) = synchronized(this) {
        val writer = output ?: error(tr(R.string.wired_bridge_is_not_connected))
        writer.writeUTF(action); writer.writeUTF(id)
        if (command != null) { writer.writeUTF(command); writer.writeLong(timeout) }
        writer.flush()
    }
    suspend fun execute(command: String, timeout: Long): String {
        val id = UUID.randomUUID().toString(); val reply = CompletableDeferred<String>(); pending[id] = reply
        try {
            withContext(Dispatchers.IO) { write("execute", id, command, timeout) }
            return withTimeout(timeout + 5000) { reply.await() }
        } finally {
            if (pending.remove(id) != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { write("cancel", id) } }
        }
    }
    suspend fun ping() {
        val id = UUID.randomUUID().toString(); val reply = CompletableDeferred<String>(); pending[id] = reply
        try { withContext(Dispatchers.IO) { write("ping", id) }; withTimeout(5000) { reply.await() } }
        finally { pending.remove(id) }
    }
    fun close() {
        listenerJob?.cancel(); listenerJob = null
        runCatching { listener?.close() }; listener = null
        runCatching { peer?.close() }; peer = null; output = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException(tr(R.string.wired_bridge_closed))) }; pending.clear()
    }
}

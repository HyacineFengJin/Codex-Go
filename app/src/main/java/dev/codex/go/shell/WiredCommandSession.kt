package dev.codex.go.shell

import dev.codex.go.protocol.JsonRpcClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import org.json.JSONObject

/** One authenticated connection. Serialize execution, while still reading cancel and ping. */
class WiredCommandSession(private val socket: Socket, private val runner: CommandRunner) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "codex-usb-command").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, FutureTask<Unit>>()

    private fun reply(id: String, result: String = "") = synchronized(output) {
        val bytes = result.toByteArray(Charsets.UTF_8)
        output.writeUTF(id); output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    fun run() {
        try {
            while (!socket.isClosed) {
                val action = input.readUTF(); val id = input.readUTF()
                when (action) {
                    "ping" -> reply(id)
                    "cancel" -> { pending.remove(id)?.cancel(false); runner.cancel(id) }
                    "execute" -> {
                        val size = input.readInt()
                        require(size in 0..JsonRpcClient.MAX_MESSAGE_CHARS)
                        val bytes = ByteArray(size); input.readFully(bytes)
                        val command = bytes.toString(Charsets.UTF_8); val timeout = input.readLong()
                        lateinit var task: FutureTask<Unit>
                        task = FutureTask {
                            try {
                                val result = runCatching { runner.execute(id, command, timeout) }.getOrElse {
                                    JSONObject().put("stdout", "").put("stderr", it.message ?: "Command failed").put("exitCode", 1).toString()
                                }
                                // A new request may arrive as soon as this reply is visible.
                                pending.remove(id, task)
                                if (!socket.isClosed) reply(id, result)
                            } catch (_: Exception) { runCatching { socket.close() } }
                            finally { pending.remove(id, task) }
                        }
                        check(pending.putIfAbsent(id, task) == null) { "Duplicate command ID" }
                        worker.execute(task)
                    }
                    else -> error("Invalid bridge action")
                }
            }
        } finally { close() }
    }
    override fun close() {
        runCatching { socket.close() }
        pending.values.forEach { it.cancel(true) }; pending.clear()
        worker.shutdownNow(); runner.close()
    }
}

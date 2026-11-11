package dev.codex.go.shell

import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Started explicitly through USB adb. Only connects to the app's authenticated loopback listener. */
object WiredShellDaemon {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2 && args[1].matches(Regex("[a-f0-9]{64}")))
        check(Process.myUid() in setOf(0, 2000))
        val runner = CommandRunner()
        val gate = Semaphore(1)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), args[0].toInt()), 5000)
                socket.soTimeout = 90_000
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                output.writeUTF(args[1]); output.flush()
                check(input.readUTF() == "CODEX_GO_SHELL_V1")
                while (true) {
                    val action = input.readUTF(); val id = input.readUTF()
                    when (action) {
                        "ping" -> synchronized(output) { output.writeUTF(id); output.writeInt(0); output.flush() }
                        "cancel" -> runner.cancel(id)
                        "execute" -> {
                            val command = input.readUTF(); val timeout = input.readLong()
                            check(gate.tryAcquire()) { "A command is already running" }
                            thread(isDaemon = true) {
                                try {
                                    val result = runCatching { runner.execute(id, command, timeout) }.getOrElse {
                                        org.json.JSONObject().put("stdout", "").put("stderr", it.message ?: "执行失败").put("exitCode", 1).toString()
                                    }
                                    synchronized(output) { output.writeUTF(id); val bytes = result.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes); output.flush() }
                                } catch (_: Exception) { runCatching { socket.close() } }
                                finally { gate.release() }
                            }
                        }
                        else -> error("Invalid bridge action")
                    }
                }
            }
        } finally { runner.close() }
    }
}

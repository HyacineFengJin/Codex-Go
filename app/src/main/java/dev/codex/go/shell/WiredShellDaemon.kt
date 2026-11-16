package dev.codex.go.shell

import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Started explicitly through USB adb. Only connects to the app's authenticated loopback listener. */
object WiredShellDaemon {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2 && args[1].matches(Regex("[a-f0-9]{64}")))
        check(Process.myUid() in setOf(0, 2000))
        val runner = CommandRunner()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), args[0].toInt()), 5000)
                socket.soTimeout = 90_000
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                output.writeUTF(args[1]); output.flush()
                check(input.readUTF() == "CODEX_GO_SHELL_V2")
                socket.soTimeout = 0
                WiredCommandSession(socket, runner).run()
            }
        } finally { runner.close() }
    }
}

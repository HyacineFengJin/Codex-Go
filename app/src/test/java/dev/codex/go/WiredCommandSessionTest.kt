package dev.codex.go

import dev.codex.go.shell.*
import java.io.*
import java.net.*
import java.nio.file.Files
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WiredCommandSessionTest {
    private class Client(val socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        fun action(action: String, id: String) { output.writeUTF(action); output.writeUTF(id); output.flush() }
        fun execute(id: String, command: String) {
            output.writeUTF("execute"); output.writeUTF(id)
            val bytes = command.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size); output.write(bytes); output.writeLong(0); output.flush()
        }
        fun reply(): Pair<String, String> {
            val id = input.readUTF(); val size = input.readInt(); val bytes = ByteArray(size); input.readFully(bytes)
            return id to bytes.toString(Charsets.UTF_8)
        }
    }
    private fun session(block: (Client) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                socket.soTimeout = 4000
                val server = WiredCommandSession(listener.accept(), CommandRunner("/bin/sh"))
                val worker = thread(isDaemon = true) { runCatching { server.run() } }
                try { block(Client(socket)) }
                finally { server.close(); worker.join(2000); assertFalse("Bridge reader did not stop", worker.isAlive) }
            }
        }
    }
    private fun awaitFile(file: File) {
        val end = System.nanoTime() + 3_000_000_000
        while (!file.isFile && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("Command did not start", file.isFile)
    }
    @Test fun longUtf8CommandAndRapidRequestsKeepTheConnection() = session { client ->
        client.execute("unicode", "printf '你好' #" + "界".repeat(22000))
        assertEquals("你好", JSONObject(client.reply().second).getString("stdout"))
        repeat(40) { client.execute("$it", "printf '$it'") }
        repeat(40) { val (id, value) = client.reply(); assertEquals("$it", id); assertEquals(id, JSONObject(value).getString("stdout")) }
        client.action("ping", "alive"); assertEquals("alive" to "", client.reply())
    }
    @Test fun cancelThenRetryAndCancelQueuedCommandNeverTearDownTheBridge() {
        val directory = Files.createTempDirectory("usb-cancel").toFile()
        try { session { client ->
            val started = File(directory, "started"); val forbidden = File(directory, "must-not-run")
            client.execute("old", "printf started > '$started'; exec sleep 30")
            awaitFile(started)
            client.execute("queued", "printf BAD > '$forbidden'")
            client.action("cancel", "queued"); client.action("cancel", "old")
            client.execute("retry", "printf recovered")
            var recovered = false
            repeat(2) {
                val (id, value) = client.reply()
                if (id == "retry") { assertEquals("recovered", JSONObject(value).getString("stdout")); recovered = true }
                else { assertEquals("old", id); assertNotEquals(0, JSONObject(value).getInt("exitCode")) }
            }
            assertTrue(recovered); assertFalse(forbidden.exists())
            client.action("ping", "still-alive"); assertEquals("still-alive" to "", client.reply())
        } } finally { directory.deleteRecursively() }
    }
    @Test fun malformedLengthClosesOnlyThisSession() = session { client ->
        client.output.writeUTF("execute"); client.output.writeUTF("bad"); client.output.writeInt(-1); client.output.flush()
        assertEquals(-1, client.socket.getInputStream().read())
    }
}

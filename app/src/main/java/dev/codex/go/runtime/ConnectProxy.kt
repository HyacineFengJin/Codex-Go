package dev.codex.go.runtime

import java.io.Closeable
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** The guest delegates DNS and socket creation to Android; encrypted TLS bytes pass through. */
class ConnectProxy : Closeable {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(12)
    @Volatile private var closed = false
    val url = "http://codex:$secret@127.0.0.1:${listener.localPort}"
    init {
        workers.execute {
            while (!closed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                connections.add(client)
                workers.execute { tunnel(client) }
            }
        }
    }
    private fun tunnel(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 10_000
            val header = StringBuilder()
            val input = client.getInputStream()
            while (!header.endsWith("\r\n\r\n") && header.length < 16_384) {
                val byte = input.read(); if (byte == -1) throw IOException("Incomplete header")
                header.append(byte.toChar())
            }
            require(header.endsWith("\r\n\r\n"))
            val lines = header.toString().split("\r\n")
            val auth = lines.drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", true) }?.substringAfter(':')?.trim().orEmpty()
            val expected = "Basic " + Base64.getEncoder().encodeToString("codex:$secret".toByteArray())
            if (!MessageDigest.isEqual(auth.toByteArray(), expected.toByteArray())) { respond(client, 407, "Authentication Required"); return }
            val parts = lines.first().split(' ')
            require(parts.size == 3 && parts[0] == "CONNECT")
            val host = parts[1].substringBeforeLast(':')
            val port = parts[1].substringAfterLast(':').toInt()
            require(host.matches(Regex("[a-zA-Z0-9.-]+")) && port == 443)
            upstream = Socket().apply { connect(InetSocketAddress(host, port), 15_000); soTimeout = 180_000 }
            connections.add(upstream)
            client.soTimeout = 180_000
            respond(client, 200, "Connection Established")
            val target = upstream
            val upload = workers.submit { relay(client, target) }
            relay(target, client)
            client.close(); target.close(); upload.cancel(true)
        } catch (_: Exception) { runCatching { respond(client, 502, "Bad Gateway") } }
        finally { connections.remove(client); runCatching { client.close() }; upstream?.let { connections.remove(it); runCatching { it.close() } }; slots.release() }
    }
    private fun respond(socket: Socket, code: Int, text: String) { socket.getOutputStream().write("HTTP/1.1 $code $text\r\n\r\n".toByteArray()); socket.getOutputStream().flush() }
    private fun relay(from: Socket, to: Socket) {
        runCatching { from.getInputStream().copyTo(to.getOutputStream(), 32_768); to.shutdownOutput() }
    }
    override fun close() {
        closed = true; listener.close(); connections.forEach { runCatching { it.close() } }; connections.clear(); workers.shutdownNow()
    }
}

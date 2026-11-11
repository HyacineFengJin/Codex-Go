package dev.codex.go.history

import dev.codex.go.protocol.array
import dev.codex.go.protocol.obj
import dev.codex.go.protocol.stringOrNull
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.zip.GZIPInputStream

/** Authenticated loopback Responses adapter. TLS is created normally by Android, with no MITM CA. */
class HistoryGateway(private val images: HistoryImages, private val report: (HistoryImageStats) -> Unit = {}, private val beforeRead: (String) -> Unit = {}) : Closeable {
    data class Route(val prefix: String, val upstream: URI, @Volatile var thread: String? = null) {
        @Volatile var ready: Boolean = true
        @Volatile var turnToken: String? = null
        lateinit var baseUrl: String
        lateinit var historyUrl: String
        fun configuration(provider: String): JSONObject {
            require(provider !in setOf("ollama", "lmstudio"))
            val config = obj("features.enable_request_compression" to false,
                "mcp_servers.codex_go_history" to obj("command" to "/usr/bin/python3", "args" to array("/codex-go/history_mcp.py"),
                    "env" to obj("CODEX_GO_HISTORY_URL" to historyUrl), "enabled" to true, "startup_timeout_sec" to 10, "tool_timeout_sec" to 30))
            if (provider == "openai") {
                // Built-in provider IDs cannot appear in model_providers. This supported
                // override keeps native OpenAI auth, routing and provider identity intact.
                config.put("openai_base_url", baseUrl)
            } else {
                // RPC overrides split dotted keys literally. Nest custom provider names
                // to preserve names containing dots and deep-merge their auth/settings.
                config.put("model_providers", obj(provider to obj("base_url" to baseUrl, "supports_websockets" to false)))
            }
            return config
        }
    }
    companion object {
        const val MAX_BODY_BYTES = 128 * 1024 * 1024
        private val hopHeaders = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization", "proxy-authenticate", "keep-alive", "upgrade", "te", "trailer", "expect")
        fun upstream(config: JSONObject, chatgpt: Boolean): Pair<String, URI>? {
            val provider = config.stringOrNull("model_provider") ?: "openai"
            if (provider in setOf("amazon-bedrock", "amazon-bedrock-runtime", "gcp", "ollama", "lmstudio")) return null
            val settings = config.optJSONObject("model_providers")?.optJSONObject(provider)
            if (settings?.optJSONObject("aws") != null || (settings?.stringOrNull("wire_api") ?: "responses") != "responses") return null
            val url = settings?.stringOrNull("base_url") ?: if (provider == "openai") {
                config.stringOrNull("openai_base_url") ?: if (chatgpt) "https://chatgpt.com/backend-api/codex" else "https://api.openai.com/v1"
            } else return null
            val uri = runCatching { URI(url.trimEnd('/')) }.getOrNull() ?: return null
            if (uri.host == null || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1"))) return null
            return provider to uri
        }
    }
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val slots = Semaphore(4)
    private val routes = ConcurrentHashMap<String, Route>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false
    init {
        workers.execute {
            while (!closed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                workers.execute { try { serve(socket) } finally { sockets.remove(socket); runCatching { socket.close() }; slots.release() } }
            }
        }
    }
    fun register(upstream: URI, thread: String? = null): Route {
        require(thread == null || ImageArchive.validThread(thread))
        val prefix = "/$secret/r/${UUID.randomUUID()}"
        val local = "http://127.0.0.1:${listener.localPort}$prefix"
        return Route(prefix, upstream, thread).also {
            it.baseUrl = local + upstream.rawPath.orEmpty().trimEnd('/')
            it.historyUrl = "$local/_codex_go_history"
            routes[prefix] = it
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while (out.size() <= max) {
            val b = input.read(); if (b == -1) throw EOFException()
            if (b == 10) return out.toString("ISO-8859-1").removeSuffix("\r")
            out.write(b)
        }
        throw IOException("Header too large")
    }
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transfer = headers["transfer-encoding"]
        require(transfer == null || (transfer.equals("chunked", true) && !headers.containsKey("content-length")))
        val out = ByteArrayOutputStream()
        fun copy(count: Long) {
            require(count in 0..MAX_BODY_BYTES.toLong() && out.size().toLong() + count <= MAX_BODY_BYTES)
            var left = count; val buffer = ByteArray(32_768)
            while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (n < 0) throw EOFException(); out.write(buffer, 0, n); left -= n }
        }
        if (transfer != null) {
            while (true) {
                val size = line(input, 128).substringBefore(';').trim().toLong(16)
                if (size == 0L) { var trailerBytes = 0; while (true) { val trailer = line(input, 8192); trailerBytes += trailer.length; require(trailerBytes < 16_384); if (trailer.isEmpty()) break }; break }
                copy(size); require(line(input, 2).isEmpty())
            }
        } else copy(headers["content-length"]?.toLong() ?: 0)
        val raw = out.toByteArray()
        return when (headers["content-encoding"]?.lowercase()) {
            null, "identity" -> raw
            "gzip" -> GZIPInputStream(ByteArrayInputStream(raw)).use { gzip ->
                val decoded = ByteArrayOutputStream(); val buffer = ByteArray(32_768)
                while (true) { val n = gzip.read(buffer); if (n < 0) break; require(decoded.size().toLong() + n <= MAX_BODY_BYTES); decoded.write(buffer, 0, n) }
                decoded.toByteArray()
            }
            else -> throw IOException("Unsupported content encoding")
        }
    }
    private fun header(output: OutputStream, code: Int, headers: Map<String, String>) {
        val data = buildString {
            append("HTTP/1.1 $code Response\r\nConnection: close\r\n")
            headers.forEach { (key, value) -> if (key.lowercase() !in hopHeaders && !key.contains('\r') && !key.contains('\n') && !value.contains('\r') && !value.contains('\n')) append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(data.toByteArray(Charsets.ISO_8859_1)); output.flush()
    }
    private fun json(output: OutputStream, code: Int, value: JSONObject) {
        header(output, code, mapOf("Content-Type" to "application/json; charset=utf-8"))
        output.write(value.toString().toByteArray()); output.flush()
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream(), 32_768); val output = socket.getOutputStream()
        var responseStarted = false
        try {
            val start = line(input, 8192).split(' ')
            require(start.size == 3 && start[0] in setOf("GET", "POST", "DELETE") && start[2] in setOf("HTTP/1.1", "HTTP/1.0"))
            val uri = URI(start[1]); require(!uri.isAbsolute && uri.rawFragment == null)
            val route = routes.values.firstOrNull { uri.rawPath.startsWith(it.prefix + "/") }
            if (route == null) { json(output, 403, obj("error" to "Forbidden")); return }
            val headers = linkedMapOf<String, String>(); var size = 0
            while (true) {
                val entry = line(input, 8192); if (entry.isEmpty()) break
                size += entry.length; require(size < 32_768 && ':' in entry)
                val key = entry.substringBefore(':').lowercase(); require(key.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(key))
                headers[key] = entry.substringAfter(':').trim()
            }
            if (headers["expect"].equals("100-continue", true)) { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
            val suffix = uri.rawPath.removePrefix(route.prefix)
            if (suffix.startsWith("/_codex_go_history/")) {
                require(start[0] == "GET")
                val thread = route.thread ?: error("会话尚未准备好")
                val query = uri.rawQuery.orEmpty().split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to URLDecoder.decode(p[1], "UTF-8") } }.toMap()
                val result = when (suffix) {
                    "/_codex_go_history/list" -> images.archive.list(thread, query["offset"]?.toIntOrNull() ?: 0)
                    "/_codex_go_history/read" -> {
                        val id = query["id"].orEmpty()
                        require(images.archive.metadata(thread, id) != null)
                        beforeRead(thread)
                        images.readResult(thread, id)
                    }
                    else -> error("未知历史图片操作")
                }
                json(output, 200, result); return
            }
            if (headers["upgrade"].equals("websocket", true)) {
                // The built-in provider's WebSocket capability is immutable. Reject
                // upgrades locally so native Codex falls back to its HTTP transport.
                // Never relay this handshake or its credentials to the upstream.
                json(output, 426, obj("error" to obj("message" to "History images require HTTP Responses", "type" to "unsupported_transport")))
                return
            }
            require(suffix.startsWith(route.upstream.rawPath.orEmpty().trimEnd('/') + "/") && !suffix.contains("..") && !suffix.contains('%'))
            var body = readBody(input, headers)
            if (route.ready && start[0] == "POST" && (suffix.endsWith("/responses") || suffix.endsWith("/responses/compact"))) {
                val thread = route.thread ?: error("会话尚未准备好")
                val request = JSONObject(body.toString(Charsets.UTF_8))
                val stats = images.transform(thread, request, route.turnToken)
                body = request.toString().toByteArray(); report(stats)
            }
            val target = URI(route.upstream.scheme, null, route.upstream.host, route.upstream.port, suffix, null, null).toASCIIString() + (uri.rawQuery?.let { "?$it" } ?: "")
            val connection = URL(target).openConnection() as HttpURLConnection
            connections.add(connection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000; connection.readTimeout = 180_000; connection.requestMethod = start[0]
                headers.forEach { (key, value) -> if (key !in hopHeaders && key !in setOf("content-encoding", "accept-encoding")) connection.setRequestProperty(key, value) }
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (start[0] == "POST") { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
                val status = connection.responseCode
                val returned = connection.headerFields.filterKeys { it != null }.mapValues { (_, values) -> values.joinToString(", ") }
                header(output, status, returned); responseStarted = true
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                stream?.use { source -> val buffer = ByteArray(16_384); while (!closed) { val n = source.read(buffer); if (n < 0) break; output.write(buffer, 0, n); output.flush() } }
            } finally { connections.remove(connection); connection.disconnect() }
        } catch (_: Exception) {
            // Neither request bodies, credentials nor upstream error text are logged or reflected.
            if (!responseStarted) runCatching { json(output, 502, obj("error" to obj("message" to "历史图片通道未完成请求；可在设置中关闭后重试", "type" to "history_image_gateway"))) }
        }
    }
    override fun close() {
        closed = true; listener.close(); connections.forEach { it.disconnect() }; sockets.forEach { runCatching { it.close() } }; routes.clear(); workers.shutdownNow()
    }
}

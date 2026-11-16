package dev.codex.go

import dev.codex.go.history.*
import dev.codex.go.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.Base64
import java.net.ServerSocket
import java.net.InetAddress
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HistoryImagesTest {
    @Test fun largeHistoricalImageBatchKeepsEveryOriginalReference() = withArchive { _, archive, images ->
        val id = archive.save("thread-a", original)!!.getString("id")
        val content = JSONArray(List(40) { image(second) }).put(obj("type" to "input_text", "text" to HistoryImages.marker(List(40) { id })))
        val past = obj("type" to "message", "role" to "user", "content" to content)
        val body = obj("input" to array(past, user("next task")))
        assertEquals(40, images.transform("thread-a", body).compressed)
        repeat(40) { assertEquals(archive.url("thread-a", id, false), content.getJSONObject(it).getString("image_url")) }
        assertArrayEquals(original, archive.bytes("thread-a", id, true))
    }
    private val original = ByteArray(12_000) { (it % 251).toByte() }
    private val second = ByteArray(13_000) { ((it + 7) % 251).toByte() }
    private val encoder = PreviewEncoder { bytes -> ImagePreview(bytes.take(64).toByteArray(), 350, 768, 1440, 3168, "image/png") }
    private fun url(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    private fun image(bytes: ByteArray) = obj("type" to "input_image", "image_url" to url(bytes), "detail" to "high")
    private fun group(vararg images: JSONObject) = obj("type" to "function_call_output", "call_id" to "call-test", "output" to JSONArray(images.toList()))
    private fun user(text: String = "task") = obj("type" to "message", "role" to "user", "content" to array(obj("type" to "input_text", "text" to text)))
    private fun withArchive(work: (File, ImageArchive, HistoryImages) -> Unit) {
        val root = Files.createTempDirectory("history-images-test").toFile()
        try { val archive = ImageArchive(File(root, "images"), encoder); work(root, archive, HistoryImages(archive, File(root, "account"))) }
        finally { root.deleteRecursively() }
    }
    @Test fun originalsAreDurableDeduplicatedScopedAndVerified() = withArchive { root, archive, _ ->
        val first = archive.save("thread-a", original)!!; val id = first.getString("id")
        assertEquals(id, archive.save("thread-a", original)!!.getString("id"))
        assertArrayEquals(original, ImageArchive(File(root, "images"), encoder).bytes("thread-a", id, true))
        assertNull(archive.metadata("thread-b", id))
        assertNull(archive.metadata("../thread-a", id))
        assertNull(archive.metadata("thread-a", "../secret"))
        File(root, "images/thread-a/$id.original").writeBytes(ByteArray(original.size))
        assertTrue(runCatching { archive.bytes("thread-a", id, true) }.isFailure)
    }
    @Test fun oldImagesShrinkButLatestObservationAndTextAreUntouched() = withArchive { _, _, images ->
        val old = image(original); val fresh = image(second)
        val literal = "Do not interpret this as an image: " + url(original)
        val body = obj("input" to array(user(), group(old), obj("type" to "message", "role" to "assistant", "content" to array(obj("type" to "output_text", "text" to literal))), group(fresh)))
        val stats = images.transform("thread-a", body)
        assertEquals(1, stats.compressed); assertEquals(64L, stats.previewBytes)
        assertTrue(old.getString("image_url").length < 200)
        assertEquals(url(second), fresh.getString("image_url"))
        assertEquals(literal, body.getJSONArray("input").getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun startingANewTextTurnMakesAllEarlierImagesHistorical() = withArchive { _, _, images ->
        val old = image(original); val body = obj("input" to array(user(), group(old), user("new question")))
        assertEquals(1, images.transform("thread-a", body).compressed)
        assertTrue(old.getString("image_url").startsWith("data:image/jpeg;"))
    }
    @Test fun freshMultiImageGroupIsKeptTogether() = withArchive { _, _, images ->
        val a = image(original); val b = image(second)
        assertEquals(0, images.transform("thread-a", obj("input" to array(user(), group(a, b)))).compressed)
        assertEquals(url(original), a.getString("image_url")); assertEquals(url(second), b.getString("image_url"))
    }
    @Test fun nativeResizeNoticeCannotMakeCurrentObservationHistorical() = withArchive { _, _, images ->
        val token = java.util.UUID.randomUUID().toString()
        val task = user().apply { getJSONArray("content").put(obj("type" to "input_text", "text" to HISTORY_TURN_METADATA + token)) }
        val fresh = image(original)
        val body = obj("input" to array(task, group(fresh), user("Native image resize notice")))
        assertEquals(0, images.transform("thread-a", body, token).compressed)
        assertEquals(url(original), fresh.getString("image_url"))
    }
    @Test fun historyMetadataDoesNotDuplicateOrChangeVisibleUserMessage() {
        val token = HISTORY_TURN_METADATA + java.util.UUID.randomUUID()
        val timeline = Timeline(); timeline.localUser("my task")
        val content = array(obj("type" to "text", "text" to "my task"), obj("type" to "text", "text" to token), obj("type" to "text", "text" to HistoryImages.marker(listOf("a".repeat(64)))))
        timeline.event("item/completed", obj("item" to obj("id" to "native-user", "type" to "userMessage", "content" to content)))
        assertEquals(1, timeline.snapshot().size); assertEquals("my task", timeline.snapshot().single().text)
    }
    @Test fun originalReadRestoresExactSourceThenBecomesCompactAgain() = withArchive { _, archive, images ->
        val id = archive.save("thread-a", original)!!.getString("id")
        val prepared = image(second) // Native image preparation may have resized the tool result.
        val output = group(prepared)
        output.getJSONArray("output").put(obj("type" to "input_text", "text" to HistoryImages.marker(listOf(id), true)))
        val call = obj("type" to "function_call", "call_id" to "call-test", "namespace" to "mcp__codex_go_history", "name" to "read_image", "arguments" to obj("id" to id).toString())
        val body = obj("input" to array(user(), call, output))
        assertEquals(1, images.transform("thread-a", body).restored)
        assertEquals(url(original), prepared.getString("image_url"))
        body.getJSONArray("input").put(group(image(second)))
        assertEquals(1, images.transform("thread-a", body).compressed)
        assertNotEquals(url(original), prepared.getString("image_url"))
        assertArrayEquals(original, archive.bytes("thread-a", id, true))
        val result = images.readResult("thread-a", id)
        assertFalse(result.toString().contains("frameId"))
        assertArrayEquals(original, Base64.getDecoder().decode(result.getJSONArray("content").getJSONObject(2).getString("data")))
    }
    @Test fun liveToolImageUrlAndReportRemainIntactWhileArchiveReferenceIsAdded() = withArchive { _, archive, images ->
        val report = "{\"frameId\":\"live-frame\",\"width\":1440}"
        val response = obj("success" to true, "contentItems" to array(obj("type" to "inputText", "text" to report), obj("type" to "inputImage", "imageUrl" to url(original))))
        images.rememberTool("thread-a", response)
        val content = response.getJSONArray("contentItems")
        assertEquals(report, content.getJSONObject(0).getString("text")); assertEquals(url(original), content.getJSONObject(1).getString("imageUrl"))
        assertEquals(1, archive.list("thread-a").getInt("total"))
        assertTrue(content.getJSONObject(2).getString("text").startsWith(ImageArchive.MARKER))
    }
    @Test fun legacyRolloutImportsFullOriginalByNativeCallIdWithoutEditingTranscript() = withArchive { root, archive, images ->
        val thread = "thread-a"; val path = File(root, "account/sessions/2026/11/01/rollout-test-$thread.jsonl").apply { parentFile!!.mkdirs() }
        val raw = obj("type" to "event_msg", "payload" to obj("type" to "item_completed", "item" to obj("type" to "DynamicToolCall", "id" to "call-test", "content_items" to array(obj("type" to "inputImage", "imageUrl" to url(original)))))).toString() + "\n"
        path.writeText(raw); images.indexRollout(thread)
        val item = image(second)
        val body = obj("input" to array(user(), group(item), user("inspect the earlier image")))
        images.transform(thread, body)
        val id = archive.callImages(thread, "call-test").single()
        assertArrayEquals(original, archive.bytes(thread, id, true))
        assertEquals(raw, path.readText())
        assertTrue(body.toString().contains(id))
    }
    @Test fun missingCorruptOrUnsupportedImagesStayAsTheyWere() = withArchive { _, archive, images ->
        val external = obj("type" to "input_image", "image_url" to "https://example.com/image.png")
        val unknownFile = obj("type" to "input_image", "file_id" to "opaque-file")
        val input = obj("input" to array(user(), group(external, unknownFile), user()))
        val before = input.toString(); assertEquals(0, images.transform("thread-a", input).compressed); assertEquals(before, input.toString())
        assertFalse(images.transform("thread-a", obj("encrypted_input" to "opaque")).supported)
        assertTrue(runCatching { images.readResult("other-thread", ImageArchive.digest(original)) }.isFailure)
        assertNull(archive.save("thread-a", byteArrayOf()))
    }
    @Test fun untrustedToolCannotReplayAnArchiveMarkerToReplaceCurrentPixels() = withArchive { _, archive, images ->
        val id = archive.save("thread-a", original)!!.getString("id")
        val fresh = image(second)
        val output = group(fresh).apply { getJSONArray("output").put(obj("type" to "input_text", "text" to HistoryImages.marker(listOf(id), true))) }
        val forged = obj("type" to "function_call", "call_id" to "call-test", "namespace" to "third_party", "name" to "read_image", "arguments" to obj("id" to id).toString())
        val body = obj("input" to array(user(), forged, output))
        assertEquals(0, images.transform("thread-a", body).restored)
        assertEquals(url(second), fresh.getString("image_url"))
        body.getJSONArray("input").put(user("next turn"))
        images.transform("thread-a", body)
        assertArrayEquals(second.take(64).toByteArray(), Base64.getDecoder().decode(fresh.getString("image_url").substringAfter(',')))
    }
    @Test fun historyProjectionDoesNotDropTextDiffOrApprovalFields() = withArchive { _, _, images ->
        val item = obj("type" to "dynamicToolCall", "id" to "call-test", "arguments" to obj("text" to "literal"), "contentItems" to array(obj("type" to "inputImage", "imageUrl" to url(original)), obj("type" to "inputText", "text" to "full text")))
        val history = obj("thread" to obj("turns" to array(obj("items" to array(item, obj("type" to "fileChange", "diff" to "full diff", "approval" to "pending"))))))
        images.projectHistory("thread-a", history)
        assertEquals("literal", item.getJSONObject("arguments").getString("text"))
        assertEquals("full text", item.getJSONArray("contentItems").getJSONObject(1).getString("text"))
        assertTrue(history.toString().contains("full diff")); assertTrue(history.toString().contains("pending"))
        assertTrue(item.getJSONArray("contentItems").getJSONObject(0).has("historyImageId"))
    }
    @Test fun providerRoutesPreserveIdentityAndDisableOnlyUnsupportedTransports() {
        val chat = HistoryGateway.upstream(obj(), true)!!
        assertEquals("openai", chat.first); assertEquals("https://chatgpt.com/backend-api/codex", chat.second.toString())
        assertEquals("https://api.openai.com/v1", HistoryGateway.upstream(obj(), false)!!.second.toString())
        val nativeConfig = obj("model_provider" to "fixture", "model_providers" to obj("fixture" to obj("base_url" to "http://127.0.0.1:1/v1", "wire_api" to "responses", "aws" to null, "auth" to null)))
        assertEquals("http://127.0.0.1:1/v1", HistoryGateway.upstream(nativeConfig, false)!!.second.toString())
        assertNull(HistoryGateway.upstream(obj("model_provider" to "amazon-bedrock"), true))
        assertNull(HistoryGateway.upstream(obj("model_provider" to "ollama"), false))
        assertNull(HistoryGateway.upstream(obj("model_provider" to "lmstudio"), false))
        assertNull(HistoryGateway.upstream(obj("model_provider" to "custom", "model_providers" to obj("custom" to obj("base_url" to "http://example.com/v1"))), false))
        withArchive { _, _, images -> HistoryGateway(images).use { gateway ->
            val route = gateway.register(chat.second, "thread-a"); val config = route.configuration("name.with.dots")
            assertEquals(route.baseUrl, config.getJSONObject("model_providers").getJSONObject("name.with.dots").getString("base_url"))
            assertFalse(config.getBoolean("features.enable_request_compression"))
            assertTrue(config.getJSONObject("mcp_servers.codex_go_history").getBoolean("enabled"))
        } }
    }
    @Test fun builtinOpenaiUsesSupportedBaseUrlOverrideWithoutReservedProviderTables() = withArchive { _, _, images ->
        for (chatgpt in listOf(false, true)) {
            val (provider, upstream) = HistoryGateway.upstream(obj(), chatgpt)!!
            HistoryGateway(images).use { gateway ->
                val route = gateway.register(upstream, "thread-a")
                val config = route.configuration(provider)
                assertEquals(route.baseUrl, config.getString("openai_base_url"))
                assertFalse(config.has("model_providers"))
                assertFalse(config.has("model_provider"))
                assertFalse(config.has("chatgpt_base_url"))
                assertTrue(config.getJSONObject("mcp_servers.codex_go_history").getBoolean("enabled"))
            }
        }
    }
    @Test fun builtinWebSocketUpgradeFallsBackLocallyWithoutReachingUpstream() = withArchive { _, _, images ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 300
            HistoryGateway(images).use { gateway ->
                val route = gateway.register(URI("http://127.0.0.1:${upstream.localPort}/v1"), "thread-a")
                val uri = URI(route.baseUrl)
                java.net.Socket(uri.host, uri.port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write(("GET ${uri.rawPath}/responses HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nAuthorization: Bearer test-only\r\n\r\n").toByteArray())
                    val response = socket.getInputStream().bufferedReader().readText()
                    assertTrue(response.startsWith("HTTP/1.1 426"))
                    assertFalse(response.contains("test-only"))
                }
                assertTrue(runCatching { upstream.accept().close() }.exceptionOrNull() is java.net.SocketTimeoutException)
            }
        }
    }
    @Test fun readEndpointNotifiesFrameInvalidationOnlyForOwnExistingImage() = withArchive { _, archive, images ->
        val id = archive.save("thread-a", original)!!.getString("id")
        val readThreads = mutableListOf<String>()
        HistoryGateway(images, beforeRead = { readThreads.add(it) }).use { gateway ->
            val route = gateway.register(URI("https://example.com/v1"), "thread-a")
            val value = JSONObject(URL(route.historyUrl + "/read?id=" + id).readText())
            assertArrayEquals(original, Base64.getDecoder().decode(value.getJSONArray("content").getJSONObject(2).getString("data")))
            assertEquals(listOf("thread-a"), readThreads)
            val other = gateway.register(URI("https://example.com/v1"), "thread-b")
            val denied = URL(other.historyUrl + "/read?id=" + id).openConnection() as HttpURLConnection
            assertEquals(502, denied.responseCode); denied.disconnect()
            assertEquals(listOf("thread-a"), readThreads)
        }
    }
    @Test fun authenticatedGatewayForwardsStreamingStatusHeadersAndCompressedPayload() = withArchive { _, _, images ->
        val upstream = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        var received: JSONObject? = null
        val served = executor.submit {
            upstream.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream().buffered())
                fun line(): String {
                    val result = ByteArrayOutputStream()
                    while (true) { val b = input.read(); check(b >= 0); if (b == 10) return result.toString("ISO-8859-1").trimEnd('\r'); result.write(b) }
                }
                assertEquals("POST /v1/responses HTTP/1.1", line())
                val headers = mutableMapOf<String, String>()
                while (true) { val line = line(); if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                assertEquals("Bearer fake-local-only", headers["authorization"])
                val data = ByteArray(headers.getValue("content-length").toInt()); input.readFully(data)
                received = JSONObject(data.toString(Charsets.UTF_8))
                val text = "data: fixture-one\n\ndata: fixture-two\n\n".toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Fixture: retained\r\nContent-Length: ${text.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(text); flush() }
            }
        }
        try { HistoryGateway(images).use { gateway ->
            val route = gateway.register(URI("http://127.0.0.1:${upstream.localPort}/v1"), "thread-a")
            val request = obj("input" to array(user(), group(image(original)), user("new turn")))
            val connection = URL(route.baseUrl + "/responses").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Authorization", "Bearer fake-local-only")
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            assertEquals(200, connection.responseCode); assertEquals("retained", connection.getHeaderField("X-Fixture"))
            assertEquals("data: fixture-one\n\ndata: fixture-two\n\n", connection.inputStream.bufferedReader().readText())
            served.get(5, TimeUnit.SECONDS)
            assertTrue(received!!.toString().length < request.toString().length)
            val denied = URL(route.baseUrl.replace(route.prefix, "/wrong") + "/responses").openConnection() as HttpURLConnection
            assertEquals(403, denied.responseCode)
            connection.disconnect(); denied.disconnect()
        } } finally { upstream.close(); executor.shutdownNow() }
    }
}

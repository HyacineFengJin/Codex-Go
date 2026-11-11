package dev.codex.go

import dev.codex.go.protocol.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ProtocolTest {
    private class Server : Closeable {
        val serverInput = PipedInputStream(65536)
        val clientOutput = PipedOutputStream(serverInput)
        val clientInput = PipedInputStream(65536)
        val serverOutput = PipedOutputStream(clientInput)
        val reader = serverInput.bufferedReader()
        fun next() = JSONObject(reader.readLine())
        fun send(message: JSONObject) { serverOutput.write((message.toString() + "\n").toByteArray()); serverOutput.flush() }
        override fun close() { serverOutput.close(); clientOutput.close() }
    }
    @Test fun screenshotHistoryLargerThan16MiBPreservesFullPayloadAndConnection() = runBlocking {
        Server().use { server ->
            val disconnected = LinkedBlockingQueue<String>()
            val client = JsonRpcClient(server.clientInput, server.clientOutput, {}, { disconnected.add(it) })
            try {
                val history = async(Dispatchers.IO) { client.call("thread/read", obj("includeTurns" to true)) }
                val request = withContext(Dispatchers.IO) { server.next() }
                val image = "data:image/png;base64," + "A".repeat(18 * 1024 * 1024)
                withContext(Dispatchers.IO) {
                    server.send(obj("id" to request.getLong("id"), "result" to obj("thread" to obj("turns" to array(obj("items" to array(
                        obj("id" to "image-tool", "type" to "dynamicToolCall", "contentItems" to array(obj("type" to "inputImage", "imageUrl" to image))),
                        obj("id" to "reply", "type" to "agentMessage", "text" to "完整历史仍可读取"),
                    )))))))
                }
                val items = history.await().getJSONObject("thread").getJSONArray("turns").getJSONObject(0).getJSONArray("items")
                assertEquals(image, items.getJSONObject(0).getJSONArray("contentItems").getJSONObject(0).getString("imageUrl"))
                assertEquals("完整历史仍可读取", items.getJSONObject(1).getString("text"))
                val next = async(Dispatchers.IO) { client.call("thread/list") }
                val nextRequest = withContext(Dispatchers.IO) { server.next() }
                server.send(obj("id" to nextRequest.getLong("id"), "result" to obj("data" to array())))
                assertEquals(0, next.await().getJSONArray("data").length())
                assertFalse(client.isClosed)
                assertTrue(disconnected.isEmpty())
            } finally { client.close() }
        }
    }
    @Test fun outOfOrderRepliesAndStringApprovalIds() = runBlocking {
        Server().use { server ->
            val events = LinkedBlockingQueue<JSONObject>()
            val client = JsonRpcClient(server.clientInput, server.clientOutput, { events.add(it) }, {})
            val first = async(Dispatchers.IO) { client.call("first") }
            val a = withContext(Dispatchers.IO) { server.next() }
            val second = async(Dispatchers.IO) { client.call("second") }
            val b = withContext(Dispatchers.IO) { server.next() }
            server.send(obj("id" to b.getLong("id"), "result" to obj("value" to 2)))
            server.send(obj("id" to a.getLong("id"), "result" to obj("value" to 1)))
            assertEquals(1, first.await().getInt("value")); assertEquals(2, second.await().getInt("value"))
            server.send(obj("id" to "approval-123", "method" to "item/commandExecution/requestApproval", "params" to obj("command" to "ls")))
            val approval = events.poll(2, TimeUnit.SECONDS)!!
            client.answer(approval.get("id"), obj("decision" to "decline"))
            assertEquals("approval-123", server.next().getString("id"))
            server.close(); client.close()
        }
    }
    @Test fun rpcFailureAndEofCompletePendingCalls() = runBlocking {
        supervisorScope {
            Server().use { server ->
                val client = JsonRpcClient(server.clientInput, server.clientOutput, {}, {})
                val failure = async(Dispatchers.IO) { runCatching { client.call("invalid") }.exceptionOrNull() }
                val request = withContext(Dispatchers.IO) { server.next() }
                server.send(obj("id" to request.getLong("id"), "error" to obj("code" to -32602, "message" to "bad parameters", "data" to obj("codexErrorInfo" to "unauthorized"))))
                val structured = failure.await() as RpcError
                assertEquals(-32602, structured.code)
                assertEquals("unauthorized", structured.data?.getString("codexErrorInfo"))
                val pending = async(Dispatchers.IO) { runCatching { client.call("unanswered") }.exceptionOrNull() }
                withContext(Dispatchers.IO) { server.next() }; server.serverOutput.close()
                assertTrue(withTimeout(2000) { pending.await() } is IOException)
                client.close()
            }
        }
    }
    @Test fun blockedChildInputDoesNotBlockTheCallingThread() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : OutputStream() {
            override fun write(value: Int) { entered.countDown(); release.await() }
        }
        val server = PipedOutputStream()
        val input = PipedInputStream(server)
        val client = JsonRpcClient(input, slow, {}, {})
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            client.notify("first")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.submit { client.notify("second") }.get(300, TimeUnit.MILLISECONDS)
        } finally { release.countDown(); server.close(); client.close(); caller.shutdownNow() }
    }
    @Test fun rpcTimeoutDoesNotCancelTheCallerAndCanBeClassified() = runBlocking {
        Server().use { server ->
            val client = JsonRpcClient(server.clientInput, server.clientOutput, {}, {})
            try {
                val failure = runCatching { client.call("neverAnswers", timeout = 25) }.exceptionOrNull()!!
                assertTrue(failure is TimeoutCancellationException)
                assertTrue(currentCoroutineContext().isActive)
                assertEquals(dev.codex.go.runtime.FailureKind.Network, dev.codex.go.runtime.TaskFailure.from(failure).kind)
            } finally { client.close() }
        }
    }
}

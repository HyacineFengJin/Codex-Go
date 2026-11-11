package dev.codex.go.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** JSONL RPC stays on child-process pipes; the optional history adapter carries model HTTP separately. */
class JsonRpcClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val onMessage: (JSONObject) -> Unit,
    private val onDisconnect: (String) -> Unit,
) : Closeable {
    companion object {
        // Local 0.165.0 needs a full thread/read fallback. A screenshot-heavy history
        // can exceed 16 MiB even when each individual tool result is small.
        const val MAX_MESSAGE_CHARS = 128 * 1024 * 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writer = output.bufferedWriter(Charsets.UTF_8)
    private val outbound = Channel<JSONObject>(32)
    private val counter = AtomicLong()
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    val isClosed: Boolean get() = closed.get()

    init {
        scope.launch {
            try {
                for (message in outbound) {
                    writer.write(message.toString()); writer.newLine(); writer.flush()
                }
            } catch (error: Exception) { disconnect(error.message ?: "无法写入运行服务") }
        }
        scope.launch {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    require(line.length <= MAX_MESSAGE_CHARS) { "运行服务返回的单条消息超过 128 MiB 字符，请先打开新对话" }
                    val message = JSONObject(line)
                    if (message.has("method")) onMessage(message)
                    else pending.remove(message.optLong("id", -1))?.let { waiting ->
                        val error = message.optJSONObject("error")
                        if (error != null) waiting.completeExceptionally(RpcError(error.optInt("code"), error.optString("message"), error.optJSONObject("data")))
                        else waiting.complete(message.optJSONObject("result") ?: JSONObject())
                    }
                }
                disconnect("Codex 运行服务已退出")
            } catch (error: Exception) {
                disconnect(error.message ?: "运行服务连接中断")
            }
        }
    }

    suspend fun call(method: String, params: JSONObject = JSONObject(), timeout: Long = 60_000): JSONObject {
        check(!closed.get()) { "请先启动本机环境" }
        val id = counter.incrementAndGet()
        val waiting = CompletableDeferred<JSONObject>()
        pending[id] = waiting
        try {
            send(obj("id" to id, "method" to method, "params" to params))
            return withTimeout(timeout) { waiting.await() }
        } finally { pending.remove(id) }
    }
    fun notify(method: String) = send(obj("method" to method, "params" to JSONObject()))
    fun answer(id: Any, result: JSONObject) = send(obj("id" to id, "result" to result))
    fun unsupported(id: Any) = send(obj("id" to id, "error" to obj("code" to -32601, "message" to "Client request is not supported")))
    private fun send(message: JSONObject) {
        check(!closed.get()) { "运行服务连接已停止" }
        if (!outbound.trySend(message).isSuccess) throw IOException("运行服务发送队列已满或已断开")
    }
    private fun disconnect(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(IOException(reason)) }
        pending.clear()
        outbound.close()
        onDisconnect(reason)
    }
    override fun close() {
        disconnect("本机环境已停止")
        scope.cancel()
        // Close the underlying streams without waiting for BufferedReader/Writer locks held by I/O.
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

package dev.codex.go.runtime

import dev.codex.go.BuildConfig
import dev.codex.go.phone.*
import dev.codex.go.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Separate short-lived native client: never enters the main conversation or its request handler. */
class CodexPreReader(private val environment: RuntimeEnvironment) {
    suspend fun read(proxy: String, model: Model, mcpNames: List<String>, report: JSONObject, imageUrl: String): PreReadFacts = coroutineScope {
        val resources = this
        var child: Process? = null
        var client: JsonRpcClient? = null
        var drain: Job? = null
        val messages = Channel<JSONObject>(128)
        try {
            withTimeout(PreReadPolicy.TIMEOUT_MS) {
                val process = withContext(Dispatchers.IO) {
                    check(File(environment.root, PreReadPolicy.CWD.removePrefix("/")).let { it.isDirectory || it.mkdirs() }) { "unavailable" }
                    environment.launch(proxy, PreReadPolicy.overrides(mcpNames), PreReadPolicy.CWD).also { child = it }
                }
                child = process
                // The pipe reader belongs to the outer resource scope, not the timed request.
                // Otherwise the request would wait for EOF before its finally block can kill the child.
                drain = resources.launch(Dispatchers.IO) { runCatching { process.errorStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { /* discard private runtime diagnostics */ } } } }
                val rpc = JsonRpcClient(process.inputStream, process.outputStream,
                    { if (!messages.trySend(it).isSuccess) messages.close(IllegalStateException("invalid_output")) },
                    { messages.close(IllegalStateException("unavailable")) })
                client = rpc
                rpc.call("initialize", obj("clientInfo" to obj("name" to "codex_go_preread", "version" to BuildConfig.VERSION_NAME), "capabilities" to obj("experimentalApi" to true)))
                rpc.notify("initialized")
                // Effective configuration is checked before a thread (and its MCP/hook lifecycle) starts.
                val config = rpc.call("config/read", obj("includeLayers" to false)).getJSONObject("config")
                val features = config.optJSONObject("features") ?: error("unavailable")
                check(PreReadPolicy.disabledFeatures.all { !features.optBoolean(it, false) }) { "unavailable" }
                val servers = config.optJSONObject("mcp_servers")
                check(servers == null || servers.keys().asSequence().all { servers.optJSONObject(it)?.optBoolean("enabled", true) == false }) { "unavailable" }
                val started = rpc.call("thread/start", PreReadPolicy.thread(model.id, mcpNames))
                check(started.optString("model") == model.id && started.getJSONObject("thread").optBoolean("ephemeral")) { "unavailable" }
                val id = started.getJSONObject("thread").getString("id")
                val turn = obj("threadId" to id, "input" to PreReadPolicy.input(report, imageUrl), "outputSchema" to PreReadFacts.schema())
                val rank = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                model.efforts.minByOrNull { rank.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }?.let { turn.put("effort", it.id) }
                rpc.call("turn/start", turn)
                val text = StringBuilder()
                var finalText: String? = null
                while (true) {
                    val message = messages.receive()
                    if (message.has("id")) { rpc.unsupported(message.get("id")); error("tool_attempt") }
                    val method = message.optString("method")
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val rawType = params.optJSONObject("item")?.optString("type").orEmpty()
                    if (rawType == "function_call" || rawType == "custom_tool_call" || rawType.endsWith("_tool_call")) error("tool_attempt")
                    if (method == "model/rerouted") error("unavailable")
                    if (params.optString("threadId", id) != id) continue
                    when (method) {
                        "item/agentMessage/delta" -> { text.append(params.optString("delta")); check(text.length <= 12_000) { "invalid_output" } }
                        "item/started", "item/completed" -> {
                            val item = params.optJSONObject("item") ?: continue
                            when (item.optString("type")) {
                                "agentMessage" -> if (method == "item/completed") finalText = item.optString("text").takeIf(String::isNotBlank)
                                "userMessage", "reasoning" -> Unit
                                else -> error("tool_attempt")
                            }
                        }
                        "turn/completed" -> {
                            check(params.optJSONObject("turn")?.optString("status") == "completed") { "unavailable" }
                            return@withTimeout PreReadFacts.parse(finalText ?: text.toString())
                        }
                        "error" -> error("unavailable")
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("unavailable")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                child?.destroy()
                child?.let { if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) } }
                client?.close()
                runCatching { child?.errorStream?.close() }
                drain?.cancelAndJoin(); messages.cancel()
            }
        }
    }
}

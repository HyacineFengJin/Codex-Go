package dev.codex.go.plugins

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import dev.codex.go.protocol.withOptionalTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One explicit binding per request, bounded lifetime and no automatic side-effect retries. */
class PluginBroker(private val context: Context) {
    suspend fun call(plugin: InstalledPlugin, method: String, params: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        withOptionalTimeout(plugin.manifest!!.timeoutMs) {
            val id = UUID.randomUUID().toString()
            val request = JSONObject().put("apiVersion", 1).put("id", id).put("method", method).put("params", params).toString()
            require(request.toByteArray().size <= PluginContract.MAX_REQUEST) { "Plugin request exceeds 64 KiB" }
            var remote: Messenger? = null
            var bound = false
            var completed = false
            lateinit var connection: ServiceConnection
            try {
                suspendCancellableCoroutine<JSONObject> { continuation ->
                    fun fail(error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.what != 2 || message.sendingUid != plugin.uid || !continuation.isActive) return@Handler true
                        try {
                            val raw = message.data.getString("json") ?: error("Missing plugin response")
                            require(raw.toByteArray().size <= PluginContract.MAX_RESPONSE) { "Plugin response exceeds 128 KiB" }
                            val value = JSONObject(raw)
                            if (value.optString("id") != id) return@Handler true
                            require(value.getInt("apiVersion") == PluginContract.API) { "Plugin protocol changed" }
                            if (value.has("error")) error(value.getJSONObject("error").optString("message", "Plugin request failed").take(2000))
                            continuation.resume(value.getJSONObject("result"))
                        } catch (error: Exception) { fail(error) }
                        true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                remote = Messenger(binder)
                                remote!!.send(Message.obtain(null, 1).apply { data = Bundle().apply { putString("json", request) }; replyTo = reply })
                            } catch (error: Exception) { fail(error) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = fail(IllegalStateException("Plugin disconnected"))
                        override fun onNullBinding(name: ComponentName) = fail(IllegalStateException("Plugin returned no service"))
                        override fun onBindingDied(name: ComponentName) = fail(IllegalStateException("Plugin binding died"))
                    }
                    try {
                        bound = context.bindService(Intent(PluginContract.ACTION).setComponent(ComponentName(plugin.packageName, plugin.serviceName)), connection, Context.BIND_AUTO_CREATE)
                        if (!bound) fail(IllegalStateException("Plugin service unavailable"))
                    } catch (error: Exception) { fail(error) }
                }.also { completed = true }
            } finally {
                // Cancellation is advisory: a plugin may have already performed a side effect.
                if (!completed) runCatching { remote?.send(Message.obtain(null, 3).apply { data = Bundle().apply { putString("json", JSONObject().put("apiVersion", 1).put("id", id).toString()) } }) }
                if (bound) runCatching { context.unbindService(connection) }
            }
        }
    }
}

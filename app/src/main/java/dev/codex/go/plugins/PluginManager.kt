package dev.codex.go.plugins

import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import dev.codex.go.protocol.withOptionalTimeout
import java.security.MessageDigest
import java.util.UUID

class PluginManager(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("plugin_approvals", 0)
    private val broker = PluginBroker(context)
    private val updates = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed = updates.asStateFlow()
    private val scanning = MutableStateFlow(false)
    val refreshing = scanning.asStateFlow()
    private val lock = Mutex()
    private val slots = Semaphore(4)
    private var closed = false
    private val calls = mutableMapOf<String, Pair<InstalledPlugin, Job>>()
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refresh() } }
    init {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        refresh()
    }
    private fun scan(): List<InstalledPlugin> {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val services = manager.queryIntentServices(Intent(PluginContract.ACTION), PackageManager.GET_META_DATA)
        return services.map { match ->
            val service = match.serviceInfo
            val key = ComponentName(service.packageName, service.name).flattenToString()
            try {
                require(service.exported && service.enabled && service.applicationInfo.enabled && service.permission.isNullOrBlank()) { "Plugin service must be exported without a binding permission" }
                val packageInfo = manager.getPackageInfo(service.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val resource = service.metaData?.getInt(PluginContract.METADATA) ?: 0
                require(resource != 0) { "Missing plugin manifest resource" }
                val raw = manager.getResourcesForApplication(service.applicationInfo).openRawResource(resource).use { stream ->
                    dev.codex.go.files.TextFiles.read(stream)
                }
                val manifest = PluginManifest.parse(raw)
                val signing = packageInfo.signingInfo ?: error("Missing plugin signing identity")
                val certificates = signing.apkContentsSigners.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(":")
                InstalledPlugin(key, service.packageName, service.name, service.applicationInfo.uid, packageInfo.versionName.orEmpty(), PluginContract.digest("$key\n${packageInfo.firstInstallTime}\n$certificates\n$raw"), manifest)
            } catch (error: Exception) { InstalledPlugin(key, service.packageName, service.name, service.applicationInfo.uid, "", "", null, error = error.message?.take(2000) ?: "Invalid plugin") }
        }.distinctBy { it.key }.sortedBy { it.name.lowercase() }
    }
    fun refresh() { if (closed) return; scope.launch { lock.withLock {
        scanning.value = true
        try {
            val found = withContext(Dispatchers.IO) { scan() }
            updates.value = found.map { it.copy(enabled = it.error == null && preferences.getString(it.key, null) == it.fingerprint) }
            // Revocation is permanent, including for an APK reinstalled later.
            preferences.edit().apply { preferences.all.keys.filter { key -> found.none { it.key == key && it.fingerprint == preferences.getString(key, null) } }.forEach(::remove) }.apply()
            cancelUnavailable()
        } finally { scanning.value = false }
    } } }
    fun setEnabled(key: String, enabled: Boolean) {
        if (closed) return
        val plugin = installed.value.firstOrNull { it.key == key && it.error == null } ?: return
        check(preferences.edit().apply { if (enabled) putString(key, plugin.fingerprint) else remove(key) }.commit()) { "Could not save plugin preference" }
        updates.value = installed.value.map { if (it.key == key) it.copy(enabled = enabled) else it }
        cancelUnavailable()
    }
    private fun cancelUnavailable() { calls.values.toList().filter { (plugin, _) -> installed.value.none { it.key == plugin.key && it.enabled && it.fingerprint == plugin.fingerprint } }.forEach { it.second.cancel(CancellationException("Plugin disabled or changed")) } }
    fun catalog() = PluginContract.catalog(installed.value)
    private fun approved(name: String, expected: String): Pair<InstalledPlugin, PluginTool> {
        for (plugin in installed.value.filter { it.enabled && it.error == null && it.fingerprint == expected }) {
            plugin.manifest?.tools?.firstOrNull { plugin.toolName(it.name) == name }?.let { return plugin to it }
        }
        error("Plugin disabled, changed, removed, or absent from this chat. Enable it and start a new chat.")
    }
    suspend fun invoke(name: String, arguments: JSONObject, expected: String): JSONObject {
        val (plugin, tool) = approved(name, expected)
        return request(plugin, "tools/call", JSONObject().put("name", tool.name).put("arguments", arguments)).let(PluginContract::validateResult)
    }
    suspend fun ping(key: String) {
        val plugin = installed.value.firstOrNull { it.key == key && it.enabled } ?: error("Plugin is disabled")
        // A UI health check is not a model task; a dead plugin must not leave
        // the connection-test button spinning indefinitely.
        withTimeout(5000) {
            check(request(plugin, "ping", JSONObject()).getInt("apiVersion") == PluginContract.API) { "Plugin API mismatch" }
        }
    }
    private suspend fun request(plugin: InstalledPlugin, method: String, params: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        val id = UUID.randomUUID().toString(); val job = currentCoroutineContext().job
        calls[id] = plugin to job
        try { slots.withPermit { withOptionalTimeout(plugin.manifest!!.timeoutMs) {
            check(installed.value.any { it.key == plugin.key && it.enabled && it.fingerprint == plugin.fingerprint }) { "Plugin is disabled" }
            val fresh = withContext(Dispatchers.IO) { scan().firstOrNull { it.key == plugin.key } }
            check(fresh?.fingerprint == plugin.fingerprint && fresh.error == null) { "Plugin package changed" }
            val result = broker.call(plugin, method, params)
            check(installed.value.any { it.key == plugin.key && it.enabled && it.fingerprint == plugin.fingerprint }) { "Plugin changed during call" }
            result
        } } } finally { calls.remove(id) }
    }
    override fun close() { if (closed) return; closed = true; updates.value = emptyList(); context.unregisterReceiver(receiver); calls.values.toList().forEach { it.second.cancel() }; calls.clear(); scope.cancel() }
}

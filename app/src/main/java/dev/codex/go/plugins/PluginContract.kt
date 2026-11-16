package dev.codex.go.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Engine-neutral v1 descriptors. Never load plugin classes into the host. */
data class PluginTool(val name: String, val description: String, val inputSchema: JSONObject)
data class PluginManifest(val name: String, val description: String, val tools: List<PluginTool>, val timeoutMs: Long) {
    companion object {
        fun parse(raw: String): PluginManifest {
            val value = JSONObject(raw)
            require(value.getInt("apiVersion") == PluginContract.API) { "Unsupported plugin API version" }
            val name = value.getString("name"); require(name.isNotBlank()) { "Invalid plugin name" }
            val description = value.optString("description")
            val capabilities = value.getJSONArray("capabilities")
            require(capabilities.length() == 1 && capabilities.getString(0) == "tools") { "Unsupported required capability" }
            val tools = value.getJSONArray("tools"); require(tools.length() > 0) { "Plugins need at least one tool" }
            val parsed = (0 until tools.length()).map { index ->
                val tool = tools.getJSONObject(index); val toolName = tool.getString("name")
                require(Regex("[a-z][a-z0-9_]{0,31}").matches(toolName)) { "Invalid tool name" }
                val help = tool.getString("description"); require(help.isNotBlank())
                val schema = tool.getJSONObject("inputSchema")
                require(schema.optString("type") == "object") { "Tools require an object input schema" }
                PluginTool(toolName, help, schema)
            }
            require(parsed.map { it.name }.distinct().size == parsed.size) { "Duplicate tool names" }
            val timeout = value.optLong("timeoutMs", 0); require(timeout >= 0) { "Timeout cannot be negative" }
            return PluginManifest(name, description, parsed, timeout)
        }
    }
}
data class InstalledPlugin(val key: String, val packageName: String, val serviceName: String, val uid: Int,
    val version: String, val fingerprint: String, val manifest: PluginManifest?, val enabled: Boolean = false, val error: String? = null) {
    val name get() = manifest?.name ?: packageName
    fun toolName(local: String) = "plugin_${PluginContract.digest(key).take(20)}_$local"
}
data class PluginCatalog(val definitions: JSONArray, val approvals: JSONObject)
object PluginContract {
    const val API = 1
    const val ACTION = "dev.codex.go.action.PLUGIN"
    const val METADATA = "dev.codex.go.PLUGIN_MANIFEST"
    // Messenger carries JSON in a UTF-16 Bundle. Keep inline transactions well below
    // Binder's shared 1 MiB buffer; bulk data needs a file/URI transport in a future API.
    const val MAX_REQUEST = 65_536
    const val MAX_RESPONSE = 131_072
    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun catalog(plugins: List<InstalledPlugin>): PluginCatalog {
        val definitions = JSONArray(); val approvals = JSONObject()
        plugins.filter { it.enabled && it.error == null }.forEach { plugin -> plugin.manifest?.tools?.forEach { tool ->
            val name = plugin.toolName(tool.name)
            check(!approvals.has(name)) { "Plugin tool namespace collision" }
            definitions.put(JSONObject().put("name", name).put("description", "[${plugin.name}] ${tool.description}").put("inputSchema", tool.inputSchema))
            approvals.put(name, plugin.fingerprint)
        } }
        return PluginCatalog(definitions, approvals)
    }
    fun validateResult(result: JSONObject): JSONObject {
        val content = result.getJSONArray("content")
        for (i in 0 until content.length()) {
            val item = content.getJSONObject(i); require(item.getString("type") == "text") { "Unsupported plugin content type" }
            item.getString("text")
        }
        require(result.toString().toByteArray().size <= MAX_RESPONSE) { "Plugin output exceeds 128 KiB" }
        return result
    }
}

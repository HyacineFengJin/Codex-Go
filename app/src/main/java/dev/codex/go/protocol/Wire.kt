package dev.codex.go.protocol

import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
fun array(vararg values: Any?) = JSONArray(values.toList())
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

const val HISTORY_IMAGE_METADATA = "CODEX_GO_HISTORY_IMAGE_V1 "
const val HISTORY_TURN_METADATA = "CODEX_GO_CURRENT_TURN_V1 "
fun isHistoryMetadata(text: String): Boolean {
    if (text.startsWith(HISTORY_TURN_METADATA)) return text.removePrefix(HISTORY_TURN_METADATA).matches(Regex("[a-f0-9-]{36}"))
    if (!text.startsWith(HISTORY_IMAGE_METADATA)) return false
    return runCatching {
        val value = JSONObject(text.removePrefix(HISTORY_IMAGE_METADATA)); val ids = value.getJSONArray("ids")
        value.keys().asSequence().toSet() == setOf("ids", "originalRead") && ids.length() in 1..32 && (0 until ids.length()).all { ids.getString(it).matches(Regex("[a-f0-9]{64}")) }
    }.getOrDefault(false)
}

class RpcError(val code: Int, message: String, val data: JSONObject? = null) : Exception(message)

package dev.codex.go.runtime

import dev.codex.go.protocol.objects
import dev.codex.go.protocol.stringOrNull
import org.json.JSONObject

data class ReasoningOption(val id: String, val description: String)
data class Model(val id: String, val name: String, val description: String = "", val efforts: List<ReasoningOption> = emptyList(), val defaultEffort: String? = null, val isDefault: Boolean = false, val inputModalities: List<String> = listOf("text")) {
    val supportsImages get() = "image" in inputModalities
    fun effort(selected: String?): String? = selected?.takeIf { choice -> efforts.any { it.id == choice } }
        ?: defaultEffort?.takeIf { choice -> efforts.any { it.id == choice } }
    companion object {
        fun fromJson(value: JSONObject) = Model(
            value.stringOrNull("model") ?: value.getString("id"), value.stringOrNull("displayName") ?: value.getString("id"), value.optString("description"),
            value.optJSONArray("supportedReasoningEfforts")?.objects().orEmpty().mapNotNull { option -> option.stringOrNull("reasoningEffort")?.let { ReasoningOption(it, option.optString("description")) } }.distinctBy { it.id },
            value.stringOrNull("defaultReasoningEffort"), value.optBoolean("isDefault"),
            value.optJSONArray("inputModalities")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: listOf("text"),
        )
    }
}

fun reasoningLabel(id: String): String = when (id) {
    "none" -> "关闭"; "minimal" -> "最少"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "很高"; "max" -> "最高"; else -> id
}

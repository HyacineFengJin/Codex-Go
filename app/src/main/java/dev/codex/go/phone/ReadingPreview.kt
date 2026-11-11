package dev.codex.go.phone

import org.json.JSONObject

/** Local presentation of the same facts sent to the agent, without interpreting the page. */
data class ReadingPreview(val success: Boolean, val imageIncluded: Boolean, val raw: String, val report: JSONObject?) {
    companion object {
        fun from(result: JSONObject): ReadingPreview {
            val items = result.optJSONArray("contentItems")
            val texts = mutableListOf<String>()
            var image = false
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items?.optJSONObject(i) ?: continue
                if (item.optString("type") == "inputText") texts += item.optString("text")
                if (item.optString("type") == "inputImage") image = true
            }
            val raw = texts.joinToString("\n")
            val report = raw.lineSequence().filter { it.startsWith("{") }.mapNotNull {
                runCatching { JSONObject(it) }.getOrNull()
            }.firstOrNull { it.optString("interpretation") in setOf("structure_only", "assisted_pre_read") && it.has("frameId") }
            return ReadingPreview(result.optBoolean("success"), image, raw, report)
        }
    }
}

package dev.codex.go.plugins

import org.json.JSONArray
import org.json.JSONObject

/** The Android plugin protocol stays independent of the current engine's result format. */
object CodexPluginAdapter {
    fun toolResult(raw: JSONObject): JSONObject {
        val result = PluginContract.validateResult(raw)
        val content = result.getJSONArray("content")
        val items = JSONArray()
        for (i in 0 until content.length()) items.put(JSONObject().put("type", "inputText").put("text", content.getJSONObject(i).getString("text")))
        return JSONObject().put("success", !result.optBoolean("isError", false)).put("contentItems", items)
    }
}

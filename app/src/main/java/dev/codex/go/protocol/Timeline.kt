package dev.codex.go.protocol

import org.json.JSONObject
import java.util.UUID

/** Item IDs are the identity for both streaming and authoritative completed items. */
data class ChatItem(val id: String, val kind: String, val text: String, val title: String = "", val running: Boolean = false, val failed: Boolean = false, val files: List<String> = emptyList(), val reviewComplete: Boolean = false)

class Timeline {
    private val items = mutableListOf<ChatItem>()
    @Synchronized fun snapshot() = items.toList()
    @Synchronized fun clear() = items.clear()
    @Synchronized fun localUser(text: String, files: List<String> = emptyList()): String {
        val id = "local-${UUID.randomUUID()}"
        items += ChatItem(id, "user", text, files = files)
        return id
    }
    @Synchronized fun removeLocal(id: String) { if (id.startsWith("local-")) items.removeAll { it.id == id } }
    @Synchronized fun notice(text: String) { items += ChatItem(UUID.randomUUID().toString(), "notice", text) }
    @Synchronized fun error(text: String) { items += ChatItem(UUID.randomUUID().toString(), "error", text) }
    @Synchronized fun finish(failed: Boolean = false) { items.indices.forEach { index -> if (items[index].running) items[index] = items[index].copy(running = false, failed = failed || items[index].failed) } }
    @Synchronized fun restore(values: List<JSONObject>) {
        clear(); values.forEach { item(it, true) }
    }
    @Synchronized fun event(method: String, params: JSONObject) {
        when (method) {
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { item(it, method == "item/completed") }
            "item/agentMessage/delta", "item/commandExecution/outputDelta" -> {
                val id = params.optString("itemId")
                val index = items.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val previous = items[index]
                    if (previous.running) items[index] = previous.copy(text = previous.text + params.optString("delta"))
                } else if (method == "item/agentMessage/delta") items += ChatItem(id, "assistant", params.optString("delta"), running = true)
            }
        }
    }
    private fun item(value: JSONObject, completed: Boolean) {
        val id = value.optString("id").ifBlank { return }
        val type = value.optString("type")
        val failed = value.optString("status") in setOf("failed", "declined") || (value.has("success") && !value.optBoolean("success", true)) || (!value.isNull("exitCode") && value.optInt("exitCode") != 0)
        val result = when (type) {
            "userMessage" -> {
                val content = value.optJSONArray("content")?.objects().orEmpty()
                val texts = content.filter { it.optString("type") == "text" }
                val text = (if (texts.size > 1) texts.filterNot { isHistoryMetadata(it.optString("text")) } else texts).joinToString("\n") { it.optString("text") }
                val files = content.filter { it.optString("type") == "localImage" }.map { it.optString("path") }
                val local = items.indexOfFirst { it.id.startsWith("local-") && it.text == text }
                if (local >= 0) { items[local] = ChatItem(id, "user", text, files = files.ifEmpty { items[local].files }); return }
                ChatItem(id, "user", text, files = files)
            }
            "agentMessage" -> ChatItem(id, "assistant", value.stringOrNull("text").orEmpty(), running = !completed)
            "commandExecution" -> ChatItem(id, "tool", (value.optString("command") + "\n" + value.stringOrNull("aggregatedOutput").orEmpty() + if (failed) "\n退出码：${value.optInt("exitCode", -1)}" else ""), if (failed) "命令失败" else "运行命令", !completed, failed)
            "fileChange" -> {
                val changes = value.optJSONArray("changes")?.objects().orEmpty()
                fun kind(change: JSONObject) = change.optJSONObject("kind")?.optString("type") ?: change.optString("kind")
                val diff = changes.joinToString("\n") {
                    val label = when (kind(it)) { "add" -> if (it.stringOrNull("diff") == null && it.has("diff") && !it.isNull("diff")) "新增空文件" else "新增文件"; "delete" -> "删除文件"; "update" -> "修改文件"; else -> "文件改动" }
                    label + " · " + it.optString("path") + "\n" + it.optString("diff") + (it.optJSONObject("kind")?.stringOrNull("move_path")?.let { path -> "\n移动至：$path" } ?: "")
                }
                ChatItem(id, "tool", diff, if (failed) "文件修改失败" else "修改文件", !completed, failed, changes.map { it.optString("path") },
                    reviewComplete = changes.isNotEmpty() && changes.all { !it.stringOrNull("path").isNullOrBlank() && it.has("diff") && !it.isNull("diff") && (it.optString("diff").isNotBlank() || (kind(it) == "add" && it.optString("diff").isEmpty())) })
            }
            "dynamicToolCall", "mcpToolCall" -> ChatItem(id, "tool", (value.optJSONObject("arguments")?.toString(2).orEmpty() + "\n" + value.optJSONArray("contentItems")?.objects()?.filter { it.optString("type") == "inputText" }?.joinToString("\n") { it.optString("text") }.orEmpty()).trim(), (if (failed) "工具失败 · " else "") + value.optString("tool", "使用工具"), !completed, failed)
            "webSearch" -> ChatItem(id, "tool", value.stringOrNull("query").orEmpty(), "搜索网页", !completed)
            "plan" -> ChatItem(id, "assistant", value.stringOrNull("text").orEmpty(), "计划", !completed)
            "reasoning", "contextCompaction" -> return
            else -> ChatItem(id, "tool", "", type, !completed)
        }
        val existing = items.indexOfFirst { it.id == id }
        if (existing >= 0) items[existing] = result else items += result
    }
}

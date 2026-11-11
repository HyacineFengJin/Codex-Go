package dev.codex.go.runtime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.codex.go.files.Draft
import dev.codex.go.files.SharedDraft

data class QuickTarget(val project: String, val thread: String?, val epoch: String, val generation: Int, val revision: Int = 0) {
    fun matches(other: QuickTarget) = project == other.project && epoch == other.epoch && generation == other.generation && revision == other.revision && (thread == other.thread || (thread == null && other.thread != null))
}
data class QuickResult(val consumed: Boolean, val message: String)
enum class QuickAction { Hold, Send, Queue }
fun quickAction(target: QuickTarget?, current: QuickTarget, state: EngineState, unlocked: Boolean, notification: Boolean): QuickAction {
    if (target?.matches(current) != true || !unlocked || (notification && !state.quickReplyEnabled) || state.phase != EnginePhase.Ready || !state.networkAvailable || state.importing || state.loadingHistory || state.accountBusy || state.sessionMutation != null) return QuickAction.Hold
    if (state.busy) return if (state.queue.size < 10) QuickAction.Queue else QuickAction.Hold
    return QuickAction.Send
}
data class QuickMessage(val id: String = UUID.randomUUID().toString(), val text: String, val destination: String, val source: String) {
    fun json() = JSONObject().put("id", id).put("text", text).put("destination", destination).put("source", source)
    companion object {
        const val MAX_TEXT = 128 * 1024
        fun fromJson(value: JSONObject) = QuickMessage(value.getString("id"), value.getString("text"), value.getString("destination"), value.getString("source"))
    }
}

/** Written before submission. Interrupted or rejected inputs require an explicit review, never replay. */
class QuickMessages(private val preferences: SharedPreferences) {
    fun list(): List<QuickMessage> {
        val values = JSONArray(preferences.getString("quickMessages", "[]"))
        return (0 until values.length()).map { QuickMessage.fromJson(values.getJSONObject(it)) }
    }
    fun add(message: QuickMessage) {
        require(message.text.isNotBlank() && message.text.length <= QuickMessage.MAX_TEXT) { "快捷消息应为 1–128K 字符" }
        if (list().none { it.id == message.id }) write(list() + message)
    }
    fun remove(id: String) = write(list().filterNot { it.id == id })
    fun apply(id: String, key: String, existing: Draft): Draft {
        val messages = list()
        val merged = SharedDraft.merge(existing, Draft(messages.first { it.id == id }.text))
        check(preferences.edit().putString(key, merged.json().toString()).putString("quickMessages", JSONArray(messages.filterNot { it.id == id }.map { it.json() }).toString()).commit()) { "快捷消息未能加入草稿，请重试" }
        return merged
    }
    private fun write(messages: List<QuickMessage>) {
        check(preferences.edit().putString("quickMessages", JSONArray(messages.map { it.json() }).toString()).commit()) { "快捷消息未能保存，请保留原文重试" }
    }
}

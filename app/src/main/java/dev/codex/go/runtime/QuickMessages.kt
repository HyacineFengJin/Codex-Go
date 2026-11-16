package dev.codex.go.runtime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.codex.go.files.Draft
import dev.codex.go.files.SharedDraft
import dev.codex.go.files.DraftTransfer

data class QuickTarget(val project: String, val thread: String?, val epoch: String, val generation: Int, val revision: Int = 0) {
    fun matches(other: QuickTarget) = project == other.project && epoch == other.epoch && generation == other.generation && revision == other.revision && (thread == other.thread || (thread == null && other.thread != null))
}
data class QuickResult(val consumed: Boolean, val message: String)
enum class QuickAction { Hold, Send, Queue }
fun quickAction(target: QuickTarget?, current: QuickTarget, state: EngineState, unlocked: Boolean, notification: Boolean): QuickAction {
    if (target?.matches(current) != true || !unlocked || (notification && !state.quickReplyEnabled) || state.phase != EnginePhase.Ready || !state.networkAvailable || state.importing || state.loadingHistory || state.accountBusy || state.sessionMutation != null) return QuickAction.Hold
    if (state.busy) return QuickAction.Queue
    return QuickAction.Send
}
data class QuickMessage(val id: String = UUID.randomUUID().toString(), val text: String, val destination: String, val source: String) {
    fun json() = JSONObject().put("id", id).put("text", text).put("destination", destination).put("source", source)
    companion object {
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
        require(message.text.isNotBlank()) { "请先输入消息" }
        if (list().none { it.id == message.id }) write(list() + message)
    }
    fun remove(id: String) {
        check(preferences.edit().putString("quickMessages", JSONArray(list().filterNot { it.id == id }.map { it.json() }).toString()).remove("quickRoute:$id").commit()) { "快捷消息未能保存，请保留原文重试" }
    }
    fun apply(id: String, key: String, existing: Draft, destination: SharedPreferences = preferences, engine: AgentEngine = AgentEngine.Codex): Draft {
        val messages = list()
        val routeKey = "quickRoute:$id"; val route = "${engine.id}:$key"
        require(preferences.getString(routeKey, route) == route) { "Message was already assigned to another draft. Return to that draft before retrying." }
        check(preferences.edit().putString(routeKey, route).commit()) { "Could not save message destination" }
        return DraftTransfer.apply(preferences, destination, key, "quickApplied:$id", existing,
            incoming = { Draft(messages.first { it.id == id }.text) },
            finishSource = { it.putString("quickMessages", JSONArray(messages.filterNot { it.id == id }.map { it.json() }).toString()).remove(routeKey) })
    }
    private fun write(messages: List<QuickMessage>) {
        check(preferences.edit().putString("quickMessages", JSONArray(messages.map { it.json() }).toString()).commit()) { "快捷消息未能保存，请保留原文重试" }
    }
}

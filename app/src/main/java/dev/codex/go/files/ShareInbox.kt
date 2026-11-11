package dev.codex.go.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SharedDraft(val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis(), val draft: Draft) {
    fun json() = JSONObject().put("id", id).put("createdAt", createdAt).put("draft", draft.json())
    companion object {
        fun fromJson(value: JSONObject) = SharedDraft(value.getString("id"), value.getLong("createdAt"), Draft.fromJson(value.getJSONObject("draft")))
        fun merge(existing: Draft, incoming: Draft): Draft {
            val files = (existing.attachments + incoming.attachments).distinctBy(Attachment::path)
            require(files.size <= WorkspaceFiles.MAX_FILES) { "合并后超过 8 个附件，请先处理当前草稿。分享仍在收件箱。" }
            return Draft(listOf(existing.text, incoming.text).filter(String::isNotBlank).joinToString("\n\n"), files)
        }
    }
}

/** Inbox removal and draft assignment use one durable preferences transaction. */
class ShareInbox(private val context: Context) {
    private val preferences = context.getSharedPreferences("codex_go", Context.MODE_PRIVATE)
    val materials = ShareMaterials(staging(context), java.io.File(context.filesDir, "workspace"))
    fun list(): List<SharedDraft> = synchronized(lock) { decode() }
    fun route(id: String): Pair<String?, String>? {
        val route = preferences.getString("shareRoute:$id", null)?.let(::JSONObject) ?: return null
        val project = ProjectRepository(context).list().first { it.id == route.getString("project") }
        val slot = route.getString("slot")
        return (if (slot == project.slot(null)) null else slot) to project.id
    }
    fun materialsFor(id: String): ShareMaterials {
        val route = preferences.getString("shareRoute:$id", null)?.let(::JSONObject)
        val project = route?.optString("project")?.let { key -> ProjectRepository(context).list().first { it.id == key } } ?: Project.DEFAULT
        return ShareMaterials(staging(context), project.directory(context.filesDir))
    }
    fun received(id: String) = preferences.getStringSet("shareReceived", emptySet())!!.contains(id)
    fun add(entry: SharedDraft) = synchronized(lock) {
        if (received(entry.id)) return@synchronized
        val current = decode()
        require(current.size < MAX_PENDING) { "分享收件箱已满，请先处理已有的 20 条分享" }
        require(!entry.draft.empty) { "分享中没有可接收的文字或文件" }
        val received = preferences.getStringSet("shareReceived", emptySet())!!.toMutableSet().apply { add(entry.id) }
        check(preferences.edit().putString(KEY, encode(current + entry)).putStringSet("shareReceived", received).commit()) { "分享保存失败，请重试" }
    }
    fun discard(id: String) = synchronized(lock) {
        val current = decode()
        check(preferences.edit().putString(KEY, encode(current.filterNot { it.id == id })).remove("shareRoute:$id").commit()) { "无法更新分享收件箱" }
        current.firstOrNull { it.id == id }?.let { materials.discard(it.draft) }
    }
    fun apply(id: String, owner: String?, project: Project = Project.DEFAULT, selectConversation: Boolean = true): Draft = synchronized(lock) {
        val current = decode()
        val entry = current.firstOrNull { it.id == id } ?: error("这条分享已处理")
        val key = "draft:${project.slot(owner)}"
        val routeKey = "shareRoute:$id"
        val route = JSONObject().put("slot", project.slot(owner)).put("project", project.id).toString()
        val previous = preferences.getString(routeKey, null)
        require(previous == null || JSONObject(previous).let { it.optString("slot") == project.slot(owner) && it.optString("project") == project.id }) { "这条分享上次已分配到其他目标，请回到原项目和草稿继续处理" }
        val destination = ShareMaterials(staging(context), project.directory(context.filesDir))
        val existing = Draft.fromJson(JSONObject(preferences.getString(key, "{}")!!))
        val merged = SharedDraft.merge(existing, destination.mapped(entry.draft))
        check(preferences.edit().putString(routeKey, route).commit()) { "无法保存分享目标" }
        destination.publish(entry.draft)
        val editor = preferences.edit().putString(key, merged.json().toString()).putString(KEY, encode(current.filterNot { it.id == id })).remove(routeKey)
        if (selectConversation) {
            editor.putString("project", project.id)
            if (owner == null) editor.remove("thread") else editor.putString("thread", owner)
        }
        check(editor.commit()) { "无法保存草稿，分享仍在收件箱" }
        merged
    }
    private fun decode(): List<SharedDraft> {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        return (0 until array.length()).map { SharedDraft.fromJson(array.getJSONObject(it)) }
    }
    private fun encode(entries: List<SharedDraft>) = JSONArray(entries.map { it.json() }).toString()
    companion object {
        fun staging(context: Context) = java.io.File(context.filesDir, "share-staging").apply { mkdirs() }
        private val lock = Any(); private const val KEY = "shareInbox"; const val MAX_PENDING = 20; const val MAX_TEXT = 128 * 1024
    }
}

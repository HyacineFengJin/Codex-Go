package dev.codex.go.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Stable directories are logical project boundaries, not a security sandbox. */
data class Project(val id: String, val name: String) {
    init { require(id == DEFAULT_ID || UUID_PATTERN.matches(id)) { "项目 ID 无效" }; validateName(name) }
    val cwd get() = if (id == DEFAULT_ID) "/workspace" else "/$DIRECTORY/$id"
    fun slot(threadId: String?) = threadId ?: if (id == DEFAULT_ID) "new" else "new@$id"
    fun directory(files: File): File {
        val root = files.canonicalFile
        val target = if (id == DEFAULT_ID) File(root, "workspace") else File(root, "$DIRECTORY/$id")
        require(target.canonicalFile == target.absoluteFile) { "项目目录指向了其他位置，请先检查工作区" }
        check(target.isDirectory || target.mkdirs()) { "无法创建项目目录" }
        return target
    }
    fun json() = JSONObject().put("id", id).put("name", name)
    companion object {
        const val DEFAULT_ID = "default"
        const val DIRECTORY = "projects"
        val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val DEFAULT = Project(DEFAULT_ID, "默认项目")
        fun validateName(name: String) { require(name.isNotBlank() && name == name.trim() && name.none(Char::isISOControl)) { "项目名称不能为空，也不能包含换行或控制字符" } }
        fun guestRoot(root: File): String = if (root.parentFile?.name == DIRECTORY && UUID_PATTERN.matches(root.name)) "/$DIRECTORY/${root.name}" else "/workspace"
    }
}

/** An import can start while the first native thread is still being created. */
class DraftOwner(val project: Project, var threadId: String?) {
    fun migrate(projectId: String, nativeThreadId: String) {
        if (project.id == projectId && threadId == null) threadId = nativeThreadId
    }
    val slot get() = project.slot(threadId)
}

class ProjectRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("codex_go", Context.MODE_PRIVATE)
    fun list(): List<Project> {
        val array = JSONArray(preferences.getString("projects", "[${Project.DEFAULT.json()}]"))
        val projects = (0 until array.length()).map { array.getJSONObject(it).let { p -> Project(p.getString("id"), p.getString("name")) } }
        require(projects.count { it.id == Project.DEFAULT_ID } == 1 && projects.map(Project::id).distinct().size == projects.size) { "项目列表无效，已保留原记录" }
        return projects
    }
    fun create(name: String): Project {
        val projects = list(); val title = name.trim(); Project.validateName(title)
        require(projects.none { it.name.equals(title, true) }) { "已有同名项目" }
        val project = Project(UUID.randomUUID().toString(), title)
        project.directory(context.filesDir)
        save(projects + project)
        return project
    }
    fun rename(id: String, name: String): Project {
        val title = name.trim(); Project.validateName(title)
        val projects = list(); val project = projects.first { it.id == id }.copy(name = title)
        require(projects.none { it.id != id && it.name.equals(title, true) }) { "已有同名项目" }
        save(projects.map { if (it.id == id) project else it })
        return project
    }
    private fun save(projects: List<Project>) { check(preferences.edit().putString("projects", JSONArray(projects.map(Project::json)).toString()).commit()) { "无法保存项目列表" } }
}

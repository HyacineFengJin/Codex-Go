package dev.codex.go.files

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class Attachment(val path: String, val name: String, val mime: String, val bytes: Long) {
    val image get() = mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    fun json() = JSONObject().put("path", path).put("name", name).put("mime", mime).put("bytes", bytes)
    companion object {
        fun fromJson(value: JSONObject) = Attachment(value.getString("path"), value.getString("name"), value.optString("mime", "application/octet-stream"), value.optLong("bytes"))
    }
}

data class Draft(val text: String = "", val attachments: List<Attachment> = emptyList()) {
    val empty get() = text.isBlank() && attachments.isEmpty()
    fun json() = JSONObject().put("text", text).put("attachments", JSONArray(attachments.map { it.json() }))
    fun message(): String = text.trim() + if (attachments.isEmpty()) "" else "\n\n附加的工作区文件：\n" + attachments.joinToString("\n") { "- [${it.name.replace("[", "_").replace("]", "_").replace('\n', '_')}](${WorkspaceFiles.link(it.path)})" }
    fun input(): JSONArray = JSONArray().apply {
        put(JSONObject().put("type", "text").put("text", message()))
        attachments.filter(Attachment::image).forEach { put(JSONObject().put("type", "localImage").put("path", it.path)) }
    }
    companion object {
        fun fromJson(value: JSONObject) = Draft(value.optString("text"), value.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { Attachment.fromJson(a.getJSONObject(it)) } }.orEmpty())
    }
}

/** All model-supplied paths must remain within the private workspace, including symlinks. */
class WorkspaceFiles(val root: File) {
    val guestRoot = Project.guestRoot(root)
    fun resolve(path: String): File? = runCatching {
        val raw = if (path.startsWith("sandbox:") || path.startsWith("file://")) URLDecoder.decode(path.removePrefix("sandbox:").removePrefix("file://").replace("+", "%2B"), "UTF-8") else path
        require(raw.startsWith(guestRoot + "/"))
        val file = File(root, raw.removePrefix(guestRoot + "/")).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile)
        file
    }.getOrNull()

    fun guest(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(root.canonicalPath + File.separator)) { "文件不在工作区内" }
        return guestRoot + "/" + canonical.relativeTo(root.canonicalFile).invariantSeparatorsPath
    }

    fun referenced(text: String): List<File> = Regex("\\[[^]\\n]*]\\(([^)\\n]+)\\)").findAll(text)
        .mapNotNull { resolve(it.groupValues[1]) }.distinctBy(File::getPath).toList()

    companion object {
        fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "_").take(160).trim().takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
        fun link(guest: String) = "sandbox:" + guest.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}

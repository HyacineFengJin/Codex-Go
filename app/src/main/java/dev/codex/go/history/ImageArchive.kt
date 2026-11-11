package dev.codex.go.history

import dev.codex.go.protocol.obj
import dev.codex.go.protocol.HISTORY_IMAGE_METADATA
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

data class ImagePreview(val bytes: ByteArray, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int, val mime: String)
fun interface PreviewEncoder { fun encode(bytes: ByteArray): ImagePreview? }

/** A separate, durable image cache. Native transcripts and workspace originals are never edited. */
class ImageArchive(private val root: File, private val encoder: PreviewEncoder) {
    companion object {
        const val MARKER = HISTORY_IMAGE_METADATA
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun validThread(id: String) = id.matches(Regex("[a-zA-Z0-9_-]{1,96}"))
        fun validId(id: String) = id.matches(Regex("[a-f0-9]{64}"))
        fun decode(url: String): ByteArray? {
            if (url.length > MAX_IMAGE_BYTES.toLong() * 4 / 3 + 128) return null
            val split = url.indexOf(',')
            if (split !in 1..100 || !url.substring(0, split).matches(Regex("data:image/(png|jpeg|webp);base64"))) return null
            return runCatching { Base64.getDecoder().decode(url.substring(split + 1)) }.getOrNull()?.takeIf { it.size <= MAX_IMAGE_BYTES }
        }
    }
    private fun directory(thread: String): File {
        require(validThread(thread)) { "无效的历史会话编号" }
        require(!root.isFile && !java.nio.file.Files.isSymbolicLink(root.toPath()))
        val dir = File(root, thread)
        require(!java.nio.file.Files.isSymbolicLink(dir.toPath()))
        return dir
    }
    private fun file(dir: File, name: String): File = File(dir, name).also {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath()))
    }
    private fun atomic(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        require(!java.nio.file.Files.isSymbolicLink(temp.toPath()))
        try { temp.outputStream().use { it.write(bytes) }; check(temp.renameTo(target)) }
        finally { temp.delete() }
    }
    @Synchronized fun save(thread: String, bytes: ByteArray, source: String = "original"): JSONObject? = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val dir = directory(thread); val id = digest(bytes)
        metadata(thread, id)?.let { return it }
        val preview = encoder.encode(bytes) ?: return null
        if (preview.bytes.isEmpty()) return null
        val required = bytes.size.toLong() + preview.bytes.size + 4096
        if (root.walkTopDown().filter(File::isFile).sumOf(File::length) + required > MAX_ARCHIVE_BYTES) return null
        check(dir.mkdirs() || dir.isDirectory)
        atomic(file(dir, "$id.original"), bytes)
        atomic(file(dir, "$id.preview"), preview.bytes)
        val meta = obj("id" to id, "width" to preview.sourceWidth, "height" to preview.sourceHeight,
            "previewWidth" to preview.width, "previewHeight" to preview.height, "mime" to preview.mime,
            "originalBytes" to bytes.size, "previewBytes" to preview.bytes.size, "previewSha256" to digest(preview.bytes),
            "createdAtMs" to System.currentTimeMillis(), "source" to source, "originalAvailable" to (source != "prepared_context"), "historical" to true)
        atomic(file(dir, "$id.json"), meta.toString().toByteArray())
        meta
    }.getOrNull()
    @Synchronized fun metadata(thread: String, id: String): JSONObject? = runCatching {
        require(validId(id)); val dir = directory(thread)
        val metaFile = file(dir, "$id.json"); if (!metaFile.isFile || metaFile.length() > 4096) return null
        val meta = JSONObject(metaFile.readText())
        if (meta.optString("id") != id || file(dir, "$id.original").length() != meta.getLong("originalBytes") || file(dir, "$id.preview").length() != meta.getLong("previewBytes")) return null
        meta
    }.getOrNull()
    @Synchronized fun bytes(thread: String, id: String, original: Boolean): ByteArray {
        val meta = metadata(thread, id) ?: error("历史图片不存在或缓存不完整；不会重新拍摄当前屏幕代替它")
        val path = file(directory(thread), "$id.${if (original) "original" else "preview"}")
        require(path.length() <= MAX_IMAGE_BYTES)
        val bytes = path.readBytes()
        check(digest(bytes) == if (original) id else meta.getString("previewSha256")) { "历史图片校验失败" }
        return bytes
    }
    fun url(thread: String, id: String, original: Boolean): String {
        val meta = metadata(thread, id) ?: error("历史图片不可用")
        val mime = if (original) meta.getString("mime") else "image/jpeg"
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes(thread, id, original))
    }
    @Synchronized fun list(thread: String, offset: Int = 0, limit: Int = 30): JSONObject {
        val dir = directory(thread)
        val all = dir.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .mapNotNull { metadata(thread, it.name.removeSuffix(".json")) }.sortedByDescending { it.optLong("createdAtMs") }
        val start = offset.coerceAtLeast(0); val end = (start + limit.coerceIn(1, 50)).coerceAtMost(all.size)
        return obj("images" to JSONArray(if (start < end) all.subList(start, end) else emptyList<JSONObject>()), "nextOffset" to end.takeIf { it < all.size }, "total" to all.size)
    }
    @Synchronized fun bindCall(thread: String, callId: String, ids: List<String>) {
        if (callId.isBlank() || ids.isEmpty() || ids.any { metadata(thread, it) == null }) return
        val dir = directory(thread); dir.mkdirs()
        atomic(file(dir, "call-${digest(callId.toByteArray())}.json"), JSONArray(ids).toString().toByteArray())
    }
    @Synchronized fun callImages(thread: String, callId: String): List<String> = runCatching {
        if (callId.isBlank()) return emptyList()
        val f = file(directory(thread), "call-${digest(callId.toByteArray())}.json")
        if (!f.isFile || f.length() > 16_384) return emptyList()
        val a = JSONArray(f.readText()); (0 until a.length()).map { a.getString(it) }.takeIf { ids -> ids.all { metadata(thread, it) != null } }.orEmpty()
    }.getOrDefault(emptyList())
}

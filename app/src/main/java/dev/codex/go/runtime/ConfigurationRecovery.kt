package dev.codex.go.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

fun configurationWarningText(value: org.json.JSONObject): String? {
    val summary = value.opt("summary") as? String ?: return null
    val path = value.opt("path") as? String
    if (path?.substringAfterLast('/') != "config.toml" && !summary.startsWith("Invalid configuration")) return null
    return listOfNotNull(summary, value.opt("details") as? String).filter(String::isNotBlank).joinToString("\n")
}

enum class ConfigurationTarget { Defaults, LastGood, Previous }
data class ConfigurationStatus(val lastGood: Boolean = false, val previous: Boolean = false)

/** Private snapshots live outside CODEX_HOME; they may contain provider secrets. */
class ConfigurationRecovery(private val config: File, private val store: File) {
    fun status() = ConfigurationStatus(File(store, "good.toml").isFile, previous()?.isFile == true)
    fun snapshot(): ByteArray {
        require(!Files.isSymbolicLink(config.toPath())) { "配置是符号链接，请先检查原文件" }
        if (!Files.exists(config.toPath(), NOFOLLOW_LINKS)) return DEFAULTS.toByteArray()
        require(config.isFile) { "配置路径不是普通文件，已保留原路径" }
        return read(config)
    }
    fun markGood(candidate: ByteArray) {
        if (snapshot().contentEquals(candidate)) atomic(File(store, "good.toml"), candidate)
    }
    fun restore(target: ConfigurationTarget) {
        val replacement = when (target) {
            ConfigurationTarget.Defaults -> DEFAULTS.toByteArray()
            ConfigurationTarget.LastGood -> read(File(store, "good.toml"))
            ConfigurationTarget.Previous -> read(checkNotNull(previous()) { "还没有配置备份" })
        }
        val original = snapshot() // Refuse unreadable, oversized, or linked configs before any mutation.
        val backup = File(store, "backup-${UUID.randomUUID()}.toml")
        atomic(backup, original)
        atomic(File(store, "previous"), backup.name.toByteArray()) // Commit backup before replacing config.
        atomic(config, replacement)
        store.listFiles()?.filter { it.name.startsWith("backup-") && it != backup }
            ?.sortedByDescending(File::lastModified)?.drop(4)?.forEach(File::delete)
    }
    private fun previous(): File? = runCatching {
        val name = File(store, "previous").readText()
        require(Regex("backup-[a-f0-9-]{36}\\.toml").matches(name))
        File(store, name)
    }.getOrNull()
    private fun read(file: File): ByteArray {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "配置快照不存在或无法读取" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "配置超过 1 MB，已保留原文件" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    companion object {
        const val DEFAULTS = "cli_auth_credentials_store = \"file\"\n[analytics]\nenabled = false\n"
        private const val MAX_BYTES = 1024 * 1024
        private fun atomic(file: File, bytes: ByteArray) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建配置备份目录" }
            val temp = File(file.parentFile, ".${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }
}

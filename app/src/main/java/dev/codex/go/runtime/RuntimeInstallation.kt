package dev.codex.go.runtime

import java.io.File

/** Only the replaceable guest directory is moved. Sibling workspace and Codex data stay in place. */
object RuntimeInstallation {
    private fun previous(root: File) = File(root.parentFile, "environment-previous")
    fun recover(root: File) {
        val previous = previous(root)
        if (!root.exists() && previous.exists()) check(previous.renameTo(root)) { "无法恢复先前的运行环境" }
    }
    fun replace(root: File, staging: File) {
        val previous = previous(root)
        if (previous.exists()) check(previous.deleteRecursively()) { "无法清理先前的环境备份" }
        if (root.exists()) check(root.renameTo(previous)) { "无法替换旧环境" }
        if (!staging.renameTo(root)) {
            if (previous.exists()) check(previous.renameTo(root)) { "环境安装失败；保留的环境备份将在下次启动时恢复" }
            error("环境安装未完成，请重试")
        }
        previous.deleteRecursively()
    }
}

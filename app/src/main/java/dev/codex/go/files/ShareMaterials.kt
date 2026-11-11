package dev.codex.go.files

import java.io.File

/** Unconfirmed files are outside the native workspace. A confirmed batch moves as a directory. */
class ShareMaterials(val staging: File, val workspace: File) {
    fun resolve(attachment: Attachment): File? = WorkspaceFiles(staging).resolve(attachment.path) ?: WorkspaceFiles(workspace).resolve(mapped(attachment).path)
    fun mapped(attachment: Attachment): Attachment {
        require(Regex("^/workspace/imports/([a-f0-9-]{36})/[0-9]+/[^/]+$").matches(attachment.path)) { "分享附件路径无效" }
        return attachment.copy(path = Project.guestRoot(workspace) + attachment.path.removePrefix("/workspace"))
    }
    fun mapped(draft: Draft) = draft.copy(attachments = draft.attachments.map(::mapped))
    fun publish(draft: Draft) {
        val batches = draft.attachments.map { attachment ->
            val match = Regex("^/workspace/imports/([a-f0-9-]{36})/[0-9]+/[^/]+$").matchEntire(attachment.path)
            requireNotNull(match) { "分享附件路径无效" }
            val file = resolve(attachment)
            require(file != null && file.length() <= WorkspaceFiles.MAX_FILE_BYTES) { "分享附件已移除或超过 64 MB" }
            match.groupValues[1]
        }.distinct()
        batches.forEach { id ->
            val source = File(staging, "imports/$id")
            val target = File(workspace, "imports/$id")
            if (source.exists()) {
                require(!java.nio.file.Files.isSymbolicLink(source.toPath()) && source.canonicalFile.parentFile?.parentFile == staging.canonicalFile && target.parentFile!!.canonicalPath.startsWith(workspace.canonicalPath + File.separator) && !target.exists()) { "分享目标路径冲突，原文件仍保留" }
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                check(source.renameTo(target)) { "无法将分享材料移入工作区" }
            } // A previous confirmed attempt may have moved files before its preferences commit.
        }
    }
    fun discard(draft: Draft) {
        draft.attachments.mapNotNull { WorkspaceFiles(staging).resolve(it.path)?.parentFile?.parentFile }.distinct().forEach { it.deleteRecursively() }
    }
}

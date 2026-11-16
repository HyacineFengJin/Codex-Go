package dev.codex.go

import dev.codex.go.files.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ShareMaterialsTest {
    @Test fun largeFileIsPublishedIntactWithoutReadingItIntoMemory() = temporary { stage, work ->
        val id = UUID.randomUUID().toString()
        val source = File(stage, "imports/$id/0/large.bin").apply { parentFile!!.mkdirs() }
        val length = 65L * 1024 * 1024
        java.io.RandomAccessFile(source, "rw").use { it.setLength(length); it.seek(length - 1); it.write(37) }
        val attachment = Attachment("/workspace/imports/$id/0/large.bin", "large.bin", "application/octet-stream", length)
        val materials = ShareMaterials(stage, work)
        materials.publish(Draft(attachments = listOf(attachment)))
        val published = materials.resolve(attachment)!!
        assertEquals(length, published.length())
        java.io.RandomAccessFile(published, "r").use { it.seek(length - 1); assertEquals(37, it.read()) }
        assertFalse(source.exists())
    }
    @Test fun unconfirmedFilesStayPrivateAndConfirmedMoveCanResumeAfterRestart() = temporary { stage, work ->
        val materials = ShareMaterials(stage, work)
        val id = UUID.randomUUID().toString()
        val source = File(stage, "imports/$id/0/test.txt").apply { parentFile!!.mkdirs(); writeText("original") }
        val other = File(stage, "imports/${UUID.randomUUID()}/0/other").apply { parentFile!!.mkdirs(); writeText("private") }
        val attachment = Attachment("/workspace/imports/$id/0/test.txt", "test.txt", "text/plain", 8)
        assertNull(WorkspaceFiles(work).resolve(attachment.path))
        assertEquals(source.canonicalFile, materials.resolve(attachment))
        val draft = Draft(attachments = listOf(attachment))
        materials.publish(draft)
        ShareMaterials(stage, work).publish(draft) // Death between directory move and preferences commit.
        assertFalse(source.exists()); assertEquals("original", WorkspaceFiles(work).resolve(attachment.path)!!.readText())
        assertEquals("private", other.readText())
    }
    @Test fun discardRemovesOnlyUnconfirmedBatchAndRejectsUnsafePublishPaths() = temporary { stage, work ->
        val id = UUID.randomUUID().toString()
        val file = File(stage, "imports/$id/0/file").apply { parentFile!!.mkdirs(); writeText("pending") }
        val draft = Draft(attachments = listOf(Attachment("/workspace/imports/$id/0/file", "file", "text/plain", 7)))
        val materials = ShareMaterials(stage, work)
        val unsafe = draft.copy(attachments = listOf(draft.attachments[0].copy(path = "/workspace/../../private")))
        assertThrows(IllegalArgumentException::class.java) { materials.publish(unsafe) }
        assertTrue(file.isFile)
        materials.discard(draft); assertFalse(file.exists())
        File(work, "keep").writeText("published")
        materials.discard(draft); assertEquals("published", File(work, "keep").readText())
    }
    private fun temporary(block: (File, File) -> Unit) {
        val base = Files.createTempDirectory("share-materials").toFile()
        try { block(File(base, "staging").apply { mkdir() }, File(base, "workspace").apply { mkdir() }) } finally { base.deleteRecursively() }
    }
}

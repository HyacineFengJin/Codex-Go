package dev.codex.go

import dev.codex.go.files.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ProjectTest {
    @Test fun importOwnerMigratesOnlyOnceToItsOwnFirstThread() {
        val a = Project(UUID.randomUUID().toString(), "A")
        val owner = DraftOwner(a, null)
        owner.migrate(UUID.randomUUID().toString(), "wrong")
        assertEquals(a.slot(null), owner.slot)
        owner.migrate(a.id, "thread-a")
        assertEquals("thread-a", owner.slot)
        owner.migrate(a.id, "later-thread")
        assertEquals("thread-a", owner.slot)
    }
    @Test fun projectIdentityKeepsLegacyKeysAndDraftOwnersSeparate() {
        val a = Project(UUID.randomUUID().toString(), "A")
        val b = Project(UUID.randomUUID().toString(), "B")
        assertEquals("new", Project.DEFAULT.slot(null))
        assertNotEquals(a.slot(null), b.slot(null))
        assertEquals("thread-id", a.slot("thread-id"))
        assertEquals(a.cwd, a.copy(name = "Renamed").cwd)
        assertEquals(a.slot(null), a.copy(name = "Renamed").slot(null))
    }
    @Test fun projectFilesUseStableNativePathsAndRejectOtherProjects() = temporary { base ->
        val a = Project(UUID.randomUUID().toString(), "A"); val b = Project(UUID.randomUUID().toString(), "B")
        val ar = a.directory(base);val br = b.directory(base)
        val af = File(ar, "same.txt").apply { writeText("A") }; val bf = File(br, "same.txt").apply { writeText("B") }
        val path = WorkspaceFiles(ar).guest(af)
        assertEquals(a.cwd + "/same.txt", path)
        assertEquals(af.canonicalFile, WorkspaceFiles(ar).resolve(path))
        assertNull(WorkspaceFiles(br).resolve(path)); assertNull(WorkspaceFiles(Project.DEFAULT.directory(base)).resolve(path))
        assertNull(WorkspaceFiles(ar).resolve(a.cwd + "/../" + b.id + "/same.txt"))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceFiles(ar).guest(bf) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceFiles(Project.DEFAULT.directory(base)).guest(af) }
    }
    @Test fun projectNamesNeverBecomePathsAndInvalidIdsAreRejected() {
        val a = Project(UUID.randomUUID().toString(), "../../friendly")
        assertFalse(a.cwd.contains("friendly"))
        assertThrows(IllegalArgumentException::class.java) { Project("../escape", "A") }
        assertThrows(IllegalArgumentException::class.java) { Project(UUID.randomUUID().toString(), "new\nline") }
    }
    @Test fun projectDirectoryLinksCannotRedirectMaterials() = temporary { base ->
        val outside = Files.createTempDirectory("project-outside").toFile()
        try {
            Files.createSymbolicLink(File(base, Project.DIRECTORY).toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { Project(UUID.randomUUID().toString(), "A").directory(base) }
        } finally { outside.deleteRecursively() }
    }
    @Test fun confirmedShareMovesToChosenProjectAndResumesWithSameNativePath() = temporary { base ->
        val project = Project(UUID.randomUUID().toString(), "A")
        val staging = File(base.parentFile, "staging-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val batch = UUID.randomUUID().toString();val source = File(staging,"imports/$batch/0/test.txt").apply { parentFile!!.mkdirs();writeText("source") }
            val incoming = Draft("shared",listOf(Attachment("/workspace/imports/$batch/0/test.txt","test.txt","text/plain",6)))
            val materials = ShareMaterials(staging, project.directory(base))
            val mapped = materials.mapped(incoming)
            assertTrue(mapped.attachments[0].path.startsWith(project.cwd + "/imports/"))
            assertTrue(source.isFile)
            materials.publish(incoming)
            val reopened = ShareMaterials(staging, project.directory(base)); reopened.publish(incoming)
            assertEquals("source", reopened.resolve(incoming.attachments[0])!!.readText())
            assertNull(WorkspaceFiles(Project.DEFAULT.directory(base)).resolve(mapped.attachments[0].path))
            assertFalse(source.exists())
        } finally { staging.deleteRecursively() }
    }
    @Test fun projectCannotReadSymlinkToAnotherProject() = temporary { base ->
        val a=Project(UUID.randomUUID().toString(),"A");val b=Project(UUID.randomUUID().toString(),"B")
        val ar=a.directory(base);val target=File(b.directory(base),"secret").apply { writeText("B") }
        Files.createSymbolicLink(File(ar,"alias").toPath(),target.toPath())
        assertNull(WorkspaceFiles(ar).resolve(a.cwd+"/alias"))
    }
    private fun temporary(block: (File)->Unit) {
        val base=Files.createTempDirectory("codex-go-projects").toFile()
        try { block(base) } finally { base.deleteRecursively() }
    }
}

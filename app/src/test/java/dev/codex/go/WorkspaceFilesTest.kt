package dev.codex.go

import dev.codex.go.files.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceFilesTest {
    @Test fun artifactLinksCannotEscapeThroughTraversalOrSymlinks() {
        val directory = Files.createTempDirectory("workspace-links").toFile()
        try {
            val root = directory.resolve("workspace").apply { mkdir() }
            val outside = directory.resolve("account.json").apply { writeText("private") }
            val workspace = WorkspaceFiles(root)
            Files.createSymbolicLink(root.resolve("escape").toPath(), outside.toPath())
            assertNull(workspace.resolve("sandbox:/workspace/../account.json"))
            assertNull(workspace.resolve("sandbox:/workspace/%2e%2e/account.json"))
            assertNull(workspace.resolve("sandbox:/workspace/escape"))
            assertNull(workspace.resolve("file:///data/data/dev.codex.go/files/account.json"))
            assertNull(workspace.resolve("/workspace-other/account.json"))
            val file = root.resolve("图表 + (1).csv").apply { writeText("a,b\n1,2") }
            val link = WorkspaceFiles.link(workspace.guest(file))
            assertEquals(file.canonicalFile, workspace.resolve(link))
            assertEquals(listOf(file.canonicalFile), workspace.referenced("[图表]($link)\n[重复]($link)"))
            val literal = root.resolve("literal%20+data.csv").apply { writeText("literal") }
            assertEquals(literal.canonicalFile, workspace.resolve(workspace.guest(literal)))
            assertEquals(literal.canonicalFile, workspace.resolve(WorkspaceFiles.link(workspace.guest(literal))))
        } finally { directory.deleteRecursively() }
    }

    @Test fun imageAndFileSubmissionSurvivesDraftPersistence() {
        val draft = Draft("分析附件", listOf(
            Attachment("/workspace/imports/photo.png", "photo.png", "image/png", 80),
            Attachment("/workspace/imports/data.csv", "data.csv", "text/csv", 40),
        ))
        val restored = Draft.fromJson(draft.json())
        assertEquals(draft, restored)
        val input = restored.input()
        assertEquals(2, input.length())
        assertEquals("localImage", input.getJSONObject(1).getString("type"))
        assertEquals("/workspace/imports/photo.png", input.getJSONObject(1).getString("path"))
        assertTrue(input.getJSONObject(0).getString("text").contains("sandbox:/workspace/imports/data.csv"))
        assertFalse(Draft(attachments = draft.attachments).empty)
    }
}

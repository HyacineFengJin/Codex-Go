package dev.codex.go

import dev.codex.go.runtime.RuntimeDiagnostics
import dev.codex.go.runtime.RuntimeInstallation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaintenanceTest {
    @Test fun diagnosticErrorsNeverIncludeMessagesOrUnknownFields() {
        val event = RuntimeDiagnostics.entry(RuntimeDiagnostics.Event.StartFailed, "secret-stage", IllegalStateException("user@example.test sk-secret /workspace/private.txt"))
        event.put("message", "private prompt").put("token", "sk-private")
        val result = RuntimeDiagnostics.sanitize(JSONArray().put(event).put(JSONObject().put("event", "private prompt"))).toString()
        assertTrue(result.contains("StartFailed"))
        assertTrue(result.contains("IllegalStateException"))
        for (secret in listOf("user@", "sk-", "workspace", "private prompt", "secret-stage", "token", "message")) assertFalse(secret, result.contains(secret))
    }
    @Test fun repairReplacesOnlyGuestAndPreservesSiblingData() = temporary { base ->
        val root = File(base, "environment").apply { mkdir() }
        File(root, "old-tool").writeText("old")
        val account = File(base, "codex").apply { mkdir() }
        File(account, "auth.json").writeText("test-account")
        File(account, "history.jsonl").writeText("test-history")
        val workspace = File(base, "workspace").apply { mkdir() }
        File(workspace, "report.txt").writeText("work")
        val staging = File(base, "staging").apply { mkdir() }
        File(staging, "new-tool").writeText("new")
        RuntimeInstallation.replace(root, staging)
        assertFalse(File(root, "old-tool").exists())
        assertEquals("new", File(root, "new-tool").readText())
        assertEquals("test-account", File(account, "auth.json").readText())
        assertEquals("test-history", File(account, "history.jsonl").readText())
        assertEquals("work", File(workspace, "report.txt").readText())
    }
    @Test fun failedReplacementRestoresWorkingGuest() = temporary { base ->
        val root = File(base, "environment").apply { mkdir() }
        File(root, "working").writeText("intact")
        assertThrows(IllegalStateException::class.java) { RuntimeInstallation.replace(root, File(base, "missing")) }
        assertEquals("intact", File(root, "working").readText())
    }
    @Test fun interruptedDirectorySwapRecoversOnNextInstall() = temporary { base ->
        val previous = File(base, "environment-previous").apply { mkdir() }
        File(previous, "working").writeText("intact")
        val root = File(base, "environment")
        RuntimeInstallation.recover(root)
        assertEquals("intact", File(root, "working").readText())
        assertFalse(previous.exists())
    }
    private fun temporary(block: (File) -> Unit) {
        val base = Files.createTempDirectory("codex-go-maintenance").toFile()
        try { block(base) } finally { base.deleteRecursively() }
    }
}

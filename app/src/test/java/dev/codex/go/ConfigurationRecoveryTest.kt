package dev.codex.go

import dev.codex.go.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigurationRecoveryTest {
    @Test fun environmentFallbackWarningCannotSuggestResettingAccountConfig() {
        assertNull(configurationWarningText(org.json.JSONObject().put("summary", "Using bundled bubblewrap").put("details", org.json.JSONObject.NULL).put("path", org.json.JSONObject.NULL)))
        val parse = org.json.JSONObject().put("summary", "Invalid configuration; using defaults.").put("path", "/root/.codex/config.toml").put("details", "unclosed array")
        assertEquals("Invalid configuration; using defaults.\nunclosed array", configurationWarningText(parse))
        parse.put("details", org.json.JSONObject.NULL)
        assertEquals("Invalid configuration; using defaults.", configurationWarningText(parse))
    }
    @Test fun defaultsBackUpExactBytesAndUndoPreservesOtherData() = temporary { base, config, recovery ->
        val original = byteArrayOf(0, 1, 2, 13, 10, -1)
        config.writeBytes(original)
        val auth = File(config.parentFile, "auth.json").apply { writeText("private") }
        val work = File(base, "workspace/report").apply { parentFile!!.mkdirs(); writeText("result") }
        recovery.restore(ConfigurationTarget.Defaults)
        assertEquals(ConfigurationRecovery.DEFAULTS, config.readText())
        recovery.restore(ConfigurationTarget.Previous)
        assertArrayEquals(original, config.readBytes())
        assertEquals("private", auth.readText()); assertEquals("result", work.readText())
        recovery.restore(ConfigurationTarget.Previous)
        assertEquals(ConfigurationRecovery.DEFAULTS, config.readText())
    }
    @Test fun onlyUnchangedSuccessfulStartupBecomesKnownGood() = temporary { _, config, recovery ->
        config.writeText("model = 'a'")
        val candidate = recovery.snapshot()
        config.writeText("broken = [")
        recovery.markGood(candidate)
        assertFalse(recovery.status().lastGood)
        config.writeBytes(candidate); recovery.markGood(candidate)
        config.writeText("broken = [")
        recovery.restore(ConfigurationTarget.LastGood)
        assertArrayEquals(candidate, config.readBytes())
        recovery.restore(ConfigurationTarget.Previous)
        assertEquals("broken = [", config.readText())
    }
    @Test fun missingSnapshotDoesNotMutateConfig() = temporary { _, config, recovery ->
        config.writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { recovery.restore(ConfigurationTarget.LastGood) }
        assertEquals("keep", config.readText()); assertFalse(recovery.status().previous)
    }
    @Test fun linksAndOversizedConfigsAreNeverOverwritten() = temporary { base, config, recovery ->
        val target = File(base, "secret").apply { writeText("keep") }
        Files.createSymbolicLink(config.toPath(), target.toPath())
        assertThrows(IllegalArgumentException::class.java) { recovery.restore(ConfigurationTarget.Defaults) }
        assertEquals("keep", target.readText()); Files.delete(config.toPath())
        config.writeBytes(ByteArray(1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { recovery.restore(ConfigurationTarget.Defaults) }
        assertEquals(1024 * 1024 + 1L, config.length()); assertFalse(recovery.status().previous)
    }
    @Test fun unreadableCurrentConfigRetainsPreviousSnapshot() = temporary { base, config, recovery ->
        config.writeText("original")
        recovery.restore(ConfigurationTarget.Defaults)
        config.delete(); config.mkdir(); File(config, "child").writeText("block")
        assertThrows(IllegalArgumentException::class.java) { recovery.restore(ConfigurationTarget.Previous) }
        config.deleteRecursively(); config.writeText("current")
        val reopened = ConfigurationRecovery(config, File(base, "recovery"))
        reopened.restore(ConfigurationTarget.Previous)
        assertEquals("original", config.readText())
    }
    private fun temporary(block: (File, File, ConfigurationRecovery) -> Unit) {
        val base = Files.createTempDirectory("config-recovery-test").toFile()
        val config = File(base, "codex/config.toml").also { it.parentFile!!.mkdirs() }
        try { block(base, config, ConfigurationRecovery(config, File(base, "recovery"))) } finally { base.deleteRecursively() }
    }
}

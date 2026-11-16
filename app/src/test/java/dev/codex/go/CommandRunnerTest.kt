package dev.codex.go

import dev.codex.go.shell.CommandRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files

class CommandRunnerTest {
    @Test fun longCommandAndCallerChosenDeadlineAreAccepted() {
        val command = "printf finished #" + "x".repeat(20000)
        val result = JSONObject(CommandRunner("/bin/sh").execute("long", command, 180_000))
        assertEquals("finished", result.getString("stdout")); assertEquals(0, result.getInt("exitCode"))
    }
    @Test fun preservesStreamsAndExitStatus() {
        val result = JSONObject(CommandRunner("/bin/sh").execute("streams", "printf '你好'; printf 'failed' >&2; exit 7", 5000))
        assertEquals("你好", result.getString("stdout")); assertEquals("failed", result.getString("stderr")); assertEquals(7, result.getInt("exitCode"))
    }
    @Test fun drainsLargeOutputWithoutDeadlockAndReportsTruncation() {
        val result = JSONObject(CommandRunner("/bin/sh").execute("large", "head -c 100000 /dev/zero; head -c 100000 /dev/zero >&2", 5000))
        assertEquals(0, result.getInt("exitCode")); assertEquals(48_000, result.getString("stdout").length); assertEquals(12_000, result.getString("stderr").length); assertTrue(result.getBoolean("truncated"))
    }
    @Test fun timesOutAndStopsTheProcess() {
        val result = JSONObject(CommandRunner("/bin/sh").execute("timeout", "exec sleep 30", 1000))
        assertEquals(124, result.getInt("exitCode")); assertTrue(result.getBoolean("timedOut"))
    }
    @Test fun cancellationStopsRunningCommand() {
        val runner = CommandRunner("/bin/sh"); val executor = Executors.newSingleThreadExecutor()
        val marker = Files.createTempDirectory("codex-go-cancel-").resolve("started")
        try {
            val result = executor.submit<String> { runner.execute("cancel", "printf started > '$marker'; exec sleep 30", 0) }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!Files.exists(marker) && System.nanoTime() < until) Thread.sleep(10)
            assertTrue(Files.exists(marker)); runner.cancel("cancel")
            assertNotEquals(0, JSONObject(result.get(1, TimeUnit.SECONDS)).getInt("exitCode"))
        } finally { runner.close(); executor.shutdownNow(); Files.deleteIfExists(marker); Files.deleteIfExists(marker.parent) }
    }
    @Test fun cancellationBeforeDispatchPreventsExecution() {
        val runner = CommandRunner("/bin/sh")
        runner.cancel("before")
        assertThrows(IllegalStateException::class.java) { runner.execute("before", "printf should_not_run", 5000) }
    }
}

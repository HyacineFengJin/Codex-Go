package dev.codex.go

import dev.codex.go.runtime.RuntimeLogs
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.InterruptedIOException

class RuntimeLogsTest {
    @Test fun closingAChildPipeDuringStopDoesNotEscapeTheLogCoroutine() {
        var released = false
        val interrupted = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("read interrupted by close() on another thread")
            override fun close() { released = true }
        }
        RuntimeLogs.consume(interrupted) { fail("Closed pipe must not produce a log line") }
        assertTrue(released)
    }
}

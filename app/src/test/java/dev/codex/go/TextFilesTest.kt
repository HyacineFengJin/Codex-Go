package dev.codex.go

import dev.codex.go.files.TextFiles
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TextFilesTest {
    @Test fun unknownLengthStreamStopsBeforeAllocatingTheWholeInput() {
        val stream = object : java.io.ByteArrayInputStream(ByteArray(100_000) { 65 }) { val consumed get() = pos }
        assertThrows(dev.codex.go.files.TextFileMemoryException::class.java) { TextFiles.read(stream, 10_000) }
        assertTrue(stream.consumed < 100_000)
        assertEquals("你好", TextFiles.read(java.io.ByteArrayInputStream("你好".toByteArray()), 6))
    }
    @Test fun ordinarySourceFileAbove256KiBReadsWithoutLosingText() {
        val file = Files.createTempFile("codex-text", ".txt").toFile()
        try {
            val text = "你好 source line\n".repeat(24000)
            file.writeText(text)
            assertTrue(file.length() > 256 * 1024)
            assertEquals(text, TextFiles.read(file))
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertThrows(IllegalArgumentException::class.java) { TextFiles.read(file) }
        } finally { file.delete() }
    }
}

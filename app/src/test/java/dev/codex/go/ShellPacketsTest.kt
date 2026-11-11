package dev.codex.go

import dev.codex.go.adb.ShellPackets
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShellPacketsTest {
    private fun packet(id: Int, bytes: ByteArray) = byteArrayOf(id.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array() + bytes
    @Test fun fragmentedFramesPreservePngAndExitStatus() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 127, -1)
        val input = ByteArrayInputStream(packet(1, png) + packet(2, "warning".toByteArray()) + packet(3, byteArrayOf(0)))
        val result = ShellPackets.read { bytes, offset, length -> input.read(bytes, offset, minOf(length, 2)) }
        assertArrayEquals(png, result.stdout); assertEquals("warning", result.stderr); assertEquals(0, result.exit)
    }
    @Test fun nonzeroExitIsRetainedAndTruncationCannotPassAsSuccess() {
        val input = ByteArrayInputStream(packet(2, "permission denied".toByteArray()) + packet(3, byteArrayOf(13)))
        assertEquals(13, ShellPackets.read { bytes, offset, length -> input.read(bytes, offset, length) }.exit)
        val truncated = ByteArrayInputStream(packet(1, "partial output".toByteArray()))
        assertThrows(EOFException::class.java) { ShellPackets.read { bytes, offset, length -> truncated.read(bytes, offset, length) } }
    }
}

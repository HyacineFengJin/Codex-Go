package dev.codex.go.adb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read shell-v2 frames directly, including the explicit exit status. No PTY or text conversion. */
object ShellPackets {
    data class Result(val stdout: ByteArray, val stderr: String, val exit: Int)
    fun read(read: (ByteArray, Int, Int) -> Int): Result {
        fun exact(size: Int): ByteArray {
            val bytes = ByteArray(size); var position = 0
            while (position < size) {
                val count = read(bytes, position, size - position)
                if (count < 0) throw EOFException("ADB shell ended without an exit packet")
                if (count > 0) position += count
            }
            return bytes
        }
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        while (true) {
            val header = exact(5)
            val length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(length in 0..16_777_216) { "Invalid ADB frame length" }
            val bytes = exact(length)
            when (header[0].toInt()) {
                1 -> stdout.write(bytes)
                2 -> stderr.write(bytes)
                3 -> {
                    require(length in 1..4) { "Invalid ADB exit status" }
                    var exit = 0; bytes.forEachIndexed { index, byte -> exit = exit or ((byte.toInt() and 255) shl (index * 8)) }
                    return Result(stdout.toByteArray(), stderr.toString("UTF-8"), exit)
                }
            }
            require(stdout.size() + stderr.size() <= 24 * 1024 * 1024) { "ADB output exceeds limit" }
        }
    }
}

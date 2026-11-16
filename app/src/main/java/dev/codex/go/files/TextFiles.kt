package dev.codex.go.files

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class TextFileMemoryException : IllegalArgumentException("Text exceeds available memory budget")

object TextFiles {
    fun memoryBudget(): Long {
        val runtime = Runtime.getRuntime()
        val remaining = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return (remaining / 8).coerceAtLeast(0)
    }
    fun read(file: File): String {
        // The editor holds bytes, decoded text, undo state and layout at once. Its
        // budget follows this device's heap, rather than rejecting every file >256 KiB.
        val budget = memoryBudget()
        if (file.length() > budget) throw TextFileMemoryException()
        return file.inputStream().use { read(it, budget) }
    }
    /** Check during the read too: file size and provider metadata can change. */
    fun read(input: InputStream, budgetBytes: Long = memoryBudget()): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > budgetBytes - output.size()) throw TextFileMemoryException()
            output.write(buffer, 0, count)
        }
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        require(!value.contains('\u0000')) { "Not a UTF-8 text file" }
        return value
    }
}

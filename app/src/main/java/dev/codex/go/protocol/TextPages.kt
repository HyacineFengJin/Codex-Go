package dev.codex.go.protocol

/** Rendering pages preserve every UTF-16 code point; no content is discarded. */
class TextPages(val text: String) {
    private val boundaries = mutableListOf(0)
    init {
        var start = 0
        while (start < text.length) { start = end(text, start, 8192, 120); boundaries += start }
        if (boundaries.size == 1) boundaries += 0
    }
    val size get() = boundaries.size - 1
    fun page(index: Int) = text.substring(boundaries[index], boundaries[index + 1])
    fun pageForOffset(offset: Int): Int {
        val value = boundaries.binarySearch(offset.coerceIn(0, text.length))
        return (if (value >= 0) value else -value - 2).coerceIn(0, size - 1)
    }
    companion object {
        fun needsReader(text: String) = text.length > 16_000 || text.asSequence().filter { it == '\n' }.take(201).count() > 200
        fun preview(text: String) = text.substring(0, end(text, 0, 2048, 40))
        private fun end(text: String, start: Int, chars: Int, lines: Int): Int {
            var next = start; var count = 0
            val limit = minOf(text.length.toLong(), start.toLong() + chars).toInt()
            while (next < limit) { if (text[next++] == '\n' && ++count == lines) break }
            if (next < text.length && next > start && text[next].isLowSurrogate() && text[next - 1].isHighSurrogate()) next++
            return next
        }
    }
}

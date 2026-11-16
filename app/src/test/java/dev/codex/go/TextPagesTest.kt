package dev.codex.go

import dev.codex.go.protocol.TextPages
import org.junit.Assert.*
import org.junit.Test

class TextPagesTest {
    @Test fun hugeTextAndTrailingNewlinesSurviveEveryPage() {
        val text = "FIRST\n" + "原文 line\n".repeat(30000) + "LAST\n\n"
        val pages = TextPages(text)
        assertTrue(TextPages.needsReader(text))
        assertEquals(text, (0 until pages.size).joinToString("") { pages.page(it) })
        assertTrue(pages.page(pages.pageForOffset(text.indexOf("LAST"))).contains("LAST"))
        assertTrue(pages.page(0).startsWith(TextPages.preview(text)))
    }
    @Test fun pageBoundaryNeverSplitsSurrogatePairOrDropsUnbrokenLines() {
        val text = "a".repeat(8191) + "😀" + "b".repeat(40000)
        val pages = TextPages(text)
        assertEquals(text, (0 until pages.size).joinToString("") { pages.page(it) })
        repeat(pages.size) { assertFalse(pages.page(it).first().isLowSurrogate()); assertFalse(pages.page(it).last().isHighSurrogate()) }
        assertEquals(0, pages.pageForOffset(8191)); assertEquals(1, pages.pageForOffset(8193))
    }
    @Test fun manyShortLinesUseReaderAndEmptyTextStillHasOnePage() {
        assertTrue(TextPages.needsReader("x\n".repeat(250)))
        assertFalse(TextPages.needsReader("ordinary message"))
        val empty = TextPages(""); assertEquals(1, empty.size); assertEquals("", empty.page(0))
    }
}

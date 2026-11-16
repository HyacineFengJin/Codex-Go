package dev.codex.go

import dev.codex.go.files.*
import org.junit.Assert.*
import org.junit.Test

class ShareDraftTest {
    @Test fun appendPreservesExistingDraftAndDistinctFiles() {
        val old = Attachment("/workspace/old.txt", "same.txt", "text/plain", 5)
        val new = old.copy(path = "/workspace/new.txt")
        val merged = SharedDraft.merge(Draft("unfinished", listOf(old)), Draft("https://example.test", listOf(new, old)))
        assertEquals("unfinished\n\nhttps://example.test", merged.text)
        assertEquals(listOf(old, new), merged.attachments)
    }
    @Test fun largeBatchMergesWithoutDroppingFilesOrMutatingDrafts() {
        val old = Draft("keep", (0..39).map { Attachment("/workspace/$it", "$it", "text/plain", 1) })
        val incoming = Draft("new", listOf(Attachment("/workspace/new", "new", "text/plain", 1)))
        val merged = SharedDraft.merge(old, incoming)
        assertEquals(41, merged.attachments.size)
        assertEquals(merged, Draft.fromJson(merged.json()))
        assertEquals(40, old.attachments.size); assertEquals("keep", old.text)
        assertEquals(1, incoming.attachments.size)
    }
    @Test fun persistedShareKeepsBatchIdentityAndAllInputTypes() {
        val entry = SharedDraft(draft = Draft("链接和文字", listOf(Attachment("/workspace/photo.png", "photo.png", "image/png", 14))))
        assertEquals(entry, SharedDraft.fromJson(entry.json()))
    }
}

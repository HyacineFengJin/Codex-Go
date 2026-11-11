package dev.codex.go

import dev.codex.go.protocol.obj
import dev.codex.go.runtime.Session
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    @Test fun jsonNullTitleFallsBackToActualPreview() {
        val session = Session.fromJson(obj("id" to "thread", "name" to null, "preview" to "Integration test"))
        assertEquals("Integration test", session.title)
    }
    @Test fun literalNullTitleIsStillAValidUserName() {
        val session = Session.fromJson(obj("id" to "thread", "name" to "null", "preview" to "old title"))
        assertEquals("null", session.title)
    }
    @Test fun emptyThreadUsesReadableDefault() {
        assertEquals("新对话", Session.fromJson(obj("id" to "thread", "name" to null, "preview" to null)).title)
    }
}

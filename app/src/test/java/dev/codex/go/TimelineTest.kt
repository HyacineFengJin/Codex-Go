package dev.codex.go

import dev.codex.go.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun completionReplacesStreamingAndIgnoresLateDelta() {
        val timeline = Timeline()
        timeline.event("item/agentMessage/delta", obj("itemId" to "a", "delta" to "Hel"))
        timeline.event("item/agentMessage/delta", obj("itemId" to "a", "delta" to "lo"))
        timeline.event("item/completed", obj("item" to obj("id" to "a", "type" to "agentMessage", "text" to "Hello!")))
        timeline.event("item/agentMessage/delta", obj("itemId" to "a", "delta" to " duplicate"))
        assertEquals(listOf(ChatItem("a", "assistant", "Hello!")), timeline.snapshot())
    }
    @Test fun echoedUserMessageAndCompletedToolAreNotDuplicated() {
        val timeline = Timeline(); timeline.localUser("do it")
        timeline.event("item/started", obj("item" to obj("id" to "u", "type" to "userMessage", "content" to array(obj("type" to "text", "text" to "do it")))))
        timeline.event("item/completed", obj("item" to obj("id" to "u", "type" to "userMessage", "content" to array(obj("type" to "text", "text" to "do it")))))
        timeline.event("item/started", obj("item" to obj("id" to "c", "type" to "commandExecution", "command" to "ls")))
        timeline.event("item/completed", obj("item" to obj("id" to "c", "type" to "commandExecution", "command" to "ls", "aggregatedOutput" to "README.md")))
        assertEquals(2, timeline.snapshot().size)
        assertTrue(timeline.snapshot().last().text.contains("README.md"))
        assertFalse(timeline.snapshot().last().running)
    }
    @Test fun restoredToolNeverStoresScreenImagePayload() {
        val timeline = Timeline()
        timeline.restore(listOf(obj("id" to "screen", "type" to "dynamicToolCall", "tool" to "phone_observe", "contentItems" to array(obj("type" to "inputImage", "imageUrl" to "data:image/png;base64,private-screen")))))
        assertFalse(timeline.snapshot().toString().contains("private-screen"))
    }
    @Test fun failedCommandKeepsFailureAndExitCode() {
        val timeline = Timeline()
        timeline.event("item/completed", obj("item" to obj("id" to "bad", "type" to "commandExecution", "command" to "false", "status" to "failed", "exitCode" to 1)))
        val item = timeline.snapshot().single()
        assertTrue(item.failed); assertTrue(item.text.contains("退出码：1")); assertFalse(item.running)
    }
}

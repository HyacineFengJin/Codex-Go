package dev.codex.go

import dev.codex.go.files.*
import dev.codex.go.protocol.*
import dev.codex.go.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReadingStateTest {
    private val a = Project(UUID.randomUUID().toString(), "A")
    private val b = Project(UUID.randomUUID().toString(), "B")
    private val record = TaskRecord("task-a")
    private val owner = EngineState(project = a, threadId = "a", title = "A task", busy = true,
        turnId = "turn-a", draft = Draft("A next input"), feed = listOf(ChatItem("a1", "assistant", "stream A", running = true)),
        prompts = listOf(ClientPrompt(1, "approval", obj())), phoneTaskActive = true, phonePaused = true)
    private val reading = ReadingConversation(b, "b", "B history", feed = listOf(ChatItem("b1", "assistant", "saved B")), draft = Draft("B draft"))

    @Test fun browsingKeepsGlobalBusyButSeparatesInputsApprovalsAndPhoneControls() {
        val view = owner.forReading(reading, record, 2)
        assertTrue(view.busy); assertTrue(view.browsing)
        assertEquals(b, view.project); assertEquals("b", view.threadId)
        assertEquals(reading.feed, view.feed); assertEquals("B draft", view.draft.text)
        assertTrue(view.prompts.isEmpty()); assertNull(view.turnId)
        assertFalse(view.phoneTaskActive); assertFalse(view.phonePaused)
        assertEquals("a", view.runningTask!!.threadId); assertEquals(a, view.runningTask.project)
        assertEquals(1, view.runningTask.waiting); assertTrue(view.runningTask.phonePaused)
        assertEquals(2, view.conversationRequest)
    }
    @Test fun ownerStreamNeverChangesTheVisibleHistoryOrItsDraft() {
        val before = owner.forReading(reading, record, 0)
        val after = owner.copy(feed = owner.feed + ChatItem("a2", "assistant", "A continued"), draft = Draft("A new draft")).forReading(reading, record, 0)
        assertEquals(before, after)
        assertEquals("A next input", owner.draft.text)
    }
    @Test fun backgroundFailureKeepsViewedDraftAndQueueWhileUpdatingTaskSummary() {
        val view = reading.copy(queue = listOf(PendingMessage("b-queue", Draft("B queued"))))
        val failed = owner.copy(busy = false, failedSubmission = Draft("A failed"), failure = TaskFailure(FailureKind.Network, "A failure"), prompts = emptyList())
            .forReading(view, record.copy(outcome = TaskOutcome.Failed), 0)
        assertEquals("B draft", failed.draft.text); assertEquals(view.queue, failed.queue)
        assertNull(failed.failure); assertNull(failed.failedSubmission)
        assertEquals(TaskOutcome.Failed, failed.runningTask!!.outcome); assertFalse(failed.busy)
    }
    @Test fun returningRestoresOwnerStateWithoutMixingViewedDraft() {
        val returned = owner.forReading(null, record, 0)
        assertFalse(returned.browsing); assertEquals(owner.draft, returned.draft)
        assertEquals(owner.prompts, returned.prompts); assertEquals(owner.feed, returned.feed)
        assertEquals(owner.project, returned.project); assertEquals(owner.turnId, returned.turnId)
    }
    @Test fun taskActionsRejectMissingAndPreviousTaskTokens() {
        assertTrue(record.accepts("task-a")); assertFalse(record.accepts(null))
        assertFalse(TaskRecord("task-next").accepts(record.token))
    }
}

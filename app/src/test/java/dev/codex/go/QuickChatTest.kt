package dev.codex.go

import dev.codex.go.runtime.*
import dev.codex.go.files.*
import org.junit.Assert.*
import org.junit.Test

class QuickChatTest {
    private val target = QuickTarget("project-a", "thread-a", "epoch", 4)
    private val ready = EngineState(phase = EnginePhase.Ready)
    private fun decide(state: EngineState = ready, snapshot: QuickTarget? = target, unlocked: Boolean = true, notification: Boolean = true) = quickAction(snapshot, target, state, unlocked, notification)
    @Test fun oldProjectThreadProcessAndGenerationCannotReceiveTheMessage() {
        for (old in listOf(null, target.copy(project = "other"), target.copy(thread = null, revision = -1), target.copy(thread = "other"), target.copy(epoch = "old"), target.copy(generation = 3), target.copy(revision = -1))) assertEquals(QuickAction.Hold, decide(snapshot = old))
    }
    @Test fun firstNativeIdAllocationKeepsTheSameInputDestination() {
        assertEquals(QuickAction.Send, decide(snapshot = target.copy(thread = null)))
        assertEquals(QuickAction.Hold, quickAction(target, target.copy(thread = null), ready, true, true))
    }
    @Test fun readyConversationSendsAndBusyConversationQueues() {
        assertEquals(QuickAction.Send, decide())
        assertEquals(QuickAction.Queue, decide(ready.copy(busy = true)))
    }
    @Test fun fullQueueHoldsInsteadOfDroppingOrInterruptingInput() {
        val queue = List(10) { PendingMessage(draft = Draft("message $it")) }
        assertEquals(QuickAction.Hold, decide(ready.copy(busy = true, queue = queue)))
        assertEquals(QuickAction.Queue, decide(ready.copy(busy = true, queue = queue.dropLast(1))))
    }
    @Test fun offlineLockedAndMaintenanceStatesRequireReview() {
        assertEquals(QuickAction.Hold, decide(unlocked = false))
        for (blocked in listOf(ready.copy(phase = EnginePhase.Idle), ready.copy(networkAvailable = false), ready.copy(importing = true), ready.copy(loadingHistory = true), ready.copy(accountBusy = true), ready.copy(sessionMutation = "changing"))) assertEquals(QuickAction.Hold, decide(blocked))
    }
    @Test fun disablingNotificationRepliesAlsoInvalidatesAnAlreadyOpenReply() {
        assertEquals(QuickAction.Hold, decide(ready.copy(quickReplyEnabled = false)))
        assertEquals(QuickAction.Send, decide(ready.copy(quickReplyEnabled = false), notification = false))
    }
    @Test fun rescuedInputKeepsItsTextAndDestinationAcrossSerialization() {
        val message = QuickMessage(text = "你好\ncode `x`", destination = "A · 对话", source = "通知")
        assertEquals(message, QuickMessage.fromJson(message.json()))
    }
}

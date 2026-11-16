package dev.codex.go

import dev.codex.go.protocol.*
import dev.codex.go.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun structuredStatusBeatsUntrustedMessageText() {
        val auth = TaskFailure.from(obj("message" to "request failed", "codexErrorInfo" to obj("httpConnectionFailed" to obj("httpStatusCode" to 401))))
        assertEquals(FailureKind.Authentication, auth.kind)
        val rate = TaskFailure.from(obj("message" to "tool quoted unauthorized", "codexErrorInfo" to "rateLimitExceeded"))
        assertEquals(FailureKind.RateLimit, rate.kind)
        val forbidden = TaskFailure.from(obj("message" to "forbidden", "codexErrorInfo" to obj("httpConnectionFailed" to obj("httpStatusCode" to 403))))
        assertEquals(FailureKind.Configuration, forbidden.kind)
    }
    @Test fun streamFailureRetainsNativeRetryState() {
        val failure = TaskFailure.from(obj("message" to "stream disconnected", "codexErrorInfo" to obj("responseStreamDisconnected" to obj("httpStatusCode" to null))), true)
        assertEquals(FailureKind.Network, failure.kind)
        assertTrue(failure.willRetry)
        assertTrue(failure.hint.contains("正在重试"))
    }
    @Test fun contextAndUnknownCodesRemainDistinct() {
        assertEquals(FailureKind.Context, TaskFailure.from(obj("message" to "context exceeded", "codexErrorInfo" to "contextWindowExceeded")).kind)
        assertEquals(FailureKind.Other, TaskFailure.from(obj("message" to "quoted invalid api key", "codexErrorInfo" to "futurePolicyBlock")).kind)
    }
    @Test fun modelOnlySendsAdvertisedEfforts() {
        val model = Model.fromJson(obj("id" to "id", "model" to "actual-model", "displayName" to "Model", "defaultReasoningEffort" to "medium", "supportedReasoningEfforts" to array(obj("reasoningEffort" to "medium"), obj("reasoningEffort" to "high"))))
        assertEquals("actual-model", model.id)
        assertEquals("high", model.effort("high"))
        assertEquals("medium", model.effort("unavailable"))
        assertEquals("medium", model.effort(null))
        assertNull(Model.fromJson(obj("id" to "legacy", "defaultReasoningEffort" to "high")).effort("high"))
    }
    @Test fun reviewDependsOnCompleteContentRatherThanDiffLength() {
        fun review(diff: String?) = Timeline().apply {
            event("item/started", obj("item" to obj("id" to "patch", "type" to "fileChange", "changes" to array(obj("path" to "/workspace/file.txt", "diff" to diff)))))
        }.snapshot().single()
        assertTrue(review("+hello").reviewComplete)
        assertFalse(review(null).reviewComplete)
        assertFalse(review("").reviewComplete)
        assertTrue(review("+".repeat(64_001)).reviewComplete)
        val empty = Timeline().apply { event("item/started", obj("item" to obj("id" to "empty", "type" to "fileChange", "changes" to array(obj("path" to "/workspace/empty.txt", "kind" to obj("type" to "add"), "diff" to ""))))) }.snapshot().single()
        assertTrue(empty.reviewComplete)
        assertTrue(empty.text.contains("新增空文件"))
    }
    @Test fun endedTurnCannotLeaveToolsRunning() {
        val timeline = Timeline()
        timeline.event("item/started", obj("item" to obj("id" to "command", "type" to "commandExecution", "command" to "work")))
        timeline.finish(failed = true)
        assertFalse(timeline.snapshot().single().running)
        assertTrue(timeline.snapshot().single().failed)
    }
}

package dev.codex.go.runtime

import dev.codex.go.files.*
import dev.codex.go.protocol.ChatItem

/** Execution stays in EngineState; this snapshot is only a reading/editing destination. */
data class ReadingConversation(
    val project: Project,
    val threadId: String? = null,
    val title: String = "新对话",
    val feed: List<ChatItem> = emptyList(),
    val draft: Draft = Draft(),
    val queue: List<PendingMessage> = emptyList(),
    val retry: Draft? = null,
    val loading: Boolean = false,
    val archived: Boolean = false,
)
enum class TaskOutcome { Running, Complete, Stopped, Failed, Unconfirmed }
data class TaskRecord(val token: String, val outcome: TaskOutcome = TaskOutcome.Running) {
    fun accepts(candidate: String?) = candidate != null && candidate == token
}
data class RunningTask(
    val token: String, val project: Project, val threadId: String?, val title: String,
    val busy: Boolean, val waiting: Int, val phonePaused: Boolean, val outcome: TaskOutcome,
)

fun EngineState.forReading(reading: ReadingConversation?, task: TaskRecord?, request: Int): EngineState {
    val summary = task?.let { RunningTask(it.token, project, threadId, title, busy, prompts.size, phonePaused, it.outcome) }
    val base = copy(runningTask = summary, conversationRequest = request)
    if (reading == null) return base
    return base.copy(
        browsing = true, project = reading.project, threadId = reading.threadId, title = reading.title,
        feed = reading.feed, draft = reading.draft, queue = reading.queue, queuePaused = true,
        failedSubmission = reading.retry,
        failure = if (reading.retry != null) TaskFailure(FailureKind.Engine, "这段对话有待核对的输入，进入对话后检查已有结果。") else null,
        loadingHistory = reading.loading, turnId = null, steering = false,
        selectedModel = "", selectedEffort = null, effectiveModel = null, effectiveEffort = null,
        prompts = emptyList(), phoneTaskActive = false, phonePaused = false, handoffReason = null,
    )
}

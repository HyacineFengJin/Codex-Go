package dev.codex.go.runtime

import dev.codex.go.protocol.ChatItem
import dev.codex.go.protocol.stringOrNull
import org.json.JSONObject
import dev.codex.go.files.Draft
import dev.codex.go.files.SharedDraft
import dev.codex.go.files.Project
import java.util.UUID

enum class EnginePhase { Idle, Preparing, Ready, Failed }
data class Session(val id: String, val title: String, val preview: String, val updatedAt: Long, val cwd: String = "/workspace") {
    companion object {
        fun fromJson(value: JSONObject): Session {
            val preview = value.stringOrNull("preview").orEmpty()
            return Session(value.getString("id"), value.stringOrNull("name") ?: preview.take(40).ifBlank { "新对话" }, preview, value.optLong("updatedAt"), value.stringOrNull("cwd") ?: "/workspace")
        }
    }
}
data class DeviceLogin(val id: String, val code: String, val url: String, val provider: String = "ChatGPT", val requiresCode: Boolean = false)
data class ClientPrompt(val id: Any, val method: String, val params: JSONObject)
enum class SendMode { Steer, Queue, Interrupt }
data class PendingMessage(val id: String = UUID.randomUUID().toString(), val draft: Draft) {
    fun json() = JSONObject().put("id", id).put("draft", draft.json())
    companion object { fun fromJson(value: JSONObject) = PendingMessage(value.getString("id"), Draft.fromJson(value.getJSONObject("draft"))) }
}
data class EngineState(
    val phase: EnginePhase = EnginePhase.Idle,
    val autoPrepare: Boolean = true,
    val status: String = "尚未启动",
    val account: String = "未登录 ChatGPT",
    val signedIn: Boolean = false,
    val login: DeviceLogin? = null,
    val loginPending: Boolean = false,
    val accountBusy: Boolean = false,
    val startupStage: String? = null,
    val exportingDiagnostics: Boolean = false,
    val notice: String? = null,
    val configurationWarning: String? = null,
    val configuration: ConfigurationStatus = ConfigurationStatus(),
    val shares: List<SharedDraft> = emptyList(),
    val shareRequest: Int = 0,
    val quickRequest: Int = 0,
    val chatOverlayEnabled: Boolean = false,
    val quickReplyEnabled: Boolean = true,
    val quickMessages: List<QuickMessage> = emptyList(),
    val quickFeedback: String = "",
    val threadId: String? = null,
    val title: String = "新对话",
    val project: Project = Project.DEFAULT,
    val projects: List<Project> = listOf(Project.DEFAULT),
    val turnId: String? = null,
    val busy: Boolean = false,
    val browsing: Boolean = false,
    val runningTask: RunningTask? = null,
    val conversationRequest: Int = 0,
    val loadingHistory: Boolean = false,
    val feed: List<ChatItem> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val sessionsCursor: String? = null,
    val sessionQuery: String = "",
    val archivedSessions: Boolean = false,
    val sessionsLoading: Boolean = false,
    val sessionsScanned: Int = 0,
    val sessionsError: String? = null,
    val sessionMutation: String? = null,
    val models: List<Model> = emptyList(),
    val selectedModel: String = "",
    val selectedEffort: String? = null,
    val effectiveModel: String? = null,
    val effectiveEffort: String? = null,
    val failure: TaskFailure? = null,
    val networkAvailable: Boolean = true,
    val refreshingAccount: Boolean = false,
    val prompts: List<ClientPrompt> = emptyList(),
    val phoneEnabled: Boolean = false,
    val phonePreRead: Boolean = false,
    val phoneModelPreRead: Boolean = false,
    val phonePreReadModel: String = dev.codex.go.phone.PreReadPolicy.DEFAULT_MODEL,
    val historyCompression: Boolean = false,
    val historyImageStatus: String = "未启用历史图片压缩",
    val historyImageStats: dev.codex.go.history.HistoryImageStats = dev.codex.go.history.HistoryImageStats(),
    val shellEnabled: Boolean = false,
    val shellAccess: dev.codex.go.shell.ShellAccess = dev.codex.go.shell.ShellAccess(),
    val adbStatus: String = "尚未连接无线调试",
    val adbConnected: Boolean = false,
    val adbSearching: Boolean = false,
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val draft: Draft = Draft(),
    val importing: Boolean = false,
    val workspaceRevision: Int = 0,
    val queue: List<PendingMessage> = emptyList(),
    val queuePaused: Boolean = true,
    val steering: Boolean = false,
    val failedSubmission: Draft? = null,
    val phoneTaskActive: Boolean = false,
    val phonePaused: Boolean = false,
    val engine: AgentEngine = AgentEngine.Codex,
    val handoffReason: String? = null,
    val phoneAction: String = "准备操作手机",
)

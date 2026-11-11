package dev.codex.go.runtime

import dev.codex.go.protocol.RpcError
import dev.codex.go.protocol.obj
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

enum class FailureKind { Authentication, Network, RateLimit, Model, Context, Configuration, Engine, Other }
data class TaskFailure(val kind: FailureKind, val details: String, val willRetry: Boolean = false) {
    val title: String get() = when (kind) {
        FailureKind.Authentication -> "账户验证未通过"
        FailureKind.Network -> "连接暂时中断"
        FailureKind.RateLimit -> "服务暂时受限"
        FailureKind.Model -> "当前模型不可用"
        FailureKind.Context -> "对话已达到上下文限制"
        FailureKind.Configuration -> "请求或配置需要调整"
        FailureKind.Engine -> "本机引擎已断开"
        FailureKind.Other -> "这次任务没有完成"
    }
    val hint: String get() = if (willRetry) "Codex 正在重试，可以继续等待或停止任务。" else when (kind) {
        FailureKind.Authentication -> "刷新账户状态或重新登录，再手动重试。"
        FailureKind.Network -> "连接恢复后请检查已有结果，再手动重试。任务可能已完成部分操作。"
        FailureKind.RateLimit -> "稍后重试，或检查账户的使用额度。"
        FailureKind.Model -> "选择账户可用的模型，再手动重试。"
        FailureKind.Context -> "请在新对话中继续，或缩短输入。"
        FailureKind.Configuration -> "查看错误详情，调整模型或设置后重试。"
        FailureKind.Engine -> "重新启动本机环境后，检查已有结果再继续。"
        FailureKind.Other -> "查看错误详情和已有结果，再决定是否重试。"
    }
    companion object {
        fun from(error: JSONObject, willRetry: Boolean = false): TaskFailure {
            val info = error.opt("codexErrorInfo")
            val tag = when (info) { is String -> info; is JSONObject -> info.keys().asSequence().firstOrNull().orEmpty(); else -> "" }
            val status = (info as? JSONObject)?.optJSONObject(tag)?.optInt("httpStatusCode", 0) ?: 0
            val message = error.optString("message", "任务未完成")
            // Structured status wins over message text, which can contain quoted tool output.
            val kind = when {
                tag == "unauthorized" || status == 401 -> FailureKind.Authentication
                tag in setOf("usageLimitExceeded", "rateLimitExceeded", "sessionBudgetExceeded", "serverOverloaded", "flexUnavailable") || status == 429 || status == 503 -> FailureKind.RateLimit
                tag == "modelNotFound" -> FailureKind.Model
                tag == "contextWindowExceeded" -> FailureKind.Context
                status in setOf(400, 403, 404) -> FailureKind.Configuration
                tag in setOf("httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected", "responseTooManyFailedAttempts") -> FailureKind.Network
                tag == "badRequest" -> FailureKind.Configuration
                tag.isNotEmpty() && tag != "other" -> FailureKind.Other
                Regex("(?i)(unauthorized|authentication failed|invalid api key|token.{0,20}(expired|revoked)|not (logged|signed) in|401 unauthorized)").containsMatchIn(message) -> FailureKind.Authentication
                Regex("(?i)(rate limit|usage limit|quota exceeded|429 too many)").containsMatchIn(message) -> FailureKind.RateLimit
                Regex("(?i)(network|connection (reset|refused|closed)|stream disconnected|timed? out|dns|failed to (connect|send request))").containsMatchIn(message) -> FailureKind.Network
                else -> FailureKind.Other
            }
            return TaskFailure(kind, message.take(8000), willRetry)
        }
        fun from(error: Throwable): TaskFailure {
            if (error is RpcError) return from(JSONObject((error.data?.optJSONObject("error") ?: error.data ?: obj()).toString()).apply { if (!has("message")) put("message", error.message) })
            if (error is IOException || error is SocketTimeoutException || error is kotlinx.coroutines.TimeoutCancellationException) return TaskFailure(FailureKind.Network, error.message.orEmpty().take(8000))
            return from(obj("message" to error.message))
        }
    }
}

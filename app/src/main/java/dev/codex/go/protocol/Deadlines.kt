package dev.codex.go.protocol

import kotlinx.coroutines.withTimeout

/** Zero means no requested deadline; explicit cancellation still propagates. */
suspend fun <T> withOptionalTimeout(timeoutMs: Long, block: suspend () -> T): T {
    require(timeoutMs >= 0) { "Timeout cannot be negative" }
    return if (timeoutMs == 0L) block() else withTimeout(timeoutMs) { block() }
}

fun replyTimeout(timeoutMs: Long): Long {
    require(timeoutMs >= 0) { "Timeout cannot be negative" }
    return if (timeoutMs == 0L) 0 else if (timeoutMs > Long.MAX_VALUE - 5000) Long.MAX_VALUE else timeoutMs + 5000
}

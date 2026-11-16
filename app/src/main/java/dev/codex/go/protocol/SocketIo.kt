package dev.codex.go.protocol

import java.net.Socket
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Interrupting a JVM socket thread is insufficient; cancellation must close its socket. */
suspend fun <T> socketIo(socket: Socket, block: () -> T): T = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        launch(Dispatchers.IO) {
            try { val result = block(); if (continuation.isActive) continuation.resume(result) }
            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
}

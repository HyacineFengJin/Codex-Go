package dev.codex.go

import dev.codex.go.protocol.socketIo
import kotlinx.coroutines.*
import java.net.*
import org.junit.Assert.*
import org.junit.Test

class SocketIoTest {
    @Test fun cancellingBlockedSocketWriteClosesItAndJoinsWorker() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                listener.accept().use {
                    socket.sendBufferSize = 1024
                    val entered = CompletableDeferred<Unit>()
                    val job = launch {
                        socketIo(socket) {
                            entered.complete(Unit)
                            val chunk = ByteArray(64 * 1024)
                            while (true) socket.getOutputStream().write(chunk)
                        }
                    }
                    withTimeout(3000) { entered.await() }
                    delay(50); assertTrue(job.isActive)
                    withTimeout(2000) { job.cancelAndJoin() }
                    assertTrue(socket.isClosed)
                }
            }
        }
    }
}

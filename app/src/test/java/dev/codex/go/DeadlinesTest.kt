package dev.codex.go

import dev.codex.go.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeadlinesTest {
    @Test fun noDeadlineSurvivesLongWaitAndStillCancels() = runTest {
        val waiting = launch { withOptionalTimeout(0) { awaitCancellation() } }
        runCurrent(); advanceTimeBy(3_600_001)
        assertTrue(waiting.isActive)
        waiting.cancelAndJoin(); assertTrue(waiting.isCancelled)
        assertEquals(0L, replyTimeout(0))
    }
    @Test fun callerDeadlineIsRespectedWithoutClampingOrOverflow() = runTest {
        val waiting = async { runCatching { withOptionalTimeout(180_000) { delay(180_001) } } }
        runCurrent(); advanceTimeBy(120_001); assertTrue(waiting.isActive)
        advanceUntilIdle(); assertTrue(waiting.await().exceptionOrNull() is TimeoutCancellationException)
        assertEquals(185_000L, replyTimeout(180_000))
        assertEquals(Long.MAX_VALUE, replyTimeout(Long.MAX_VALUE))
    }
}

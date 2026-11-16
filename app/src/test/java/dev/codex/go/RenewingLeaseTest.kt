package dev.codex.go

import dev.codex.go.runtime.RenewingLease
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenewingLeaseTest {
    @Test fun quietLongTaskKeepsItsLeaseAndIdleReleasesIt() = runTest {
        val acquisitions = mutableListOf<Long>(); var held = false
        val lease = RenewingLease(this, 1_800_000, 900_000, { acquisitions += it; held = true }, { held = false })
        lease.setActive(true); runCurrent(); advanceTimeBy(3_600_001)
        assertEquals(5, acquisitions.size); assertTrue(held)
        lease.setActive(false); runCurrent(); val count = acquisitions.size
        advanceTimeBy(3_600_001); assertEquals(count, acquisitions.size); assertFalse(held)
    }
    @Test fun immediateStopAndRestartCannotReleaseTheNewLease() = runTest {
        var held = false
        val lease = RenewingLease(this, 1000, 500, { held = true }, { held = false })
        lease.setActive(true); runCurrent(); lease.setActive(false); lease.setActive(true); runCurrent()
        assertTrue(held); lease.setActive(false); runCurrent(); assertFalse(held)
    }
    @Test fun parentCancellationReleasesTheLease() = runTest {
        val owner = CoroutineScope(coroutineContext + Job()); var held = false
        val lease = RenewingLease(owner, 1000, 500, { held = true }, { held = false })
        lease.setActive(true); runCurrent(); owner.cancel(); runCurrent(); assertFalse(held)
    }
    @Test fun cancellationBeforeWorkerStartsAlsoReleasesTheLease() = runTest {
        val owner = CoroutineScope(coroutineContext + Job()); var held = false
        val lease = RenewingLease(owner, 1000, 500, { held = true }, { held = false })
        lease.setActive(true); owner.cancel(); runCurrent(); assertFalse(held)
    }
}

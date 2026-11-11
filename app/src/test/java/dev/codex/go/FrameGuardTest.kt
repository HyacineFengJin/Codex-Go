package dev.codex.go

import dev.codex.go.phone.FrameGuard
import org.junit.Assert.*
import org.junit.Test

class FrameGuardTest {
    @Test fun rotationAndExpiryInvalidateCachedImageEvidence() {
        var time = 1000L
        val frames = FrameGuard { time }
        val token = frames.record(1080, 2400, 0)
        frames.requireCurrent(token, 0, 1079 to 2399)
        assertThrows(IllegalArgumentException::class.java) { frames.requireCurrent(token, 0, 1080 to 0) }
        assertThrows(IllegalArgumentException::class.java) { frames.requireCurrent(token, 1, 10 to 10) }
        time += 30_001
        assertThrows(IllegalArgumentException::class.java) { frames.requireCurrent(token, 0, 10 to 10) }
    }
    @Test fun clearedImageEvidenceCannotBeReadWithAnOldToken() {
        val frames = FrameGuard()
        val token = frames.record(500, 700, 0)
        frames.invalidate()
        assertThrows(IllegalArgumentException::class.java) { frames.requireCurrent(token, 0, 20 to 20) }
    }
}

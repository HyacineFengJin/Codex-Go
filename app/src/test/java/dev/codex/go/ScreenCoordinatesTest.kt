package dev.codex.go

import dev.codex.go.phone.ScreenCoordinates
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenCoordinatesTest {
    @Test fun fullDisplayEdgesAreValidButOutsideCoordinatesAreRejected() {
        ScreenCoordinates.requireWithin(1080, 2400, 0 to 0, 1079 to 2399)
        for (point in listOf(-1 to 0, 0 to -1, 1080 to 0, 0 to 2400)) {
            assertThrows(IllegalArgumentException::class.java) { ScreenCoordinates.requireWithin(1080, 2400, point) }
        }
    }
    @Test fun swipeChecksBothEndpointsAgainstCurrentDisplayDimensions() {
        ScreenCoordinates.requireWithin(2400, 1080, 2399 to 1079, 1080 to 0)
        assertThrows(IllegalArgumentException::class.java) { ScreenCoordinates.requireWithin(2400, 1080, 0 to 0, 0 to 1080) }
        assertThrows(IllegalArgumentException::class.java) { ScreenCoordinates.requireWithin(0, 1080, 0 to 0) }
    }
}

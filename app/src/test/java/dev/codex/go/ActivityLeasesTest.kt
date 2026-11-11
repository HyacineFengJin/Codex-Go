package dev.codex.go

import dev.codex.go.phone.ActivityLeases
import dev.codex.go.phone.PhoneActivity
import org.junit.Assert.*
import org.junit.Test

class ActivityLeasesTest {
    @Test fun overlappingOperationsKeepTheWindowUntilTheLastExit() {
        val leases = ActivityLeases()
        val first = leases.begin(); val second = leases.begin()
        leases.end(first); assertTrue(leases.active)
        leases.end(first); assertTrue(leases.active)
        leases.end(second); assertFalse(leases.active)
    }
    @Test fun oldFinallyAfterPauseCannotDismissANewOperation() {
        val leases = ActivityLeases(); val cancelled = leases.begin()
        leases.clear(); assertFalse(leases.active)
        val current = leases.begin()
        leases.end(cancelled); assertTrue(leases.active)
        leases.end(current); assertFalse(leases.active)
    }
    @Test fun onlyLiveScreenToolsHaveAnIndicator() {
        assertTrue(ActivityLeases.live("phone_observe")); assertTrue(ActivityLeases.live("phone_swipe"))
        assertFalse(ActivityLeases.live("phone_read_image")); assertFalse(ActivityLeases.live("phone_handoff"))
        assertFalse(ActivityLeases.live("android_shell")); assertFalse(ActivityLeases.live("exec_command"))
    }
    @Test fun readOnlyOperationsHaveDistinctLabels() {
        assertEquals(PhoneActivity.Viewing, PhoneActivity.from("phone_observe"))
        assertEquals(PhoneActivity.Reading, PhoneActivity.from("phone_pre_read"))
        for (tool in listOf("phone_tap", "phone_swipe", "phone_type", "phone_key", "phone_open_app")) assertEquals(PhoneActivity.Using, PhoneActivity.from(tool))
    }
    @Test fun overlappingControlTakesPriorityThenRestoresReading() {
        val leases = ActivityLeases()
        val viewing = leases.begin(PhoneActivity.Viewing)
        val control = leases.begin(PhoneActivity.Using)
        val reading = leases.begin(PhoneActivity.Reading)
        assertEquals(PhoneActivity.Using, leases.activity)
        leases.end(control); assertEquals(PhoneActivity.Reading, leases.activity)
        leases.end(reading); assertEquals(PhoneActivity.Viewing, leases.activity)
        leases.end(viewing); assertNull(leases.activity)
    }
}

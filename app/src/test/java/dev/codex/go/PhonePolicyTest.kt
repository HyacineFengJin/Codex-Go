package dev.codex.go

import dev.codex.go.phone.PhonePolicy
import org.junit.Assert.*
import org.junit.Test

class PhonePolicyTest {
    @Test fun lockedScreensNeverPermitObservationOrMutation() {
        assertNotNull(PhonePolicy.handoff("phone_observe", null, true))
        assertNotNull(PhonePolicy.handoff("phone_read_image", null, true))
        assertNotNull(PhonePolicy.handoff("phone_tap", "com.android.chrome", true))
    }
    @Test fun settingsPaymentAndAgentUiHandBackWhileNavigationRemainsAvailable() {
        for (app in listOf("dev.codex.go", "com.android.settings", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.eg.android.AlipayGphone", "com.example.bank")) {
            assertNotNull(PhonePolicy.handoff("phone_type", app, false))
            assertNull(PhonePolicy.handoff("phone_key", app, false))
        }
        assertNotNull(PhonePolicy.handoff("phone_tap", null, false))
        assertNull(PhonePolicy.handoff("phone_tap", "com.android.chrome", false))
        assertNull(PhonePolicy.handoff("phone_observe", "com.android.settings", false))
        assertNull(PhonePolicy.handoff("phone_read_image", "com.android.settings", false))
    }
}

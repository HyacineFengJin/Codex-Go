package dev.codex.go

import dev.codex.go.runtime.AppUpdates
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    @Test fun detectsPreviewWithAnApkUsingNumericVersionOrder() {
        val releases = JSONArray("""
            [
              {"tag_name":"v2.0.0","draft":true,"assets":[{"name":"codex-go.apk"}]},
              {"tag_name":"v1.0.0","assets":[]},
              {"tag_name":"v0.100.0-beta","assets":[{"name":"codex-go.apk"}]},
              {"tag_name":"v0.9.2","assets":[{"name":"codex-go.apk"}]},
              {"tag_name":"v0.10.0","prerelease":true,"assets":[{"name":"codex-go.apk"}]}
            ]
        """.trimIndent())
        assertEquals("0.10.0", AppUpdates.newerVersion(releases, "0.9.1"))
        assertNull(AppUpdates.newerVersion(releases, "0.10.0"))
        assertNull(AppUpdates.newerVersion(releases, "0.22.0"))
    }
}

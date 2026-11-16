package dev.codex.go

import dev.codex.go.protocol.obj
import dev.codex.go.runtime.NativeToolOutput
import org.junit.Assert.*
import org.junit.Test

class NativeToolOutputTest {
    @Test fun implicitBudgetDoesNotCutStructuredToolResults() {
        for (config in listOf(obj(), obj("tool_output_token_limit" to org.json.JSONObject.NULL))) {
            assertTrue(NativeToolOutput.configuration(config).getLong("tool_output_token_limit") > 128L * 1024 * 1024)
        }
    }
    @Test fun explicitAccountOrProjectBudgetIsNeverOverridden() {
        for (budget in listOf(0, 500, 250_000)) {
            val config = obj("tool_output_token_limit" to budget, "model_provider" to "custom")
            assertEquals(0, NativeToolOutput.configuration(config).length())
            assertEquals(budget, config.getInt("tool_output_token_limit"))
            assertEquals("custom", config.getString("model_provider"))
        }
    }
}

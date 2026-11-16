package dev.codex.go.runtime

import dev.codex.go.protocol.obj
import org.json.JSONObject

object NativeToolOutput {
    /**
     * The CLI's implicit truncation can cut a screen report in the middle of JSON.
     * Use a budget above the transport's memory guard unless the user chose one.
     * Model context limits still apply; this never allocates the budget up front.
     */
    fun configuration(effective: JSONObject): JSONObject =
        if (effective.isNull("tool_output_token_limit")) obj("tool_output_token_limit" to Int.MAX_VALUE) else obj()
}

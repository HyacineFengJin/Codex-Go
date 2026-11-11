package dev.codex.go.runtime

import java.io.IOException
import java.io.InputStream

object RuntimeLogs {
    fun consume(input: InputStream, onLine: (String) -> Unit) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
        } catch (_: IOException) {
            // Stop/Restart closes the child pipes. The RPC channel reports process lifecycle.
        }
    }
}

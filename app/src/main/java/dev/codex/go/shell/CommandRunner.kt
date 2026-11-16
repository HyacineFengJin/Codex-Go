package dev.codex.go.shell

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Shared by the explicit su process and the shell-UID services. No shell interpolation of arguments. */
class CommandRunner(private val shell: String = "/system/bin/sh") {
    private val processes = ConcurrentHashMap<String, Process>()
    private val cancelled = ConcurrentHashMap<String, Long>()
    private class Capture(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        @Volatile var truncated = false
        @Synchronized fun append(buffer: ByteArray, count: Int) {
            val keep = count.coerceAtMost((limit - bytes.size()).coerceAtLeast(0))
            if (keep > 0) bytes.write(buffer, 0, keep)
            if (keep < count) truncated = true
        }
        @Synchronized fun text() = bytes.toString("UTF-8")
    }
    fun execute(id: String, command: String, timeoutMs: Long, root: Boolean = false): String {
        require(command.isNotBlank()) { "命令不能为空" }
        require(timeoutMs >= 0) { "命令超时不能为负数" }
        check(cancelled.remove(id) == null) { "命令已取消" }
        val process = ProcessBuilder(if (root) listOf("su", "-c", command) else listOf(shell, "-c", command)).start()
        if (processes.putIfAbsent(id, process) != null) { process.destroyForcibly(); error("重复命令") }
        if (cancelled.remove(id) != null) { processes.remove(id, process); process.destroyForcibly(); error("命令已取消") }
        val out = Capture(48_000); val err = Capture(12_000)
        fun read(stream: InputStream, capture: Capture) = thread(isDaemon = true, name = "codex-shell-output") {
            runCatching { stream.use { val buffer = ByteArray(4096); while (true) { val count = it.read(buffer); if (count < 0) break; capture.append(buffer, count) } } }
        }
        val stdout = read(process.inputStream, out); val stderr = read(process.errorStream, err)
        try {
            process.outputStream.close()
            val complete = if (timeoutMs == 0L) { process.waitFor(); true } else process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!complete) process.destroyForcibly()
            stdout.join(500); stderr.join(500)
            return JSONObject().put("stdout", out.text()).put("stderr", err.text()).put("exitCode", if (complete) process.exitValue() else 124)
                .put("timedOut", !complete).put("truncated", out.truncated || err.truncated).toString()
        } finally {
            processes.remove(id, process); process.destroyForcibly()
            runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
        }
    }
    fun cancel(id: String) {
        val now = System.currentTimeMillis()
        cancelled.entries.removeAll { now - it.value > 120_000 }
        if (cancelled.size < 1024) cancelled[id] = now
        processes[id]?.destroyForcibly()
    }
    fun close() { processes.values.forEach { it.destroyForcibly() }; processes.clear(); cancelled.clear() }
}

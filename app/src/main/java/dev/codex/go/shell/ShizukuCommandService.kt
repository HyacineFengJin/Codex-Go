package dev.codex.go.shell

/** Instantiated by Shizuku's user-service loader with shell/root identity. */
class ShizukuCommandService : ICommandService.Stub() {
    private val runner = CommandRunner()
    override fun execute(requestId: String, command: String, timeoutMs: Long) = runner.execute(requestId, command, timeoutMs)
    override fun cancel(requestId: String) = runner.cancel(requestId)
    override fun destroy() { runner.close(); kotlin.system.exitProcess(0) }
}

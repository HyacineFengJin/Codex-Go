package dev.codex.go.shell

enum class ShellBackend(val label: String, val detail: String) {
    Wireless("无线调试", "在手机上配对，无需电脑"),
    Wired("有线 ADB", "连接电脑，执行一次启动命令"),
    Shizuku("Shizuku", "使用已启动的 Shizuku 授权"),
    Root("Root", "通过设备的 su 管理器授权"),
}

data class ShellAccess(
    val selected: ShellBackend = ShellBackend.Wireless,
    val ready: Set<ShellBackend> = emptySet(),
    val messages: Map<ShellBackend, String> = emptyMap(),
    val connecting: Set<ShellBackend> = emptySet(),
    val wiredCommand: String = "",
) {
    val connected get() = selected in ready
    fun status(backend: ShellBackend) = messages[backend] ?: if (backend in ready) "已连接" else "尚未连接"
}

package dev.codex.go.i18n

import dev.codex.go.R
import dev.codex.go.files.Project
import dev.codex.go.shell.ShellBackend

val Project.displayName: String get() = if (id == Project.DEFAULT_ID && name == "默认项目") tr(R.string.default_project) else name

val ShellBackend.displayLabel: String get() = when (this) {
    ShellBackend.Wireless -> tr(R.string.wireless_debugging)
    ShellBackend.Wired -> tr(R.string.wired_adb)
    ShellBackend.Shizuku -> "Shizuku"
    ShellBackend.Root -> "Root"
}
val ShellBackend.displayDetail: String get() = metadata(detail)
fun effortLabel(id: String): String = when (id) {
    "none" -> tr(R.string.reasoning_none)
    "minimal" -> tr(R.string.minimal)
    "low" -> tr(R.string.low)
    "medium" -> tr(R.string.medium)
    "high" -> tr(R.string.high)
    "xhigh" -> tr(R.string.very_high)
    "max" -> tr(R.string.maximum)
    else -> id
}

val dev.codex.go.runtime.EngineState.displayTitle: String get() = if (threadId == null) tr(R.string.new_chat_ea4b29) else title

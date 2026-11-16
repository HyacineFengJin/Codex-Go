package dev.codex.go.runtime

/** Persisted engine identity; model IDs and conversations stay in their own engine. */
enum class AgentEngine(val id: String, val label: String) {
    Codex("codex", "Codex"), Antigravity("antigravity", "Antigravity");
    val preferencesName get() = if (this == Codex) "codex_go" else "antigravity_conversations"
    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: Codex }
}

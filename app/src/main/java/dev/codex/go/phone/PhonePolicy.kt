package dev.codex.go.phone

/** Shared by accessibility and ADB Computer Use. Android Shell has its own approval. */
object PhonePolicy {
    fun handoff(tool: String, packageName: String?, locked: Boolean): String? {
        if (locked) return "屏幕已熄灭或锁定。请解锁手机，确认后继续。"
        if (tool in setOf("phone_observe", "phone_read_image", "phone_key", "phone_handoff")) return null
        val app = packageName?.lowercase() ?: return "无法确认当前应用，请你检查屏幕后继续。"
        return when {
            app == "dev.codex.go" -> "Codex Go 自身的操作请由你完成。"
            app == "com.android.settings" || app == "com.android.systemui" || app.endsWith(".permissioncontroller") || app.endsWith(".packageinstaller") -> "系统设置、权限与安装步骤请由你完成。"
            "bank" in app || "alipay" in app || app == "com.tencent.wetype.pay" -> "银行和支付应用请由你操作。"
            else -> null
        }
    }
}

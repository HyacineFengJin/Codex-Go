package dev.codex.go.phone

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Uses the accessibility overlay; ADB-only tasks retain the notification controls. */
class TaskOverlay(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var label: TextView? = null
    private var pause: Button? = null
    private var hidden = 0
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun show(text: String?, paused: Boolean, toggle: () -> Unit, stop: () -> Unit, open: () -> Unit) {
        if (text == null) { close(); return }
        if (root == null) {
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(2), dp(4), dp(2))
                background = GradientDrawable().apply { setColor(Color.argb(242, 37, 40, 45)); cornerRadius = dp(22).toFloat() }
                elevation = dp(8).toFloat()
            }
            label = TextView(service).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 2; contentDescription = tr(R.string.return_to_codex_go_chat) }
            row.addView(label, LinearLayout.LayoutParams(0, dp(48), 1f))
            fun button(title: String): Button = Button(service).apply {
                this.text = title; textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(dp(8), 0, dp(8), 0)
                row.addView(this, LinearLayout.LayoutParams(dp(56), dp(48)))
            }
            pause = button(tr(R.string.pause))
            button(tr(R.string.stop)).setOnClickListener { stop() }
            val params = WindowManager.LayoutParams(dp(328), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; params.y = dp(36); params.title = tr(R.string.codex_go_phone_task)
            runCatching { manager.addView(row, params); root = row }.onFailure { label = null; pause = null }
        }
        label?.text = text
        label?.setOnClickListener { open() }
        pause?.text = if (paused) tr(R.string.continue_label) else tr(R.string.pause)
        pause?.contentDescription = if (paused) tr(R.string.resume_phone_control) else tr(R.string.pause_phone_control)
        pause?.setOnClickListener { toggle() }
        root?.visibility = if (hidden > 0) View.INVISIBLE else View.VISIBLE
    }
    fun hideForTool() { hidden++; root?.visibility = View.INVISIBLE }
    fun restoreAfterTool() { hidden = (hidden - 1).coerceAtLeast(0); if (hidden == 0) root?.visibility = View.VISIBLE }
    fun close() { root?.let { runCatching { manager.removeView(it) } }; root = null; label = null; pause = null }
}

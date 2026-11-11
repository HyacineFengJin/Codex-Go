package dev.codex.go.adb

import dev.codex.go.i18n.*

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.codex.go.R
import dev.codex.go.ui.OverlayStyle

/** Optional persistent input alongside Settings; notification RemoteInput remains available. */
class PairingOverlay(private val context: Context, private val submit: (String, Int, String) -> Unit, private val cancel: () -> Unit, private val unavailable: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var code: EditText? = null
    private var status: TextView? = null
    private var send: Button? = null
    private var session = ""
    private var port = 0
    private var busy = false
    fun update(origin: String, newPort: Int, working: Boolean, message: String) {
        if (!Settings.canDrawOverlays(context)) return
        val awake = context.getSystemService(PowerManager::class.java).isInteractive && !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!awake) { close(); return }
        if (root != null && (origin != session || (port > 0 && port != newPort))) code?.setText("")
        session = origin; port = newPort; busy = working
        if (root == null) show()
        status?.text = message
        send?.isEnabled = port > 0 && !working
        send?.alpha = if (send?.isEnabled == true) 1f else .45f
        send?.text = if (working) tr(R.string.pairing) else tr(R.string.pair)
        code?.isEnabled = !working
    }
    private fun show() {
        val style = OverlayStyle(context)
        fun dp(v: Int) = style.dp(v)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = style.shape(style.surface, 28, true); elevation = dp(10).toFloat()
            isFocusableInTouchMode = true
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(context).apply { setImageResource(R.drawable.codex_brand) }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        heading.addView(style.text(tr(R.string.wireless_pairing), 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(style.icon("×", tr(R.string.cancel_wireless_pairing), cancel), LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(heading)
        status = style.text(tr(R.string.keep_the_system_pairing_code_window_open), 12f, secondary = true)
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(14) })
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        code = EditText(context).apply {
            hint = tr(R.string.six_digit_pairing_code); contentDescription = tr(R.string.floating_pairing_code_input)
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6)); setSingleLine()
            textSize = 22f; letterSpacing = .12f; setTextColor(style.ink); setHintTextColor(style.muted)
            setPadding(dp(14), dp(12), dp(10), dp(12)); background = style.shape(style.inner, 18, true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> if (port > 0 && !busy) submit(session, port, text.toString()); true }
        }
        row.addView(code, LinearLayout.LayoutParams(0, -2, 1f))
        send = style.action(tr(R.string.pair), true) { submit(session, port, code?.text?.toString().orEmpty()) }
        row.addView(send, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(10) }); card.addView(row)
        val bounds = manager.currentWindowMetrics.bounds
        val p = WindowManager.LayoutParams((bounds.width() - dp(24)).coerceAtMost(dp(390)), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(36); title = tr(R.string.codex_go_pairing_input)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        runCatching {
            manager.addView(card, p); root = card; card.requestFocus()
            card.postDelayed({ if (root === card && card.windowVisibility != View.VISIBLE) unavailable() }, 1500)
        }.onFailure { root = null; code = null; status = null; send = null; unavailable() }
    }
    fun close() {
        root?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); runCatching { manager.removeView(it) } }
        root = null; code = null; status = null; send = null
    }
}

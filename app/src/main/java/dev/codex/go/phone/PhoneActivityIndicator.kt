package dev.codex.go.phone

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import dev.codex.go.R
import dev.codex.go.ui.GlassSurface
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** A passive, operation-scoped window. It never owns input or requests a new permission. */
class PhoneActivityIndicator(private val context: Context, private val accessibility: Boolean) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val leases = ActivityLeases()
    private var view: IndicatorView? = null

    fun begin(tool: String): Long {
        checkMainThread()
        val kind = PhoneActivity.from(tool) ?: return 0
        val token = leases.begin(kind)
        if (view == null && (accessibility || Settings.canDrawOverlays(context))) {
            val candidate = IndicatorView(context).apply { activity = leases.activity ?: kind }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "Codex Go phone activity"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                // Android 12+ rejects touches through opaque untrusted app overlays.
                // Our accessibility window is trusted; the ADB-only window stays below the OS limit.
                alpha = if (accessibility) 1f else .75f
            }
            runCatching { manager.addView(candidate, params); view = candidate }
                .onFailure { runCatching { manager.removeViewImmediate(candidate) } }
        }
        view?.let { it.activity = leases.activity ?: kind; it.invalidate() }
        return token
    }

    fun end(token: Long) {
        checkMainThread()
        leases.end(token)
        if (!leases.active) removeWindow()
        else view?.let { it.activity = leases.activity!!; it.invalidate() }
    }

    /** Pause/disconnect invalidates every lease, including nested and cancelled operations. */
    fun close() { checkMainThread(); leases.clear(); removeWindow() }

    suspend fun <T> during(tool: String, block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        val token = begin(tool)
        try {
            // Wait for the first draw, never for a minimum display duration or a fade-out.
            if (token != 0L) view?.awaitDraw()
            block()
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { end(token) } }
    }

    private fun removeWindow() {
        val previous = view; view = null
        if (previous != null) runCatching { manager.removeViewImmediate(previous) }
    }
    private fun checkMainThread() = check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())

    private class IndicatorView(context: Context) : View(context) {
        var activity = PhoneActivity.Using
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); color = Color.rgb(242, 244, 247) }
        private val mark = context.getDrawable(R.drawable.ic_codex_mark)?.mutate()
        private val capsule = GlassSurface(true, 0f, pill = true).apply { fillOpacity = 246 }
        private var safe = Rect()
        private var corners = 8f
        private var drawn = false
        private val waiters = mutableListOf<CancellableContinuation<Unit>>()
        private fun dp(value: Float) = value * resources.displayMetrics.density
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false
            setWillNotDraw(false)
        }
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // Some overlay windows receive already-consumed insets. WindowMetrics retains
            // the display's safe area, including the notch after a rotation.
            val display = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safe = Rect(max(bars.left, display.left), max(bars.top, display.top), max(bars.right, display.right), max(bars.bottom, display.bottom))
            if (Build.VERSION.SDK_INT >= 31) corners = listOf(
                RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT
            ).maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }.toFloat().coerceAtLeast(dp(8f))
            invalidate()
            return insets
        }
        override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); requestApplyInsets(); invalidate() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }
        override fun onDetachedFromWindow() {
            waiters.toList().forEach { if (it.isActive) it.resume(Unit) }; waiters.clear()
            super.onDetachedFromWindow()
        }
        suspend fun awaitDraw() {
            if (drawn) return
            withTimeoutOrNull(500) {
                suspendCancellableCoroutine { continuation ->
                    waiters += continuation
                    continuation.invokeOnCancellation { post { waiters.remove(continuation) } }
                    invalidate()
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.shader = null; paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            val glow = dp(24f)
            val colors = intArrayOf(0x660a6eff, 0x460a6eff, 0x280a6eff, 0x120a6eff, 0x040a6eff, 0x000a6eff)
            val stops = floatArrayOf(0f, .1f, .25f, .45f, .75f, 1f)
            val clip = Path().apply { addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corners, corners, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clip)
            paint.shader = LinearGradient(0f, 0f, glow, 0f, colors, stops, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, glow, height.toFloat(), paint)
            paint.shader = LinearGradient(width.toFloat(), 0f, width - glow, 0f, colors, stops, Shader.TileMode.CLAMP); canvas.drawRect(width - glow, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = LinearGradient(0f, 0f, 0f, glow, colors, stops, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, width.toFloat(), glow, paint)
            paint.shader = LinearGradient(0f, height.toFloat(), 0f, height - glow, colors, stops, Shader.TileMode.CLAMP); canvas.drawRect(0f, height - glow, width.toFloat(), height.toFloat(), paint)
            canvas.restore()
            paint.shader = null; paint.style = Paint.Style.STROKE
            paint.color = Color.argb(240, 46, 166, 255); paint.strokeWidth = dp(1.5f)
            val edge = paint.strokeWidth / 2
            canvas.drawRoundRect(edge, edge, width - edge, height - edge, corners, corners, paint)

            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            val available = (width - safe.left - safe.right - dp(24f)).coerceAtLeast(1f)
            val icon = dp(16f)
            val leading = dp(16f) + icon + dp(8f)
            val trailing = dp(16f)
            val brand = if (context.getSharedPreferences("codex_go", 0).getString("agentEngine", "codex") == "antigravity") "Antigravity" else "Codex"
            val label = android.text.TextUtils.ellipsize(context.getString(activity.labelId).replace("Codex", brand), android.text.TextPaint(text), (available - leading - trailing).coerceAtLeast(1f), android.text.TextUtils.TruncateAt.END).toString()
            val badgeWidth = min(available, text.measureText(label) + leading + trailing)
            val metrics = text.fontMetrics
            val badgeHeight = max(dp(32f), metrics.descent - metrics.ascent + dp(12f))
            val left = safe.left + (width - safe.left - safe.right - badgeWidth) / 2
            val top = (safe.top + dp(8f)).coerceAtMost((height - safe.bottom - badgeHeight).coerceAtLeast(0f))
            // A passive status capsule, kept below the system bar and camera safe area.
            capsule.setBounds(left.toInt(), top.toInt(), (left + badgeWidth).toInt(), (top + badgeHeight).toInt())
            capsule.draw(canvas)
            val iconTop = top + (badgeHeight - icon) / 2
            mark?.let { it.setBounds((left + dp(16f)).toInt(), iconTop.toInt(), (left + dp(16f) + icon).toInt(), (iconTop + icon).toInt()); it.draw(canvas) }
            canvas.drawText(label, left + leading, top + (badgeHeight - metrics.descent - metrics.ascent) / 2, text)
            drawn = true
            // Resume after the draw traversal, so work cannot start before the window is drawn.
            val ready = waiters.toList(); waiters.clear()
            if (ready.isNotEmpty()) post { ready.forEach { if (it.isActive) it.resume(Unit) } }
        }
    }
}

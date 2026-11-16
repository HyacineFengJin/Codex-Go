package dev.codex.go.ui

import android.app.UiModeManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import java.util.function.Consumer

/** A quiet translucent surface. Content and foreground controls are drawn separately. */
class GlassSurface(private val dark: Boolean, private val radius: Float, private val pill: Boolean = false) : Drawable() {
    var fillOpacity: Int = 246
        set(value) { field = value.coerceIn(0, 255); invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private var opacity = 255
    private var highlight: Shader? = null
    private var rim: Shader? = null
    private var filter: ColorFilter? = null
    private val corner get() = if (pill) area.height() / 2 else radius
    override fun onBoundsChange(bounds: Rect) {
        area.set(bounds)
        highlight = LinearGradient(0f, area.top, area.right, area.bottom,
            intArrayOf(Color.argb(if (dark) 12 else 30, 255, 255, 255), Color.TRANSPARENT, Color.argb(2, 255, 255, 255)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        rim = LinearGradient(0f, area.top, 0f, area.bottom,
            intArrayOf(Color.argb(if (dark) 35 else 95, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        paint.colorFilter = filter; paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = if (dark) Color.rgb(23, 29, 28) else Color.rgb(241, 246, 242)
        paint.alpha = fillOpacity * opacity / 255
        canvas.drawRoundRect(area, corner, corner, paint)
        paint.color = Color.WHITE; paint.alpha = opacity; paint.shader = highlight
        canvas.drawRoundRect(area, corner, corner, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f; paint.shader = rim
        canvas.drawRoundRect(area.left + .5f, area.top + .5f, area.right - .5f, area.bottom - .5f, corner, corner, paint)
        paint.shader = null
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, corner); outline.alpha = 1f }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Background blur is restricted to this dialog's rounded bounds, never the screen behind it. */
class GlassWindow(private val context: Context, private val window: Window, dark: Boolean, radius: Float) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    val surface = GlassSurface(dark, radius)
    var usingBlur = false
        private set
    private var closed = false
    private val listener = Consumer<Boolean> { refresh(it) }
    private val contrastListener = if (Build.VERSION.SDK_INT >= 34) UiModeManager.ContrastChangeListener {
        refresh(Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled)
    } else null
    init {
        window.setBackgroundDrawable(surface)
        refresh(Build.VERSION.SDK_INT >= 31 && manager.isCrossWindowBlurEnabled)
        if (Build.VERSION.SDK_INT >= 31) manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) context.getSystemService(UiModeManager::class.java).addContrastChangeListener(context.mainExecutor, contrastListener)
    }
    private fun refresh(available: Boolean) {
        if (closed) return
        val highContrast = Build.VERSION.SDK_INT >= 34 && context.getSystemService(UiModeManager::class.java).contrast > .25f
        usingBlur = available && !highContrast
        surface.fillOpacity = if (usingBlur) 212 else 255
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (usingBlur) (24 * context.resources.displayMetrics.density).toInt() else 0)
    }
    override fun close() {
        if (closed) return
        closed = true
        if (Build.VERSION.SDK_INT >= 31) {
            manager.removeCrossWindowBlurEnabledListener(listener)
            window.setBackgroundBlurRadius(0)
        }
        if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) context.getSystemService(UiModeManager::class.java).removeContrastChangeListener(contrastListener)
    }
}

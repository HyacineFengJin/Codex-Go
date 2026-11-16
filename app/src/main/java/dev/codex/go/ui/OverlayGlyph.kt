package dev.codex.go.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small native counterparts of the chat's controls, with no font-dependent symbols. */
class OverlayGlyph(private val kind: Kind, private val color: Int) : Drawable() {
    enum class Kind { Collapse, Close, Send }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = color; paint.alpha = Color.alpha(color) * opacity / 255; paint.strokeWidth = 1.8f
        when (kind) {
            Kind.Close -> { canvas.drawLine(6f, 6f, 18f, 18f, paint); canvas.drawLine(18f, 6f, 6f, 18f, paint) }
            Kind.Collapse -> { canvas.drawLine(6f, 10f, 12f, 16f, paint); canvas.drawLine(12f, 16f, 18f, 10f, paint) }
            Kind.Send -> { canvas.drawLine(12f, 19f, 12f, 5f, paint); canvas.drawLine(6f, 11f, 12f, 5f, paint); canvas.drawLine(12f, 5f, 18f, 11f, paint) }
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

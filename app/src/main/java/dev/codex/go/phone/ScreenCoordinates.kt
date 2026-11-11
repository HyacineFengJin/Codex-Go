package dev.codex.go.phone

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** Actions target the current display; an observation token is not an action permit. */
object ScreenCoordinates {
    fun requireWithin(width: Int, height: Int, vararg points: Pair<Int, Int>) {
        require(width > 0 && height > 0) { "无法读取当前屏幕尺寸" }
        require(points.all { (x, y) -> x in 0 until width && y in 0 until height }) { "坐标超出当前屏幕范围" }
    }
}

@Suppress("DEPRECATION")
fun requireScreenCoordinates(context: Context, vararg points: Pair<Int, Int>) {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ?: error("无法读取当前屏幕尺寸")
    // Full display pixels, including system bars and adjusted for current rotation;
    // activity/window bounds would incorrectly exclude valid screenshot coordinates.
    val size = Point().also(display::getRealSize)
    ScreenCoordinates.requireWithin(size.x, size.y, *points)
}

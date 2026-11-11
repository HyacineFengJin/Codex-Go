package dev.codex.go.phone

import java.util.UUID

/** Freshness guard for cached image evidence only. Never gates phone actions. */
class FrameGuard(private val now: () -> Long = System::currentTimeMillis) {
    private var id = ""
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var captured = 0L
    fun record(width: Int, height: Int, rotation: Int): String {
        id = UUID.randomUUID().toString(); this.width = width; this.height = height
        this.rotation = rotation; captured = now(); return id
    }
    fun requireCurrent(token: String, currentRotation: Int, vararg coordinates: Pair<Int, Int>) {
        require(id.isNotEmpty() && token == id && now() - captured in 0..30_000 && rotation == currentRotation) { "屏幕已变化或观察过期，请重新观察" }
        require(coordinates.all { (x, y) -> x in 0 until width && y in 0 until height }) { "坐标超出截图范围" }
    }
    fun invalidate() { id = "" }
}

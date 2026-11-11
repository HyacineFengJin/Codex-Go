package dev.codex.go.phone

/** One bounded, memory-only original image, tied to the existing frame/rotation/expiry guard. */
class FrameEvidence(private val guard: FrameGuard, private val maxBytes: Int = 20 * 1024 * 1024) {
    private var image: ByteArray? = null
    private var id = ""
    fun record(width: Int, height: Int, rotation: Int, png: ByteArray): String {
        clear()
        id = guard.record(width, height, rotation)
        if (png.size <= maxBytes) image = png.copyOf()
        return id
    }
    val available get() = image != null
    fun read(token: String, rotation: Int): ByteArray {
        check(token == id && id.isNotEmpty()) { "原图不属于此观察，请重新观察" }
        try { guard.requireCurrent(token, rotation) }
        catch (error: IllegalArgumentException) { clear(); throw error }
        return image?.copyOf() ?: error("原图未保留，请使用 phone_observe 的 image 模式重新观察")
    }
    fun clear() { image = null; id = ""; guard.invalidate() }
}

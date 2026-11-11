package dev.codex.go.phone

import org.json.JSONArray
import org.json.JSONObject

/** Observations only: no task planning, model inference, or action suggestions. */
data class ScreenElement(
    val id: Int, val parent: Int?, val role: String, val text: String, val description: String,
    val bounds: List<Int>, val enabled: Boolean, val clickable: Boolean, val editable: Boolean,
    val checkable: Boolean, val checked: Boolean, val selected: Boolean, val focused: Boolean,
    val scrollable: Boolean, val web: Boolean,
    val hint: String = "", val showingHint: Boolean = false, val stateDescription: String = "",
    val error: String = "", val heading: Boolean = false, val range: ScreenRange? = null,
) {
    fun json() = JSONObject().put("id", id).put("parent", parent ?: JSONObject.NULL).put("role", role)
        .put("text", text).put("description", description).put("bounds", JSONArray(bounds))
        .put("enabled", enabled).put("clickable", clickable).put("editable", editable)
        .put("checkable", checkable).put("checked", checked).put("selected", selected)
        .put("focused", focused).put("scrollable", scrollable).put("source", if (web) "web_accessibility" else "accessibility")
        .apply {
            if (hint.isNotEmpty()) put("hint", hint)
            if (showingHint) put("showingHint", true)
            if (stateDescription.isNotEmpty()) put("stateDescription", stateDescription)
            if (error.isNotEmpty()) put("error", error)
            if (heading) put("heading", true)
            range?.let { put("range", it.json()) }
        }
}

data class ScreenRange(val type: Int, val min: Float, val max: Float, val current: Float) {
    fun json() = JSONObject().put("type", type).put("min", min).put("max", max).put("current", current)
    companion object {
        fun from(type: Int, min: Float, max: Float, current: Float) =
            if (min.isFinite() && max.isFinite() && current.isFinite()) ScreenRange(type, min, max, current) else null
    }
}

/** A label or alt text does not describe all of a chart, photo or custom drawing. */
object ScreenVisuals {
    fun needsImage(role: String, childCount: Int, bounds: List<Int>, width: Int, height: Int): Boolean {
        val area = (bounds[2] - bounds[0]).toLong() * (bounds[3] - bounds[1])
        if (area <= width.toLong() * height / 50) return false
        return role.endsWith("ImageView") || role.endsWith(".Image") || role.contains("SurfaceView") ||
            role.contains("TextureView") || role.contains("Canvas") || (role == "android.view.View" && childCount == 0)
    }
}

data class ScreenWindow(val id: Int, val type: String, val bounds: List<Int>) {
    fun json() = JSONObject().put("id", id).put("type", type).put("bounds", JSONArray(bounds))
}
data class WindowInventory(val windows: List<ScreenWindow>, val truncated: Boolean = false)
data class WindowCoverage(val inventory: WindowInventory?, val activeId: Int, val width: Int, val height: Int) {
    val relevant get() = inventory?.windows.orEmpty().filter {
        val b = it.bounds
        val w = (minOf(width, b[2]) - maxOf(0, b[0])).coerceAtLeast(0)
        val h = (minOf(height, b[3]) - maxOf(0, b[1])).coerceAtLeast(0)
        w > 0 && h > 0 && (it.id == activeId || it.type != "system" || w.toLong() * h > width.toLong() * height / 12)
    }
    val imageReasons get() = buildList {
        if (inventory == null || relevant.none { it.id == activeId }) add("无法确认当前窗口的覆盖范围")
        if (inventory?.truncated == true) add("可见窗口超过读取上限")
        if (relevant.any { it.id != activeId }) add("键盘或其他窗口未包含在当前语义树中")
        relevant.firstOrNull { it.id == activeId }?.bounds?.let { b ->
            val w = (minOf(width, b[2]) - maxOf(0, b[0])).coerceAtLeast(0)
            val h = (minOf(height, b[3]) - maxOf(0, b[1])).coerceAtLeast(0)
            if (w.toLong() * h < width.toLong() * height * 3 / 4) add("当前语义窗口未覆盖大部分画面")
        }
    }
    fun json() = JSONObject().put("activeWindowId", activeId).put("activeWindowOnly", true)
        .put("inventoryAvailable", inventory != null).put("windows", JSONArray(relevant.map { it.json() }))
}

data class ScreenReading(
    val elements: List<ScreenElement>, val truncated: Boolean = false,
    val unlabelledControls: Int = 0, val unrepresentedVisuals: Int = 0,
    val webPresent: Boolean = false,
    val coverage: WindowCoverage? = null,
) {
    val imageReasonCodes get() = buildList {
        if (elements.isEmpty()) add("no_nodes")
        if (truncated) add("incomplete_structure")
        if (unlabelledControls > 0) add("unlabelled_controls")
        if (unrepresentedVisuals > 0) add("visual_content")
        if (webPresent && elements.none { it.web && (it.text.isNotBlank() || it.description.isNotBlank()) }) add("web_semantics_missing")
        if (coverage?.imageReasons?.isNotEmpty() == true) add("window_coverage")
    }
    val imageReasons get() = buildList {
        if (elements.isEmpty()) add("没有可读取的可见节点")
        if (truncated) add("节点或文字未完整读取")
        if (unlabelledControls > 0) add("存在缺少文字标签的控件")
        if (unrepresentedVisuals > 0) add("图像或绘制区域需要原图复核，文字标签不能代替画面")
        if (webPresent && elements.none { it.web && (it.text.isNotBlank() || it.description.isNotBlank()) }) add("网页未暴露可读取的语义节点")
        coverage?.let { addAll(it.imageReasons) }
    }
    fun includeImage(preRead: Boolean, forceImage: Boolean) = !preRead || forceImage || imageReasons.isNotEmpty()
    fun json() = JSONObject().put("source", if (webPresent) "web_accessibility" else "accessibility")
        .put("rawDomAvailable", false).put("modelUsed", JSONObject.NULL).put("interpretation", "structure_only")
        .put("completeVisualDescription", false).put("truncated", truncated)
        .put("unlabelledControls", unlabelledControls).put("unrepresentedVisuals", unrepresentedVisuals)
        .put("imageReasons", JSONArray(imageReasons)).put("elements", JSONArray(elements.map { it.json() }))
        .put("imageReasonCodes", JSONArray(imageReasonCodes))
        .apply { coverage?.let { put("windowCoverage", it.json()) } }
}

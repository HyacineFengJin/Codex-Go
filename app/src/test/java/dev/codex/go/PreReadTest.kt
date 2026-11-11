package dev.codex.go

import dev.codex.go.phone.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import dev.codex.go.protocol.*

class PreReadTest {
    private fun element(web: Boolean = false) = ScreenElement(3, null, "android.widget.Button", "下一步", "继续", listOf(10, 20, 100, 80),
        enabled = false, clickable = true, editable = false, checkable = true, checked = true, selected = false, focused = false, scrollable = false, web = web)
    @Test fun structurePreservesRawFactsAndDoesNotClaimVisualOrModelUnderstanding() {
        val data = ScreenReading(listOf(element())).json()
        assertFalse(data.getBoolean("completeVisualDescription")); assertFalse(data.getBoolean("rawDomAvailable")); assertTrue(data.isNull("modelUsed"))
        val node = data.getJSONArray("elements").getJSONObject(0)
        assertEquals("下一步", node.getString("text")); assertFalse(node.getBoolean("enabled")); assertTrue(node.getBoolean("checked"))
        assertEquals(10, node.getJSONArray("bounds").getInt(0)); assertFalse(node.has("action"))
    }
    @Test fun explicitImageAndDisabledExperimentAlwaysDeliverImage() {
        val reading = ScreenReading(listOf(element()))
        assertFalse(reading.includeImage(true, false)); assertTrue(reading.includeImage(true, true)); assertTrue(reading.includeImage(false, false))
    }
    @Test fun incompleteOrVisualContentRequestsAnImageInsteadOfSilentlyOmittingIt() {
        for (reading in listOf(ScreenReading(emptyList()), ScreenReading(listOf(element()), truncated = true),
            ScreenReading(listOf(element()), unlabelledControls = 1), ScreenReading(listOf(element()), unrepresentedVisuals = 1),
            ScreenReading(listOf(element()), webPresent = true))) assertTrue(reading.includeImage(true, false))
        assertFalse(ScreenReading(listOf(element(true)), webPresent = true).includeImage(true, false))
    }
    @Test fun imageReadReturnsTheOriginalAndDoesNotCreateANewFrame() {
        val guard = FrameGuard(); val evidence = FrameEvidence(guard); val original = byteArrayOf(1, 2, 3)
        val id = evidence.record(100, 200, 0, original); original[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), evidence.read(id, 0)); guard.requireCurrent(id, 0, 50 to 50)
        val read = evidence.read(id, 0); read[0] = 7; assertEquals(1, evidence.read(id, 0)[0].toInt())
    }
    @Test fun staleTokenCannotReadOrEraseANewerObservation() {
        val evidence = FrameEvidence(FrameGuard()); val old = evidence.record(100, 200, 0, byteArrayOf(1))
        val fresh = evidence.record(100, 200, 0, byteArrayOf(2))
        assertThrows(IllegalStateException::class.java) { evidence.read(old, 0) }; assertArrayEquals(byteArrayOf(2), evidence.read(fresh, 0))
    }
    @Test fun expiryRotationAndExplicitClearRemoveCachedEvidence() {
        var now = 1000L; val evidence = FrameEvidence(FrameGuard { now })
        var id = evidence.record(100, 200, 0, byteArrayOf(1)); now += 30_001
        assertThrows(IllegalArgumentException::class.java) { evidence.read(id, 0) }; assertFalse(evidence.available)
        id = evidence.record(100, 200, 0, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { evidence.read(id, 1) }; assertFalse(evidence.available)
        id = evidence.record(100, 200, 0, byteArrayOf(1)); evidence.clear()
        assertThrows(IllegalStateException::class.java) { evidence.read(id, 0) }; assertFalse(evidence.available)
    }
    @Test fun oversizedImageIsNotRetained() {
        val evidence = FrameEvidence(FrameGuard(), maxBytes = 2); val id = evidence.record(100, 200, 0, byteArrayOf(1, 2, 3))
        assertFalse(evidence.available); assertThrows(IllegalStateException::class.java) { evidence.read(id, 0) }
    }
    @Test fun placeholdersRangeAndErrorRemainFactsInsteadOfInventedInput() {
        val data = element().copy(text = "输入关键字", hint = "输入关键字", showingHint = true,
            stateDescription = "尚未选择", error = "至少两个字符", heading = true,
            range = ScreenRange(1, 0f, 100f, 37f)).json()
        assertTrue(data.getBoolean("showingHint")); assertEquals(data.getString("text"), data.getString("hint"))
        assertEquals("尚未选择", data.getString("stateDescription")); assertEquals("至少两个字符", data.getString("error"))
        assertTrue(data.getBoolean("heading")); assertEquals(37.0, data.getJSONObject("range").getDouble("current"), 0.0)
        assertFalse(data.has("recommendedAction"))
    }
    @Test fun malformedRangeCannotMakeTheWholeReportFailJsonEncoding() {
        assertNull(ScreenRange.from(0, 0f, Float.NaN, 2f))
        assertNull(ScreenRange.from(0, 0f, 10f, Float.POSITIVE_INFINITY))
        JSONObject(element().copy(range = ScreenRange.from(0, 0f, 10f, Float.NaN)).json().toString())
    }
    @Test fun largeLabelledImagesStillNeedPixelsButSmallIconsAndTextDoNot() {
        for (role in listOf("android.widget.ImageView", "android.widget.Image", "android.view.View", "custom.Canvas"))
            assertTrue(ScreenVisuals.needsImage(role, 0, listOf(0, 0, 800, 600), 1080, 2400))
        assertFalse(ScreenVisuals.needsImage("android.widget.ImageView", 0, listOf(0, 0, 48, 48), 1080, 2400))
        assertFalse(ScreenVisuals.needsImage("android.widget.TextView", 0, listOf(0, 0, 800, 600), 1080, 2400))
    }
    @Test fun systemBarsDoNotForceImagesButKeyboardAndOtherAppsDo() {
        val main = ScreenWindow(1, "application", listOf(0, 40, 1000, 1940))
        val bars = listOf(ScreenWindow(2, "system", listOf(0, 0, 1000, 40)), ScreenWindow(3, "system", listOf(0, 1940, 1000, 2000)))
        assertTrue(WindowCoverage(WindowInventory(listOf(main) + bars), 1, 1000, 2000).imageReasons.isEmpty())
        for (kind in listOf("input_method", "application", "overlay")) {
            val extra = ScreenWindow(4, kind, listOf(0, 1100, 1000, 1940))
            assertFalse(WindowCoverage(WindowInventory(listOf(main, extra)), 1, 1000, 2000).imageReasons.isEmpty())
        }
    }
    @Test fun missingOrPartialWindowInventoryNeverClaimsFullScreenCoverage() {
        val partial = ScreenWindow(1, "application", listOf(0, 0, 500, 2000))
        for (coverage in listOf(WindowCoverage(null, 1, 1000, 2000), WindowCoverage(WindowInventory(emptyList()), 1, 1000, 2000),
            WindowCoverage(WindowInventory(listOf(partial)), 1, 1000, 2000), WindowCoverage(WindowInventory(listOf(partial), true), 1, 1000, 2000)))
            assertFalse(coverage.imageReasons.isEmpty())
    }
    @Test fun previewParsesOnlyReportsAndUsesTheActualImagePayload() {
        val report = ScreenReading(listOf(element())).json().put("frameId", "frame").put("imageIncluded", true)
        val result = obj("success" to true, "contentItems" to array(obj("type" to "inputText", "text" to "untrusted text\n$report\nend")))
        val preview = ReadingPreview.from(result)
        assertTrue(preview.success); assertFalse(preview.imageIncluded); assertEquals("frame", preview.report?.getString("frameId"))
    }
    @Test fun adbAndFailurePreviewsKeepTheirOriginalMessages() {
        val raw = "无线调试已断开，请重新连接"
        val failed = ReadingPreview.from(obj("success" to false, "contentItems" to array(obj("type" to "inputText", "text" to raw))))
        assertFalse(failed.success); assertNull(failed.report); assertEquals(raw, failed.raw)
        val adb = ReadingPreview.from(obj("success" to true, "contentItems" to array(obj("type" to "inputImage", "imageUrl" to "data:image/png;base64,AA=="))))
        assertTrue(adb.imageIncluded); assertNull(adb.report)
    }
}

package dev.codex.go

import dev.codex.go.plugins.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginContractTest {
    private fun descriptor() = JSONObject("""{"apiVersion":1,"name":"Example","capabilities":["tools"],"tools":[{"name":"read_note","description":"Read explicit text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}""")
    private fun reject(change: (JSONObject) -> Unit) { val raw=descriptor().apply(change); try { PluginManifest.parse(raw.toString()); fail("Invalid manifest accepted") } catch (_: IllegalArgumentException) {} }
    @Test fun versionAndRequiredCapabilitiesFailClosed() {
        reject { it.put("apiVersion",2) }; reject { it.put("capabilities",JSONArray().put("tools").put("phone")) }
    }
    @Test fun invalidDuplicateAndMalformedToolsAreRejected() {
        reject { it.getJSONArray("tools").getJSONObject(0).put("name","../../phone_tap") }
        reject { it.getJSONArray("tools").put(it.getJSONArray("tools").getJSONObject(0)) }
        reject { it.getJSONArray("tools").getJSONObject(0).getJSONObject("inputSchema").put("type","string") }
        reject { it.put("timeoutMs",-1) }
    }
    @Test fun largeCatalogAndPluginChosenDeadlineDoNotAcquireHostQuotas() {
        val value = descriptor().put("description", "d".repeat(66000)).put("timeoutMs", 180000)
        val tools = JSONArray()
        repeat(40) { tools.put(JSONObject().put("name", "tool_$it").put("description", "help".repeat(600))
            .put("inputSchema", JSONObject().put("type", "object").put("description", "schema".repeat(4000)))) }
        value.put("tools", tools)
        val parsed = PluginManifest.parse(value.toString())
        assertEquals(40, parsed.tools.size); assertEquals(180000L, parsed.timeoutMs)
        assertEquals(0L, PluginManifest.parse(descriptor().toString()).timeoutMs)
        val content = JSONArray()
        repeat(40) { content.put(JSONObject().put("type", "text").put("text", "line $it")) }
        assertEquals(40, PluginContract.validateResult(JSONObject().put("content", content)).getJSONArray("content").length())
    }
    @Test fun namespacesAreStableUniqueAndCannotShadowBuiltins() {
        val manifest=PluginManifest.parse(descriptor().toString())
        val first=InstalledPlugin("one/.Service","one",".Service",1,"1","sha",manifest,true)
        val second=first.copy(key="two/.Service")
        assertNotEquals(first.toolName("read_note"),second.toolName("read_note"))
        assertEquals(first.toolName("read_note"),first.copy(version="2").toolName("read_note"))
        assertTrue(first.toolName("read_note").matches(Regex("plugin_[a-f0-9]{20}_read_note")))
        val catalog=PluginContract.catalog(listOf(first,second,first.copy(key="disabled",enabled=false)))
        assertEquals(2,catalog.definitions.length());assertEquals("sha",catalog.approvals.getString(first.toolName("read_note")))
    }
    @Test fun outputConvertsOnlyTextAndPreservesErrors() {
        val result=JSONObject("""{"content":[{"type":"text","text":"original % text"}],"isError":true}""")
        val output=CodexPluginAdapter.toolResult(result)
        assertFalse(output.getBoolean("success")); assertEquals("inputText",output.getJSONArray("contentItems").getJSONObject(0).getString("type"))
        assertEquals("original % text",output.getJSONArray("contentItems").getJSONObject(0).getString("text"))
        assertEquals(0, PluginContract.validateResult(JSONObject("""{"content":[]}""")).getJSONArray("content").length())
        for (bad in listOf(JSONObject("""{"content":[{"type":"image","url":"file:///private"}]}"""),JSONObject().put("content",JSONArray().put(JSONObject().put("type","text").put("text","x".repeat(140000)))))) {
            try { CodexPluginAdapter.toolResult(bad);fail("Unsafe output accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}

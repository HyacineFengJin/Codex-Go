package dev.codex.go

import android.content.SharedPreferences
import dev.codex.go.files.*
import dev.codex.go.runtime.*
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Disk snapshots and failed commits model death between separate preference commits. */
private class TransferPreferences(val disk: MutableMap<String, Any?> = mutableMapOf()) {
    var reject: (Map<String, Any?>, Set<String>) -> Boolean = { _, _ -> false }
    val prefs: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
        when (method.name) {
            "edit" -> editor()
            "contains" -> disk.containsKey(args!![0])
            "getAll" -> disk.toMap()
            "getString", "getStringSet", "getBoolean", "getInt", "getLong", "getFloat" -> disk[args!![0]] ?: args[1]
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "Test preferences"
            else -> null
        }
    } as SharedPreferences
    private fun editor(): SharedPreferences.Editor {
        val changed = mutableMapOf<String, Any?>(); val removed = mutableSetOf<String>(); var clear = false
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { changed[args!![0] as String] = args[1]; removed.remove(args[0] as String); proxy }
                method.name == "remove" -> { removed += args!![0] as String; changed.remove(args[0]); proxy }
                method.name == "clear" -> { clear = true; proxy }
                method.name in setOf("commit", "apply") -> {
                    val success = !reject(changed, removed)
                    if (success) { if (clear) disk.clear(); removed.forEach(disk::remove); disk.putAll(changed) }
                    if (method.name == "commit") success else null
                }
                else -> null
            }
        } as SharedPreferences.Editor
    }
}

class DraftTransferTest {
    private fun stored(prefs: TransferPreferences, key: String) = Draft.fromJson(JSONObject(prefs.prefs.getString(key, "{}")!!))
    @Test fun legacyCodexMoveCommitsDraftAndRemovalTogether() {
        val store = TransferPreferences(mutableMapOf("entry" to "keep", "draft:new" to Draft("old").json().toString()))
        store.reject = { _, removed -> "entry" in removed }
        assertThrows(IllegalStateException::class.java) {
            DraftTransfer.apply(store.prefs, store.prefs, "draft:new", "receipt", Draft("old"), { Draft("shared") }, finishSource = { it.remove("entry") })
        }
        assertEquals("old", stored(store, "draft:new").text); assertTrue(store.prefs.contains("entry"))
        store.reject = { _, _ -> false }
        DraftTransfer.apply(store.prefs, store.prefs, "draft:new", "receipt", Draft("old"), { Draft("shared") }, finishSource = { it.remove("entry") })
        assertEquals("old\n\nshared", stored(store, "draft:new").text); assertFalse(store.prefs.contains("entry"))
    }
    @Test fun crossEngineFailureAfterDestinationCommitCanRetryAfterRestartWithoutDuplicateText() {
        val source = TransferPreferences(mutableMapOf("entry" to "keep", "draft:new" to Draft("Codex keep").json().toString()))
        val target = TransferPreferences(); source.reject = { _, removed -> "entry" in removed }
        val saved = assertThrows(SavedDraftTransferFailure::class.java) {
            DraftTransfer.apply(source.prefs, target.prefs, "draft:new", "receipt", Draft("Antigravity keep"), { Draft("shared") }, finishSource = { it.remove("entry") })
        }
        assertEquals("Antigravity keep\n\nshared", saved.draft.text)
        assertEquals("Codex keep", stored(source, "draft:new").text)
        target.prefs.edit().putString("draft:new", Draft(saved.draft.text + "\nuser edits").json().toString()).commit()
        val reopenedSource = TransferPreferences(source.disk.toMutableMap()); val reopenedTarget = TransferPreferences(target.disk.toMutableMap())
        val retried = DraftTransfer.apply(reopenedSource.prefs, reopenedTarget.prefs, "draft:new", "receipt", Draft("stale"),
            incoming = { error("Must not merge twice") }, publish = { error("Must not move files twice") }, finishSource = { it.remove("entry") })
        assertEquals(saved.draft.text + "\nuser edits", retried.text)
        assertFalse(reopenedSource.prefs.contains("entry")); assertEquals("Codex keep", stored(reopenedSource, "draft:new").text)
    }
    @Test fun failedDestinationCommitKeepsInboxAndExistingDraft() {
        val source = TransferPreferences(mutableMapOf("entry" to "keep")); val target = TransferPreferences()
        target.reject = { _, _ -> true }
        assertThrows(IllegalStateException::class.java) {
            DraftTransfer.apply(source.prefs, target.prefs, "draft:new", "receipt", Draft("keep"), { Draft("incoming") }, finishSource = { it.remove("entry") })
        }
        assertTrue(source.prefs.contains("entry")); assertFalse(target.prefs.contains("receipt")); assertFalse(target.prefs.contains("draft:new"))
    }
    @Test fun quickInboxTargetsCurrentEngineAndCannotReassignAnAlreadyCommittedTransfer() {
        val source = TransferPreferences(mutableMapOf("draft:new" to Draft("Codex keep").json().toString()))
        val target = TransferPreferences(); val inbox = QuickMessages(source.prefs)
        inbox.add(QuickMessage("one", "incoming", "Antigravity", "fixture"))
        source.reject = { _, removed -> "quickRoute:one" in removed }
        assertThrows(SavedDraftTransferFailure::class.java) { inbox.apply("one", "draft:new", Draft("AG keep"), target.prefs, AgentEngine.Antigravity) }
        assertEquals("Codex keep", stored(source, "draft:new").text)
        assertThrows(IllegalArgumentException::class.java) { inbox.apply("one", "draft:new", Draft("Codex keep")) }
        source.reject = { _, _ -> false }
        val result = QuickMessages(source.prefs).apply("one", "draft:new", Draft("stale"), target.prefs, AgentEngine.Antigravity)
        assertEquals("AG keep\n\nincoming", result.text); assertTrue(inbox.list().isEmpty())
    }
}

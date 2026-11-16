package dev.codex.go.files

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException

class SavedDraftTransferFailure(val draft: Draft) : IOException("Draft saved; inbox cleanup failed")

/** A destination receipt closes the crash window between two preferences files. */
object DraftTransfer {
    fun apply(source: SharedPreferences, destination: SharedPreferences, key: String, receipt: String,
        existing: Draft, incoming: () -> Draft, publish: () -> Unit = {},
        editDestination: (SharedPreferences.Editor) -> Unit = {},
        finishSource: (SharedPreferences.Editor) -> Unit): Draft {
        val applied = destination.getBoolean(receipt, false)
        val merged = if (applied) Draft.fromJson(JSONObject(destination.getString(key, "{}")!!))
            else SharedDraft.merge(existing, incoming())
        if (!applied) publish()
        val target = destination.edit().putString(key, merged.json().toString())
        if (source !== destination) target.putBoolean(receipt, true)
        editDestination(target)
        if (source === destination) {
            finishSource(target)
            check(target.commit()) { "Could not save draft; inbox entry was kept" }
        } else {
            check(target.commit()) { "Could not save draft; inbox entry was kept" }
            val cleanup = source.edit(); finishSource(cleanup)
            if (!cleanup.commit()) throw SavedDraftTransferFailure(merged)
        }
        return merged
    }
}

package dev.codex.go.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal object AppUpdates {
    const val RELEASES = "https://github.com/HyacineFengJin/Codex-Go/releases"
    private const val API = "https://api.github.com/repos/HyacineFengJin/Codex-Go/releases?per_page=20"
    private val versionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    suspend fun latest(installed: String): String? = withContext(Dispatchers.IO) {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK)
            val releases = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
            newerVersion(releases, installed)
        } finally {
            connection.disconnect()
        }
    }

    fun newerVersion(releases: JSONArray, installed: String): String? {
        val current = checkNotNull(parseVersion(installed))
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            if ((0 until assets.length()).none { assets.optJSONObject(it)?.optString("name") == "codex-go.apk" }) {
                return@mapNotNull null
            }
            val tag = release.optString("tag_name")
            if (!tag.startsWith('v')) return@mapNotNull null
            parseVersion(tag.drop(1))
        }.maxOrNull()?.takeIf { it > current }?.toString()
    }

    fun isAtLeast(version: String, minimum: String): Boolean {
        val candidate = parseVersion(version) ?: return false
        return candidate >= checkNotNull(parseVersion(minimum))
    }

    private fun parseVersion(value: String): Version? {
        if (!versionPattern.matches(value)) return null
        val parts = value.split('.').map { it.toIntOrNull() ?: return null }
        return Version(parts[0], parts[1], parts[2])
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
        override fun toString() = "$major.$minor.$patch"
    }
}

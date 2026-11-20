package dev.codex.go.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.system.Os
import dev.codex.go.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** The Android installer owns the executables. Only assets are extracted into writable storage. */
internal class InstalledCli private constructor(
    val version: String,
    private val nativeDir: File,
    private val assets: AssetManager,
    private val manifest: JSONObject,
    private val identity: String,
) {
    val bindings: Map<String, File> = manifest.getJSONObject("bindings").let { entries ->
        entries.keys().asSequence().associateWith { guest ->
            require(guest.startsWith("/opt/codex/"))
            val library = entries.getString(guest)
            require(library.matches(Regex("libguest_[a-f0-9]{16}\\.so")))
            File(nativeDir, library).also { check(it.isFile) }
        }.also { check("/opt/codex/bin/codex" in it) }
    }

    fun prepare(context: Context): File {
        val contents = File(context.cacheDir, "codex-cli")
        if (File(contents, ".identity").takeIf(File::isFile)?.readText() == identity) return File(contents, "opt/codex")
        val staging = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "codex-cli-").toFile()
        try {
            assets.open("cli/files.zip").use { RuntimeEnvironment.unpack(it, staging) }
            val executables = manifest.getJSONArray("executables")
            for (index in 0 until executables.length()) {
                Os.chmod(packFile(staging, executables.getString(index)).path, 0b111101101)
            }
            val links = manifest.getJSONObject("links")
            links.keys().forEach { name ->
                val link = packFile(staging, name)
                link.parentFile!!.mkdirs()
                Os.symlink(links.getString(name), link.path)
            }
            check(File(staging, "opt/codex/bin/codex").isFile)
            File(staging, ".identity").writeText(identity)
            check(contents.deleteRecursively() && staging.renameTo(contents))
            return File(contents, "opt/codex")
        } finally { staging.deleteRecursively() }
    }

    private fun packFile(root: File, path: String): File {
        require(path.startsWith("opt/codex/"))
        return RuntimeEnvironment.safeChild(root, path)
    }

    fun verify(process: Process) {
        try {
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Independent Codex CLI could not start" }
            check(process.inputStream.bufferedReader().use { it.readLine() } == "codex-cli $version") {
                "Independent Codex CLI reported a different version"
            }
        } finally { process.destroyForcibly() }
    }

    companion object {
        const val PACKAGE = "dev.codex.go.cli"
        @Suppress("DEPRECATION")
        fun find(context: Context): InstalledCli? = runCatching {
            val manager = context.packageManager
            check(manager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH)
            val info = manager.getPackageInfo(PACKAGE, 0)
            val application = checkNotNull(info.applicationInfo)
            check(application.enabled)
            val assets = manager.getResourcesForApplication(application).assets
            val data = assets.open("cli/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            check(data.getInt("format") == 1 && data.getString("abi") == BuildConfig.RUNTIME_ABI)
            val version = data.getString("version")
            check(version == info.versionName && AppUpdates.isAtLeast(version, BuildConfig.CODEX_VERSION))
            check(data.getString("sourceSha256").matches(Regex("[0-9a-f]{64}")))
            InstalledCli(version, File(application.nativeLibraryDir), assets, data, "${info.lastUpdateTime}:${info.longVersionCode}:${application.sourceDir}")
        }.getOrNull()
    }
}

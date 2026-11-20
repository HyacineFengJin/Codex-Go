package dev.codex.go.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import dev.codex.go.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Writable documents and account data outlive replacement of the guest system. */
class RuntimeEnvironment(private val context: Context) {
    val root = File(context.filesDir, "environment")
    val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
    private val projects = File(context.filesDir, "projects").apply { mkdirs() }
    private val account = File(context.filesDir, "codex").apply { mkdirs() }
    private val antigravity = File(context.filesDir, "antigravity").apply { mkdirs() }
    private val helpers = File(context.filesDir, "runtime-helpers").apply { mkdirs() }

    fun install(force: Boolean = false, progress: (String) -> Unit) {
        check(Build.SUPPORTED_ABIS.contains(BuildConfig.RUNTIME_ABI)) { "运行包架构与设备不匹配：${BuildConfig.RUNTIME_ABI}" }
        val manifest = context.assets.open("runtime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        check(manifest.getString("abi") == BuildConfig.RUNTIME_ABI) { "运行环境架构不匹配，请重新构建安装包" }
        val version = manifest.getString("version")
        ensureAccountConfig()
        context.assets.open("history_mcp.py").use { input -> File(helpers, "history_mcp.py").outputStream().use { input.copyTo(it) } }
        listOf("antigravity_bridge.py", "agent_mcp.py").forEach { name ->
            context.assets.open(name).use { input -> File(helpers, name).outputStream().use { input.copyTo(it) } }
        }
        RuntimeInstallation.recover(root)
        if (!force && File(root, ".version").takeIf { it.exists() }?.readText() == version) { File(root, "projects").mkdirs(); File(root, "codex-go").mkdirs(); refreshDns(); return }
        val required = manifest.optLong("unpackedBytes", 200L * 1024 * 1024) + 64L * 1024 * 1024
        check(android.os.StatFs(context.filesDir.path).availableBytes >= required) { "可用空间不足，请至少释放 ${required / 1048576} MB 后重试。" }
        progress("准备手机里的 Linux 环境…")
        val staging = File(context.filesDir, "install-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.assets.open("runtime/rootfs.zip").use { unpack(it, staging) }
            File(staging, ".executables.json").takeIf(File::exists)?.readText()?.let { text ->
                val paths = org.json.JSONArray(text)
                for (index in 0 until paths.length()) Os.chmod(safeChild(staging, paths.getString(index)).path, 0b111101101)
            }
            val links = JSONObject(File(staging, ".links.json").readText())
            links.keys().forEach { name ->
                val link = safeChild(staging, name); link.parentFile!!.mkdirs()
                Os.symlink(links.getString(name), link.path)
            }
            listOf("dev", "proc", "sys", "tmp", "workspace", "projects", "codex-go", "root/.codex", "etc/ssl/certs", "usr/local/bin").forEach { File(staging, it).mkdirs() }
            context.assets.open("runtime/ca.pem").use { input -> File(staging, "etc/ssl/certs/ca-certificates.crt").outputStream().use { input.copyTo(it) } }
            File(staging, ".version").writeText(version)
            RuntimeInstallation.replace(root, staging)
        } finally { staging.deleteRecursively() }
        refreshDns()
    }
    private fun ensureAccountConfig() {
        val config = File(account, "config.toml")
        if (!config.exists()) config.writeText(ConfigurationRecovery.DEFAULTS)
    }
    private fun refreshDns() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
        File(root, "etc/resolv.conf").writeText(servers.joinToString("\n", postfix = "\n") { "nameserver ${it.hostAddress}" })
    }
    private var installedCli: InstalledCli? = null
    private var cliFiles: File? = null
    val independentCliVersion get() = installedCli?.version

    /** Pin the runtime for both the main app-server and its auxiliary screen reader. */
    fun prepareIndependentCli(proxy: String): Boolean {
        val candidate = InstalledCli.find(context) ?: return true
        return try {
            cliFiles = candidate.prepare(context)
            installedCli = candidate
            candidate.verify(launchProcess(proxy, emptyList(), "/workspace", AgentEngine.Codex, versionCheck = true))
            true
        } catch (_: Exception) {
            useBundledCli()
            false
        }
    }

    fun useBundledCli() {
        installedCli = null
        cliFiles = null
    }

    fun launch(proxy: String, overrides: List<String> = emptyList(), guestCwd: String = "/workspace", engine: AgentEngine = AgentEngine.Codex) =
        launchProcess(proxy, overrides, guestCwd, engine, versionCheck = false)

    private fun launchProcess(proxy: String, overrides: List<String>, guestCwd: String, engine: AgentEngine, versionCheck: Boolean): Process {
        val native = context.applicationInfo.nativeLibraryDir
        val command = mutableListOf("$native/libproot.so", "-0", "--link2symlink", "-r", root.path, "-w", guestCwd)
        listOf("/dev", "/proc", "/sys", "${workspace.path}:/workspace", "${projects.path}:/projects", "${helpers.path}:/codex-go").forEach { command += listOf("-b", it) }
        val bindings = JSONObject(File(root, ".bindings.json").readText())
        val external = installedCli?.takeIf { engine == AgentEngine.Codex }
        if (external != null) command += listOf("-b", "${checkNotNull(cliFiles).path}:/opt/codex")
        bindings.keys().forEach { guest ->
            if (external == null || !guest.startsWith("/opt/codex/")) {
                command += listOf("-b", "$native/${bindings.getString(guest)}:$guest")
            }
        }
        external?.bindings?.forEach { (guest, file) -> command += listOf("-b", "${file.path}:$guest") }
        if (engine == AgentEngine.Antigravity) {
            check(File(native, "libguest_agy.so").isFile) { "This build does not include Antigravity CLI. Build with -Pantigravity after preparing its official runtime." }
            command += listOf("-b", "$native/libguest_agy.so:/usr/local/bin/agy", "-b", "${antigravity.path}:/root")
            command += listOf("/usr/bin/python3", "/codex-go/antigravity_bridge.py", "/usr/local/bin/agy", "acp", "--disable-telemetry", "--app-data-dir", "codex-go")
        } else {
            command += listOf("-b", "${account.path}:/root/.codex")
            command += "/usr/local/bin/codex"
            command += if (versionCheck) listOf("--version") else listOf("app-server", "--listen", "stdio://")
            overrides.forEach { command += listOf("-c", it) }
        }
        return ProcessBuilder(command).apply {
            environment().apply {
                put("PROOT_TMP_DIR", context.cacheDir.path); put("PROOT_LOADER", "$native/libloader.so")
                put("PROOT_LOADER_32", "$native/libloader32.so"); remove("PROOT_NO_SECCOMP"); put("LD_LIBRARY_PATH", native)
                put("HOME", "/root"); if (engine == AgentEngine.Codex) put("CODEX_HOME", "/root/.codex") else remove("CODEX_HOME"); put("TMPDIR", "/tmp")
                put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"); put("SHELL", "/bin/sh"); put("LANG", "C.UTF-8")
                put("HTTPS_PROXY", proxy); put("https_proxy", proxy); put("NO_PROXY", "127.0.0.1,localhost,::1"); put("no_proxy", "127.0.0.1,localhost,::1")
                if (engine == AgentEngine.Antigravity) { put("CODEX_GO_ACP_STATE", "/root/.gemini/codex-go-sessions.json") }
                put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")
            }
        }.start()
    }
    companion object {
        internal fun unpack(input: InputStream, destination: File) {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = safeChild(destination, entry.name)
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile!!.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }

        fun safeChild(root: File, relative: String): File {
            val target = File(root, relative)
            require(!relative.startsWith('/') && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "运行环境包含无效路径" }
            return target
        }
    }
}

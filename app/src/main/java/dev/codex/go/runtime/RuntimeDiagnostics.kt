package dev.codex.go.runtime

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dev.codex.go.BuildConfig
import dev.codex.go.phone.PhoneAccessService
import dev.codex.go.protocol.RpcError
import dev.codex.go.protocol.obj
import dev.codex.go.protocol.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No arbitrary log text, exception messages, identifiers, account files or documents are exported. */
object RuntimeDiagnostics {
    enum class Event { Start, Ready, StartFailed, Disconnected, Stop, Repair, Login, Logout, SessionRenamed, SessionArchived, SessionRestored, AppCrash }
    private val stages = setOf("install", "launch", "handshake", "configuration", "account", "models", "history")
    private val lock = Any()
    fun sanitize(events: JSONArray): JSONArray = JSONArray(events.objects().mapNotNull { event ->
        if (Event.entries.none { it.name == event.optString("event") }) return@mapNotNull null
        obj("event" to event.getString("event"), "timeMs" to event.optLong("timeMs").coerceAtLeast(0),
            "stage" to event.optString("stage").takeIf { it in stages },
            "errorType" to event.optString("errorType").takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) && it != "null" },
            "rpcCode" to (event.opt("rpcCode") as? Number)?.toInt())
    }.takeLast(80))

    fun entry(event: Event, stage: String? = null, error: Throwable? = null): JSONObject = obj(
        "timeMs" to System.currentTimeMillis(), "event" to event.name,
        "stage" to stage?.takeIf { it in stages },
        "errorType" to error?.javaClass?.simpleName?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) },
        "rpcCode" to (error as? RpcError)?.code,
    )

    fun record(context: Context, event: Event, stage: String? = null, error: Throwable? = null) = synchronized(lock) {
        // Diagnostics must never cause a second failure while reporting the first one.
        runCatching {
            val prefs = context.getSharedPreferences("diagnostic_events", Context.MODE_PRIVATE)
            val previous = runCatching { sanitize(JSONArray(prefs.getString("events", "[]"))).objects() }.getOrDefault(emptyList())
            prefs.edit().putString("events", JSONArray((previous + entry(event, stage, error)).takeLast(80)).toString()).commit()
        }
        Unit
    }

    fun snapshot(context: Context, state: EngineState): JSONObject {
        val installed = File(context.filesDir, "environment/.version")
        val bundled = context.assets.open("runtime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val events = synchronized(lock) {
            runCatching { JSONArray(context.getSharedPreferences("diagnostic_events", Context.MODE_PRIVATE).getString("events", "[]")) }.getOrDefault(JSONArray())
        }
        return obj(
            "formatVersion" to 1, "createdAtMs" to System.currentTimeMillis(),
            "app" to obj("version" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE, "runtimeAbi" to BuildConfig.RUNTIME_ABI, "codex" to "0.165.0"),
            "device" to obj("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT, "abis" to JSONArray(Build.SUPPORTED_ABIS.toList())),
            // Compare versions without including the contents of a potentially damaged marker.
            "environment" to obj("installed" to installed.isFile, "matchesBundle" to (installed.isFile && installed.readText().trim() == bundled.getString("version")), "availableBytes" to StatFs(context.filesDir.path).availableBytes),
            "runtime" to obj("phase" to state.phase.name, "startupStage" to state.startupStage?.takeIf { it in stages }, "busy" to state.busy, "signedIn" to state.signedIn, "loginPending" to state.loginPending, "failureKind" to state.failure?.kind?.name, "nativeRetrying" to (state.failure?.willRetry == true)),
            "phone" to obj("accessibilityConnected" to PhoneAccessService.connected.value, "adbConnected" to state.adbConnected, "phoneEnabled" to state.phoneEnabled, "shellEnabled" to state.shellEnabled, "paused" to state.phonePaused),
            "system" to obj("networkAvailable" to (connectivity.activeNetwork != null), "notificationsAllowed" to context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(), "batteryOptimizationExempt" to power.isIgnoringBatteryOptimizations(context.packageName)),
            "events" to sanitize(events),
        )
    }
}

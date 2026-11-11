package dev.codex.go

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.codex.go.runtime.EngineService
import dev.codex.go.ui.CodexGoApp
import dev.codex.go.i18n.AppLanguage

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))
    private var engine by mutableStateOf<EngineService?>(null)
    private var bound = false
    private var openShares = false
    private var openTask: String? = null
    private var openQuick = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { engine?.startEngine() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { engine = (binder as EngineService.Connection).engine; bound = true; engine?.refreshShares(openShares); openShares = false; openTask?.let { engine?.openTask(it); openTask = null }; if (openQuick) { engine?.showQuickMessages(); openQuick = false }; engine?.setVisible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
        override fun onServiceDisconnected(name: ComponentName) { engine = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openShares = savedInstanceState?.getBoolean("openShares") ?: (intent.action == "OPEN_SHARES")
        openQuick = savedInstanceState?.getBoolean("openQuick") ?: (intent.action == "OPEN_QUICK")
        openTask = if (savedInstanceState != null) savedInstanceState.getString("openTask") else intent.takeIf { it.action == "OPEN_TASK" }?.getStringExtra("taskToken")
        enableEdgeToEdge()
        bound = bindService(Intent(this, EngineService::class.java), connection, BIND_AUTO_CREATE)
        setContent { CodexGoApp(engine) { start() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "OPEN_QUICK") { if (engine != null) engine?.showQuickMessages() else openQuick = true }
        if (intent.action == "OPEN_TASK") intent.getStringExtra("taskToken")?.let { if (engine != null) engine?.openTask(it) else openTask = it }
        if (intent.action == "OPEN_SHARES") { if (engine != null) engine?.refreshShares(true) else openShares = true }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("openShares", openShares); outState.putBoolean("openQuick", openQuick); outState.putString("openTask", openTask); super.onSaveInstanceState(outState) }
    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else engine?.startEngine()
    }
    override fun onStart() { super.onStart(); engine?.refreshShares(); engine?.setVisible(true) }
    override fun onStop() { engine?.setVisible(false); super.onStop() }
    override fun onDestroy() { if (bound) unbindService(connection); super.onDestroy() }
}

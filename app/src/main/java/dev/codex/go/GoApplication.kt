package dev.codex.go

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security
import dev.codex.go.runtime.RuntimeDiagnostics
import dev.codex.go.i18n.AppLanguage
import dev.codex.go.i18n.AppText
import android.content.Context

class GoApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))
    override fun onCreate() {
        super.onCreate()
        AppLanguage.migrate(this)
        AppText.initialize(this)
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                RuntimeDiagnostics.record(this, RuntimeDiagnostics.Event.AppCrash, error = failure)
                previous.uncaughtException(thread, failure)
            }
        }
    }
}

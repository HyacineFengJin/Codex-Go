package dev.codex.go.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Framework locales on Android 13+, persisted context overrides on Android 11/12. */
object AppLanguage {
    private const val preferences = "app_language"
    val supported = listOf("", "en", "zh-Hans")

    fun selection(context: Context): String = if (Build.VERSION.SDK_INT >= 33)
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    else context.getSharedPreferences(preferences, 0).getString("language", "").orEmpty()

    fun locales(context: Context): LocaleList {
        val selected = selection(context)
        if (selected.isNotEmpty()) return LocaleList.forLanguageTags(selected)
        return if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
        else Resources.getSystem().configuration.locales
    }

    fun locale(context: Context): Locale = locales(context)[0] ?: Locale.ENGLISH

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val selected = selection(context)
        if (selected.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(selected)) })
    }

    fun migrate(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val saved = context.getSharedPreferences(preferences, 0)
        if (!saved.contains("language") || saved.getBoolean("frameworkMigrated", false)) return
        val tag = saved.getString("language", "").orEmpty()
        val manager = context.getSystemService(LocaleManager::class.java)
        if (manager.applicationLocales.isEmpty && tag in supported) manager.applicationLocales = LocaleList.forLanguageTags(tag)
        saved.edit().putBoolean("frameworkMigrated", true).apply()
    }

    fun set(context: Context, tag: String) {
        require(tag in supported)
        context.getSharedPreferences(preferences, 0).edit().putString("language", tag).putBoolean("frameworkMigrated", true).apply()
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    }

    fun recreateLegacyActivity(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.recreate()
    }
}

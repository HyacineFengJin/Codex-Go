package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.codex.go.phone.PhoneAccessService

object DeviceSettings {
    fun accessibility(context: Context) {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, PhoneAccessService::class.java))
        runCatching { context.startActivity(details) }.onFailure { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
    fun wireless(context: Context) {
        runCatching { context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }
    fun overlay(context: Context) = application(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    fun application(context: Context, action: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
        runCatching { context.startActivity(Intent(action, Uri.parse("package:${context.packageName}"))) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
}

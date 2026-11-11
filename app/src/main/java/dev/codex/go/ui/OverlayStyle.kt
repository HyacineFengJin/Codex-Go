package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** Native service windows share the app's stone/mint palette without an Activity theme. */
class OverlayStyle(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun color(light: String, dark: String) = Color.parseColor(if (this.dark) dark else light)
    val ink = color("#18201B", "#E0E8DD")
    val muted = color("#536057", "#B9C7B9")
    val surface = color("#F8FAF5", "#1A221B")
    val inner = color("#FFFFFF", "#101610")
    val outline = color("#CDD8CC", "#404E42")
    val accent = color("#005B46", "#9BDBBA")
    val mint = color("#C7F2D7", "#004F3C")
    val blue = color("#D2EBF5", "#194D60")
    fun shape(color: Int, radius: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border) setStroke(dp(1), outline)
    }
    fun text(value: String, size: Float = 14f, secondary: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink)
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun action(label: String, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = label; textSize = 13f; isAllCaps = false; setTextColor(if (primary) if (dark) Color.parseColor("#003826") else Color.WHITE else ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0; minimumWidth = 0; minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0); gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(outline), shape(if (primary) accent else mint, 22), null)
        setOnClickListener { click() }
    }
    fun icon(symbol: String, description: String, click: () -> Unit) = action(symbol, click = click).apply {
        contentDescription = description; textSize = 22f; setPadding(0, 0, 0, 0)
        background = RippleDrawable(ColorStateList.valueOf(outline), shape(inner, 18), null)
    }
}

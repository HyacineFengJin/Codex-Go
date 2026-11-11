package dev.codex.go.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/** Android resources are the source of truth for Compose, notifications and service windows. */
object AppText {
    private lateinit var application: Context
    private var cachedTag = ""
    private var cachedContext: Context? = null
    @Synchronized fun initialize(context: Context) { application = context.applicationContext; cachedContext = null }
    @Synchronized private fun localized(): Context {
        val locales = AppLanguage.locales(application)
        val tag = locales.toLanguageTags()
        if (cachedContext == null || cachedTag != tag) {
            cachedTag = tag
            cachedContext = application.createConfigurationContext(Configuration(application.resources.configuration).apply { setLocales(locales) })
        }
        return cachedContext!!
    }
    fun get(@StringRes id: Int, vararg arguments: Any?): String = if (arguments.isEmpty()) localized().getString(id) else localized().getString(id, *arguments)

    private data class Pattern(val id: Int, val expression: Regex, val positions: List<Int>)
    private val catalog by lazy {
        val exact = mutableMapOf<String, Int>(); val patterns = mutableListOf<Pattern>()
        val placeholder = Regex("%(\\d+)\\\$s")
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            val source = application.createConfigurationContext(Configuration(application.resources.configuration).apply { setLocale(locale) })
            for (id in legacyMessageIds) {
                val value = source.getString(id)
                val matches = placeholder.findAll(value).toList()
                if (matches.isEmpty()) exact.putIfAbsent(value, id)
                else if (value.length < 350 && matches.size <= 6 && placeholder.replace(value, "").count { it.isLetter() } >= 2) {
                    var position = 0
                    val expression = buildString {
                        append('^')
                        for (match in matches) { append(Regex.escape(value.substring(position, match.range.first))); append("([\\s\\S]*?)"); position = match.range.last + 1 }
                        append(Regex.escape(value.substring(position))); append('$')
                    }
                    patterns += Pattern(id, Regex(expression), matches.map { it.groupValues[1].toInt() - 1 })
                }
            }
        }
        exact to patterns.sortedByDescending { it.expression.pattern.length }
    }

    /** Only for app-owned status/labels from old state. Never translate user/model content. */
    fun metadata(value: String): String {
        catalog.first[value]?.let { return get(it) }
        if (value.length <= 8000) for (pattern in catalog.second) {
            val match = pattern.expression.matchEntire(value) ?: continue
            val args = arrayOfNulls<Any>(pattern.positions.maxOrNull()!! + 1)
            pattern.positions.forEachIndexed { group, position -> args[position] = match.groupValues[group + 1] }
            return get(pattern.id, *args)
        }
        return value
    }
}

fun tr(@StringRes id: Int, vararg arguments: Any?): String = AppText.get(id, *arguments)
fun metadata(value: String): String = AppText.metadata(value)

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Mint = Color(0xFF247657)
private val Light = lightColorScheme(
    primary = Color(0xFF006B4C), onPrimary = Color.White,
    primaryContainer = Color(0xFFABF2CE), onPrimaryContainer = Color(0xFF003724),
    secondary = Color(0xFF52634C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E69B), onSecondaryContainer = Color(0xFF3C3300),
    tertiary = Color(0xFF356477), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4E8FF), onTertiaryContainer = Color(0xFF07374F),
    background = Color(0xFFF6F5F1), onBackground = Color(0xFF1B2420),
    surface = Color(0xFFFAFAF6), onSurface = Color(0xFF1B2420),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F1EB),
    surfaceContainer = Color(0xFFE8EBE3), surfaceContainerHigh = Color(0xFFDFE5DB),
    surfaceContainerHighest = Color(0xFFD8DFD4),
    surfaceVariant = Color(0xFFDFE5DB), onSurfaceVariant = Color(0xFF526055),
    outline = Color(0xFF738177), outlineVariant = Color(0xFFCDD8CC),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8BE6BA), onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF00583E), onPrimaryContainer = Color(0xFFB5F7D5),
    secondary = Color(0xFFBECBAB), onSecondary = Color(0xFF293822),
    secondaryContainer = Color(0xFF4C4218), onSecondaryContainer = Color(0xFFF8E9A0),
    tertiary = Color(0xFF9ECEE3), onTertiary = Color(0xFF003545),
    tertiaryContainer = Color(0xFF144E70), onTertiaryContainer = Color(0xFFCCE9FF),
    background = Color(0xFF111815), onBackground = Color(0xFFE4ECE3),
    surface = Color(0xFF151D19), onSurface = Color(0xFFE4ECE3),
    surfaceContainerLowest = Color(0xFF101612), surfaceContainerLow = Color(0xFF1C2620),
    surfaceContainer = Color(0xFF222E27), surfaceContainerHigh = Color(0xFF2C392F),
    surfaceContainerHighest = Color(0xFF354238),
    surfaceVariant = Color(0xFF354036), onSurfaceVariant = Color(0xFFB9C7B9),
    outline = Color(0xFF8C9B8D), outlineVariant = Color(0xFF404E42),
)

data class BentoColors(val peach: Color, val onPeach: Color, val lavender: Color, val onLavender: Color)
val LocalBentoColors = staticCompositionLocalOf { BentoColors(Color(0xFFFFD2BC), Color(0xFF512B1A), Color(0xFFE3D9FF), Color(0xFF39265B)) }

@Composable fun GoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() }
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalBentoColors provides if (dark) BentoColors(Color(0xFF623B2B), Color(0xFFFFDDCB), Color(0xFF47356C), Color(0xFFEDE0FF)) else BentoColors(Color(0xFFFFD2BC), Color(0xFF512B1A), Color(0xFFE3D9FF), Color(0xFF39265B))) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) Dark else Light,
            motionScheme = MotionScheme.expressive(),
            shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp)),
            typography = Typography(
                displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-1.4).sp),
                headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
                headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-.5).sp),
                headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-.4).sp),
                titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
            ), content = content,
        )
    }
}

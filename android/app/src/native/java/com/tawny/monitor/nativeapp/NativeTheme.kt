package com.tawny.monitor.nativeapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tawny.monitor.R

/**
 * Tawny's own palette (res/values/colors.xml and values-night, the same
 * tokens as the web app's style.css), mapped onto Material 3 roles, with the
 * app's Mukta type. Light and dark follow the system.
 */
@Composable
fun TawnyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val bg = colorResource(R.color.bg)
    val panel = colorResource(R.color.panel)
    val raise = colorResource(R.color.raise)
    val line = colorResource(R.color.line)
    val text = colorResource(R.color.text)
    val dim = colorResource(R.color.dim)
    val berry = colorResource(R.color.berry)
    val sky = colorResource(R.color.sky)
    val live = colorResource(R.color.live)
    val onAccent = colorResource(R.color.on_accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = berry, onPrimary = onAccent, secondary = sky, onSecondary = onAccent,
            background = bg, onBackground = text, surface = bg, onSurface = text,
            surfaceContainerLow = panel, surfaceContainer = panel, surfaceContainerHigh = raise,
            onSurfaceVariant = dim, outline = dim, outlineVariant = line, error = live, onError = onAccent,
        )
    } else {
        lightColorScheme(
            primary = berry, onPrimary = onAccent, secondary = sky, onSecondary = onAccent,
            background = bg, onBackground = text, surface = bg, onSurface = text,
            surfaceContainerLow = panel, surfaceContainer = panel, surfaceContainerHigh = raise,
            onSurfaceVariant = dim, outline = dim, outlineVariant = line, error = live, onError = onAccent,
        )
    }
    val assets = LocalContext.current.assets
    val type = remember(assets) {
        val mukta = FontFamily(
            Font("fonts/Mukta-Regular.ttf", assets, FontWeight.Normal),
            Font("fonts/Mukta-SemiBold.ttf", assets, FontWeight.SemiBold),
        )
        val base = Typography()
        base.copy(
            headlineSmall = base.headlineSmall.copy(fontFamily = mukta, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = mukta, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = mukta, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontFamily = mukta),
            bodyMedium = base.bodyMedium.copy(fontFamily = mukta),
            bodySmall = base.bodySmall.copy(fontFamily = mukta),
            labelLarge = base.labelLarge.copy(fontFamily = mukta, fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.copy(fontFamily = mukta),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

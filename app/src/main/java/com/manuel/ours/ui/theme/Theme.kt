package com.manuel.ours.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.manuel.ours.data.prefs.ThemeMode

private fun schemeFor(c: OursColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.text,
        secondary = c.accent,
        onSecondary = Color.White,
        tertiary = c.positive,
        onTertiary = c.ink,
        error = c.negative,
        onError = c.ink,
        background = c.ink,
        onBackground = c.text,
        surface = c.ink,
        onSurface = c.text,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh,
        outline = c.hairline,
        outlineVariant = c.hairline,
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.text,
        secondary = c.accent,
        onSecondary = Color.White,
        tertiary = c.positive,
        onTertiary = Color.White,
        error = c.negative,
        onError = Color.White,
        background = c.ink,
        onBackground = c.text,
        surface = c.ink,
        onSurface = c.text,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh,
        outline = c.hairline,
        outlineVariant = c.hairline,
    )
}

/** Semantic colours Material's scheme has no slot for. */
data class SpendColors(
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val gradientStart: Color,
    val gradientMid: Color,
    val gradientEnd: Color,
)

val LocalSpendColors = staticCompositionLocalOf {
    SpendColors(
        positive = DarkPalette.Positive,
        negative = DarkPalette.Negative,
        warning = DarkPalette.Warning,
        gradientStart = DarkPalette.Surface,
        gradientMid = DarkPalette.SurfaceHigh,
        gradientEnd = DarkPalette.Surface,
    )
}

@Composable
fun OursTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    tone: ThemeTone = ThemeTone.CRISP,
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit,
) {
    // No Material You. Wallpaper colours would repaint the one accent that carries all
    // the meaning here, and the category hues are contrast-validated against these
    // exact two surfaces — a dynamic scheme silently invalidates that.
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colors = oursColors(dark, tone, accent)
    val scheme = remember(dark, tone, accent) { schemeFor(colors) }

    // Status-bar icons follow *this* theme, not the system's.
    //
    // `enableEdgeToEdge()` in the activity picks its bar style from the system dark
    // mode, which is the wrong source once the app carries its own Light/Dark setting:
    // choosing Light while the phone is in Dark left white icons on a white bar, and
    // the clock and battery simply vanished. Driving it from `dark` — the value every
    // other colour on screen is derived from — is the only way the two cannot disagree.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val spendColors = remember(dark, tone, accent) {
        SpendColors(
            positive = colors.positive,
            negative = colors.negative,
            warning = colors.warning,
            // Flat, not a gradient. Depth here comes from hairlines and near-black
            // layering; a saturated gradient card would fight that.
            gradientStart = colors.surface,
            gradientMid = colors.surfaceHigh,
            gradientEnd = colors.surface,
        )
    }

    CompositionLocalProvider(
        LocalOursColors provides colors,
        LocalSpendColors provides spendColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OursTypography,
            shapes = OursShapes,
            content = content,
        )
    }
}

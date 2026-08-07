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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.manuel.ours.data.prefs.ThemeMode

/**
 * [OursColors] onto Material's scheme.
 *
 * Now that the field names *are* the MD3 roles, this is a near-identity mapping rather than a
 * translation, which is the point of having renamed them: a Material component that reaches for
 * `surfaceContainerHigh` gets the same colour the app's own components do, so a stock
 * `AlertDialog` and a hand-built panel cannot drift apart.
 *
 * `primary` is deliberately the **text** tone. Material uses `primary` for text and icons
 * (`TextButton`, selected labels) far more often than for large fills, and where it does fill —
 * `Button`, `Switch` — those are given `primaryFixed` explicitly at the call site.
 */
private fun schemeFor(c: OursColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.surface,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.primary,
        onSecondary = c.surface,
        secondaryContainer = c.secondaryContainer,
        onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.success,
        onTertiary = c.surface,
        error = c.error,
        onError = c.surface,
        errorContainer = c.error.copy(alpha = 0.18f),
        onErrorContainer = c.error,
        background = c.surface,
        onBackground = c.onSurface,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceContainerHigh,
        onSurfaceVariant = c.onSurfaceVariant,
        surfaceDim = c.surfaceDim,
        surfaceContainerLowest = c.surfaceContainerLowest,
        surfaceContainerLow = c.surfaceContainerLow,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        outline = c.outline,
        outlineVariant = c.outlineVariant,
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimaryFixed,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.primary,
        onSecondary = c.onPrimaryFixed,
        secondaryContainer = c.secondaryContainer,
        onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.success,
        onTertiary = c.onPrimaryFixed,
        error = c.error,
        onError = c.onPrimaryFixed,
        errorContainer = c.error.copy(alpha = 0.12f),
        onErrorContainer = c.error,
        background = c.surface,
        onBackground = c.onSurface,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceContainerHigh,
        onSurfaceVariant = c.onSurfaceVariant,
        surfaceDim = c.surfaceDim,
        surfaceContainerLowest = c.surfaceContainerLowest,
        surfaceContainerLow = c.surfaceContainerLow,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        outline = c.outline,
        outlineVariant = c.outlineVariant,
    )
}

@Composable
fun OursTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    tone: ThemeTone = ThemeTone.CRISP,
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit,
) {
    // No Material You. Wallpaper colours would repaint the one accent that carries all the
    // meaning here, and the category hues are contrast-validated against these exact surfaces —
    // a dynamic scheme silently invalidates that work.
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Both keyed on every input, so switching accent or contrast does not rebuild the scheme on
    // unrelated recompositions.
    val colors = remember(dark, tone, accent) { oursColors(dark, tone, accent) }
    val scheme = remember(colors) { schemeFor(colors) }

    // Status-bar icons follow *this* theme, not the system's.
    //
    // `enableEdgeToEdge()` in the activity picks its bar style from the system dark mode, which
    // is the wrong source once the app carries its own Light/Dark setting: choosing Light while
    // the phone was in Dark left white icons on a white bar, and the clock and battery simply
    // vanished. Driving it from `dark` — the value every other colour on screen is derived from —
    // is the only way the two cannot disagree.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        SideEffect {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalOursColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OursTypography,
            shapes = OursShapes,
            content = content,
        )
    }
}

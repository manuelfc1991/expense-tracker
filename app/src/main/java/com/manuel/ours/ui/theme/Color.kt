package com.manuel.ours.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.manuel.ours.domain.model.Category

/**
 * The v2 palette, taken from `design/ours-mockup-v2.html`.
 *
 * A bank message is a machine-printed line, so the surface it sits on is near-black
 * paper and the structure is carried by hairlines rather than by shadow. Almost
 * everything here is a step of near-black or muted lavender-grey; that restraint is
 * what lets one blue button and one green figure mean something. Add a second accent
 * and both stop meaning anything.
 *
 * Not pure black: #000 beside a lit card makes the edge buzz on OLED and elevation
 * stops reading at all.
 */
object DarkPalette {
    val Ink = Color(0xFF0B0B0F)
    val Surface = Color(0xFF131318)
    val SurfaceHigh = Color(0xFF191920)
    val Hairline = Color(0xFF2A2A34)

    val Text = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFF9A9AAE)

    /** Muted lavender, only ever for the 9sp uppercase captions. */
    val TextLabel = Color(0xFF7C7C99)

    val Accent = Color(0xFF2F5BFF)
    val AccentSoft = Color(0xFF1B2452)

    val Positive = Color(0xFF3DDCA0)
    val Warning = Color(0xFFFFB020)
    val Negative = Color(0xFFFF5C7A)

    /** Untagged, transfers, card bills — anything deliberately not given an identity. */
    val Neutral = Color(0xFF6C7A93)
}

/**
 * Light is **not** an inversion of dark.
 *
 * A statement is black ink on paper, so this is the original the dark theme was
 * derived from, and it gets its own validated hues. Dropping the dark palette onto
 * white fails contrast and turns muddy — the greens and ambers in particular sit far
 * too light to read against #FFFFFF.
 */
object LightPalette {
    val Ink = Color(0xFFF4F4F7)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF2F2F6)
    val Hairline = Color(0xFFE3E3EA)

    val Text = Color(0xFF101014)
    val TextSecondary = Color(0xFF5A5A6E)
    val TextLabel = Color(0xFF79798F)

    val Accent = Color(0xFF2F5BFF)
    val AccentSoft = Color(0xFFE3E9FF)

    val Positive = Color(0xFF0E8F62)
    val Warning = Color(0xFF9A6200)
    val Negative = Color(0xFFCE2C4C)

    val Neutral = Color(0xFF5F6B80)
}

/**
 * One colour per spending category, ordered to match [Category].
 *
 * Five of these — Food, Groceries, Transport, Shopping, Bills — are the approved
 * mockup values and are used verbatim. The other seven were generated to sit in the
 * same measured band rather than picked by eye:
 *
 * - **Hue** is spaced evenly, never closer than 25°, and chosen so the colour carries
 *   the meaning: debt reads red, growth reads green, travel reads teal.
 * - **Lightness and chroma** stay inside the band the approved five already occupy
 *   (L .51–.62 dark). An earlier pass ran brighter and would have glared on OLED and
 *   fought the text, which is the whole reason the band exists.
 * - **A category keeps its hue across themes** and only its lightness is re-stepped
 *   for paper, exactly as the mockup does for its four (44°→40.6°, 165°→165.1°). A
 *   palette that renames its colours when you flip the theme is worse than one that
 *   is merely hard to tell apart.
 *
 * Every colour clears 4.1:1 against its own surface except Shopping, which sits at
 * 2.90:1. That exception is inherited from the approved palette and is tolerable only
 * because colour is never the sole carrier here — every bar and chip has its name and
 * amount printed next to it.
 *
 * Known limit, stated rather than hidden: with twelve categories inside one lightness
 * band, some pairs collapse under colour-blind simulation — Food and Education are
 * nearly identical to a deuteranope. Widening the band does not fix it (the worst pair
 * is Food/Bills, both approved and only 29° apart), so the label beside the colour is
 * what does the work.
 */
private val DarkCategory = mapOf(
    Category.FOOD to Color(0xFFC4571F),
    Category.GROCERIES to Color(0xFF1E9E74),
    Category.TRANSPORT to Color(0xFF2E82BC),
    Category.SHOPPING to Color(0xFF7443BE),
    Category.BILLS to Color(0xFFB2760A),
    Category.RENT to Color(0xFF5E78D9),
    Category.HEALTH to Color(0xFFC0558D),
    Category.EDUCATION to Color(0xFF868604),
    Category.ENTERTAINMENT to Color(0xFFAB5DB2),
    Category.TRAVEL to Color(0xFF009298),
    Category.INVESTMENTS to Color(0xFF519430),
    Category.EMI to Color(0xFFC95463),
)

private val LightCategory = mapOf(
    Category.FOOD to Color(0xFFC24E1D),
    Category.GROCERIES to Color(0xFF128863),
    Category.TRANSPORT to Color(0xFF2478B4),
    Category.SHOPPING to Color(0xFF6B3BB8),
    Category.BILLS to Color(0xFF986300),
    Category.RENT to Color(0xFF4D66CC),
    Category.HEALTH to Color(0xFFB0407D),
    Category.EDUCATION to Color(0xFF757503),
    Category.ENTERTAINMENT to Color(0xFF9C49A4),
    Category.TRAVEL to Color(0xFF048085),
    Category.INVESTMENTS to Color(0xFF3C840C),
    Category.EMI to Color(0xFFBA3D51),
)

/**
 * The full colour set for one theme. Held in a [staticCompositionLocalOf] rather than
 * bolted onto Material's scheme, because Material has no slot for "the colour a
 * category is" or "the colour money you didn't really spend is".
 */
data class OursColors(
    val ink: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textLabel: Color,
    val accent: Color,
    val accentSoft: Color,
    val positive: Color,
    val warning: Color,
    val negative: Color,
    val neutral: Color,
    val isDark: Boolean,
) {
    private val categories get() = if (isDark) DarkCategory else LightCategory

    /**
     * Income is the one category that borrows a semantic colour instead of owning a
     * hue — money arriving is the same "good" the budget ruler and the sync pill use,
     * and giving it a thirteenth hue would say it is just another kind of spending.
     *
     * Transfers, card bills and Other stay deliberately grey. They are the things the
     * summary excludes; a vivid colour would argue for their importance.
     */
    fun forCategory(category: Category): Color = when (category) {
        Category.INCOME -> positive
        Category.TRANSFERS, Category.CARD_PAYMENT, Category.SELF_TRANSFER,
        Category.OTHER -> neutral
        else -> categories[category] ?: neutral
    }
}

private val DarkColors = OursColors(
    ink = DarkPalette.Ink,
    surface = DarkPalette.Surface,
    surfaceHigh = DarkPalette.SurfaceHigh,
    hairline = DarkPalette.Hairline,
    text = DarkPalette.Text,
    textSecondary = DarkPalette.TextSecondary,
    textLabel = DarkPalette.TextLabel,
    accent = DarkPalette.Accent,
    accentSoft = DarkPalette.AccentSoft,
    positive = DarkPalette.Positive,
    warning = DarkPalette.Warning,
    negative = DarkPalette.Negative,
    neutral = DarkPalette.Neutral,
    isDark = true,
)

private val LightColors = OursColors(
    ink = LightPalette.Ink,
    surface = LightPalette.Surface,
    surfaceHigh = LightPalette.SurfaceHigh,
    hairline = LightPalette.Hairline,
    text = LightPalette.Text,
    textSecondary = LightPalette.TextSecondary,
    textLabel = LightPalette.TextLabel,
    accent = LightPalette.Accent,
    accentSoft = LightPalette.AccentSoft,
    positive = LightPalette.Positive,
    warning = LightPalette.Warning,
    negative = LightPalette.Negative,
    neutral = LightPalette.Neutral,
    isDark = false,
)

/**
 * How hard the theme is on the eyes.
 *
 * The palette this app started with is deliberately high contrast — near-black paper,
 * near-white ink, about 18:1 — because a statement is machine-printed and that is what
 * printed looks like. It is also a lot to read for an hour.
 *
 * [SOFT] lifts the ground and drops the ink toward each other. It stays well clear of
 * the 4.5:1 floor for body text; what it gives up is the glare, not the legibility.
 * Nothing else moves — the category hues are contrast-validated against these surfaces
 * and re-tinting them would invalidate that work for the sake of a preference.
 */
enum class ThemeTone { CRISP, SOFT }

/**
 * The one colour that carries meaning, and the only one that is a matter of taste.
 *
 * Constrained on purpose to the blue-through-violet arc plus teal. Green means money
 * arriving here, amber means a warning and red means a loss, so an accent borrowed from
 * any of those would make the interface say something it does not mean. That rules out
 * most of the wheel, and what is left is what is offered.
 */
enum class AccentColor(val label: String, val dark: Color, val light: Color) {
    BLUE("Blue", Color(0xFF2F5BFF), Color(0xFF2F5BFF)),
    INDIGO("Indigo", Color(0xFF5B4BE0), Color(0xFF4E3FD0)),
    VIOLET("Violet", Color(0xFF8A4BD8), Color(0xFF7B3DC8)),
    PLUM("Plum", Color(0xFFA8459B), Color(0xFF97388B)),
    TEAL("Teal", Color(0xFF0E9C93), Color(0xFF0B7E77)),
    SKY("Sky", Color(0xFF1E86D6), Color(0xFF1A76C0));

    fun on(isDark: Boolean): Color = if (isDark) dark else light
}

/** A mix, for deriving the soft accent and the softened surfaces. */
private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

fun oursColors(
    dark: Boolean,
    tone: ThemeTone = ThemeTone.CRISP,
    accent: AccentColor = AccentColor.BLUE,
): OursColors {
    val base = if (dark) DarkColors else LightColors
    val hue = accent.on(dark)
    val withAccent = base.copy(
        accent = hue,
        // Derived rather than hand-picked: on dark it is the accent sunk almost into the
        // ground, on paper it is the accent lifted almost to white. Either way it is a
        // tint of the same hue, so a new accent cannot leave a stale container behind.
        accentSoft = if (dark) mix(hue, base.ink, 0.78f) else mix(hue, Color.White, 0.88f),
    )
    if (tone == ThemeTone.CRISP) return withAccent

    // Soft: bring ground and ink toward each other, and warm the paper slightly. The
    // steps are small deliberately — enough to take the glare off, not enough to make
    // the hairlines disappear, which is what carries every layout in this app.
    return if (dark) withAccent.copy(
        ink = Color(0xFF16161C),
        surface = Color(0xFF1D1D24),
        surfaceHigh = Color(0xFF23232B),
        hairline = Color(0xFF32323D),
        text = Color(0xFFE0E0E8),
        textSecondary = Color(0xFF9494A6),
    ) else withAccent.copy(
        ink = Color(0xFFF2EFE9),
        surface = Color(0xFFFBF9F5),
        surfaceHigh = Color(0xFFF0EDE6),
        hairline = Color(0xFFE0DCD2),
        text = Color(0xFF23211D),
        textSecondary = Color(0xFF615E57),
    )
}

val LocalOursColors = staticCompositionLocalOf { DarkColors }

/** Shorthand for the current theme's colour set. */
val Ours: OursColors
    @Composable @ReadOnlyComposable get() = LocalOursColors.current

@Composable
@ReadOnlyComposable
fun colorForCategory(category: Category): Color = LocalOursColors.current.forCategory(category)

@Composable
@ReadOnlyComposable
fun colorForCategory(ordinal: Int): Color =
    LocalOursColors.current.forCategory(Category.entries[ordinal.coerceIn(Category.entries.indices)])

/**
 * A merchant has no category of its own, so its avatar tint is hashed from the name.
 * Deterministic on purpose — a merchant that changed colour between screens would read
 * as two different merchants.
 */
@Composable
@ReadOnlyComposable
fun colorForMerchant(name: String): Color {
    val palette = if (LocalOursColors.current.isDark) DarkCategory else LightCategory
    val hues = palette.values.toList()
    var hash = 0
    for (char in name) hash = char.code + ((hash shl 5) - hash)
    return hues[kotlin.math.abs(hash) % hues.size]
}

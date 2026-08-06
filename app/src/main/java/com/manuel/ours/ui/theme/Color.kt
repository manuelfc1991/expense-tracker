package com.manuel.ours.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.manuel.ours.domain.model.Category

/**
 * The v7 palette. Field names are Material 3 roles, so [schemeFor] is a near-identity
 * mapping rather than a translation, and `design/v7/design-system.html` carries the
 * measured contrast table for every pair below.
 *
 * A bank message is a machine-printed line, so the surface it sits on is near-black paper
 * and the structure is carried by hairlines rather than by shadow. Almost everything here is
 * a step of near-black or muted lavender-grey; that restraint is what lets one blue button
 * and one green figure mean something. Add a second accent and both stop meaning anything.
 *
 * Not pure black: #000 beside a lit surface makes the edge buzz on OLED and elevation stops
 * reading at all.
 *
 * ## The two blues
 *
 * The brand accent `#2F5BFF` is a **surface** colour — white sits on it at 5.17:1 and it
 * makes a good button. As **text** on near-black it is 3.80:1, and this app sets accent text
 * everywhere: "Sort", "Edit", "See all", "Put back", every accent-coloured caption. All of
 * that was failing AA.
 *
 * So the role is split, which is exactly what MD3's *fixed* roles are for.
 * [OursColors.primary] is the accent as text and icon — a light tone on dark, a dark tone on
 * paper. [OursColors.primaryFixed] is the brand blue, the same in both themes, used only
 * where something is filled and [OursColors.onPrimaryFixed] sits on it.
 *
 * **Reaching for the wrong one is the easiest mistake to make here.** Fill a button with
 * `primary` and you get pale blue with white text on it; write a caption in `primaryFixed`
 * and you are back to 3.80:1.
 */
object DarkPalette {
    val Surface = Color(0xFF0F0F14)
    val SurfaceDim = Color(0xFF08080C)
    val SurfaceContainerLowest = Color(0xFF0A0A0E)
    val SurfaceContainerLow = Color(0xFF15151B)
    val SurfaceContainer = Color(0xFF1A1A21)
    val SurfaceContainerHigh = Color(0xFF232329)
    val SurfaceContainerHighest = Color(0xFF2D2D35)

    val OnSurface = Color(0xFFF2F2F7)          // 17.13:1
    val OnSurfaceVariant = Color(0xFFB6B6C8)   //  9.57:1
    val OnSurfaceMuted = Color(0xFF9092A6)     //  6.23:1 — was #7C7C99 at 4.59:1

    /** Interactive borders. Needs 3:1, not 4.5:1 — it is never text. */
    val Outline = Color(0xFF8B8B9E)

    /** The hairline that carries every layout. Decorative, 1.43:1, never text. */
    val OutlineVariant = Color(0xFF2E2E3A)

    val SecondaryContainer = Color(0xFF2A2A38)
    val OnSecondaryContainer = Color(0xFFE2E2F0)

    val Success = Color(0xFF5BE3AF)            // 11.88:1 — money arriving, under budget
    val Warning = Color(0xFFFFC24D)            // 11.90:1 — not running, not yet wrong
    val Error = Color(0xFFFF8FA3)              //  8.84:1 — a figure that is wrong, a loss

    /** Untagged, transfers, card bills — anything deliberately not given an identity. */
    val Neutral = Color(0xFF9BA8BE)
}

/**
 * Light is **not** an inversion of dark.
 *
 * A statement is black ink on paper, so this is the original the dark theme was derived
 * from, and it gets its own validated hues. Dropping the dark palette onto white fails
 * contrast and turns muddy — the greens and ambers in particular sit far too light to read
 * against white.
 */
object LightPalette {
    val Surface = Color(0xFFFAFAFC)
    val SurfaceDim = Color(0xFFDEDEE6)
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF4F4F8)
    val SurfaceContainer = Color(0xFFEEEEF3)
    val SurfaceContainerHigh = Color(0xFFE8E8EF)
    val SurfaceContainerHighest = Color(0xFFE1E1EA)

    val OnSurface = Color(0xFF101014)          // 18.21:1
    val OnSurfaceVariant = Color(0xFF494A5C)   //  8.33:1
    val OnSurfaceMuted = Color(0xFF5C5D70)     //  6.19:1 — was #79798F at 4.25:1

    val Outline = Color(0xFF74758A)
    val OutlineVariant = Color(0xFFD3D3DD)

    val SecondaryContainer = Color(0xFFE0E2F0)
    val OnSecondaryContainer = Color(0xFF1A1B2A)

    val Success = Color(0xFF0A7050)            //  5.85:1
    val Warning = Color(0xFF7A4A00)            //  7.18:1
    val Error = Color(0xFFB3123A)              //  6.57:1

    val Neutral = Color(0xFF4F5A6E)
}

/**
 * One colour per spending category, chosen against a measured objective.
 *
 * **Hue** carries the meaning — debt reads red, growth green, travel teal — and is anchored
 * within ±9° of it, so a category never changes identity between themes.
 *
 * **Lightness is allowed to vary**, and that is the change that matters. The palette this
 * replaces held every category inside one lightness band on purpose, and that is precisely
 * what made them collapse for a colour-blind reader: once hue information is gone, lightness
 * is nearly all the separation left. The old set left Food and Education all but identical to
 * a deuteranope — a limit its own source comments named and accepted.
 *
 * Validated by search against two constraints: every colour clears **4.5:1** on every surface it
 * can land on, in both the crisp and the softened theme, and the 66 pairs are pushed as far apart
 * as possible under protanopia, deuteranopia and tritanopia. `PaletteContrastTest` asserts both.
 *
 * ## The two themes do not reach the same standard, and cannot
 *
 * Dark clears **ΔE 10** on all 66 pairs — nothing collapses. Light reaches **ΔE 8**, and two
 * pairs sit between 8 and 10: Food/Education for a deuteranope and Groceries/Travel for a
 * tritanope.
 *
 * That asymmetry is structural rather than a lack of effort. Clearing 4.5:1 against paper forces
 * every ink *dark* — lightness roughly 0.16–0.32 — and lightness is most of what separates two
 * colours once hue information is gone. On near-black the same floor allows 0.50–0.82, three
 * times the range to work with. A global re-optimisation under the tighter constraint came back
 * worse (seven collapsed pairs), and pushing the two offenders to near-black bought ΔE 8.6 for a
 * green nobody would call groceries.
 *
 * What makes it acceptable is principle six: **colour is never the only carrier.** Every place a
 * category hue appears, its name appears with it — the chip, the grid cell, the ranked bar, the
 * Rules header — and the mark carries a distinct glyph as well as a tint. A reader who cannot
 * separate two of these hues has two other cues on the same line.
 *
 * Two further costs, stated rather than hidden. On paper some hues drift from their meaning —
 * Bills is a dark olive-brown rather than amber — and on dark, Education and Fun run brighter
 * than the old band, so they are the two to watch on OLED at night. The floors to preserve if any
 * of this is re-tuned are 4.5:1 everywhere, ΔE 10 on dark and ΔE 8 on light — not any hex.
 */
private val DarkCategory = mapOf(
    Category.FOOD to Color(0xFFD37E3D),
    Category.GROCERIES to Color(0xFF30CF95),
    Category.TRANSPORT to Color(0xFF74AAD1),
    Category.SHOPPING to Color(0xFFAA76CB),
    Category.BILLS to Color(0xFFD7B762),
    Category.RENT to Color(0xFF6286E5),
    Category.HEALTH to Color(0xFFE85395),
    Category.EDUCATION to Color(0xFFC9DA5C),
    Category.ENTERTAINMENT to Color(0xFFE048E7),
    Category.TRAVEL to Color(0xFF82D9E2),
    Category.INVESTMENTS to Color(0xFF65CE31),
    Category.EMI to Color(0xFFDA6776),
)

private val LightCategory = mapOf(
    Category.FOOD to Color(0xFF855028),
    Category.GROCERIES to Color(0xFF147151),
    Category.TRANSPORT to Color(0xFF0C2E46),
    Category.SHOPPING to Color(0xFF731DAD),
    Category.BILLS to Color(0xFF3F3312),
    Category.RENT to Color(0xFF132274),
    Category.HEALTH to Color(0xFFB00949),
    Category.EDUCATION to Color(0xFF5C690E),
    Category.ENTERTAINMENT to Color(0xFF691169),
    Category.TRAVEL to Color(0xFF115B67),
    Category.INVESTMENTS to Color(0xFF205205),
    Category.EMI to Color(0xFF851B2C),
)

/**
 * The full colour set for one theme. Held in a [staticCompositionLocalOf] rather than bolted
 * onto Material's scheme, because Material has no slot for "the colour a category is" or
 * "the colour money you didn't really spend is".
 */
data class OursColors(
    val surface: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceMuted: Color,
    val outline: Color,
    val outlineVariant: Color,
    /** The accent as **text and icon**. Never fill anything with this. */
    val primary: Color,
    /** The accent as a **fill**. [onPrimaryFixed] is the only thing that sits on it. */
    val primaryFixed: Color,
    val onPrimaryFixed: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val neutral: Color,
    val isDark: Boolean,
) {
    private val categories get() = if (isDark) DarkCategory else LightCategory

    /**
     * Income is the one category that borrows a semantic colour instead of owning a hue —
     * money arriving is the same "good" the budget ruler and the sync pill use, and giving it
     * a thirteenth hue would say it is just another kind of spending.
     *
     * Transfers, card bills and Other stay deliberately grey. They are the things the summary
     * excludes; a vivid colour would argue for their importance.
     */
    fun forCategory(category: Category): Color = when (category) {
        Category.INCOME -> success
        Category.TRANSFERS, Category.CARD_PAYMENT, Category.SELF_TRANSFER,
        Category.OTHER -> neutral
        else -> categories[category] ?: neutral
    }
}

/**
 * How hard the theme is on the eyes.
 *
 * The palette this app started with is deliberately high contrast — near-black paper,
 * near-white ink, about 17:1 — because a statement is machine-printed and that is what
 * printed looks like. It is also a lot to read for an hour.
 *
 * [SOFT] lifts the ground and drops the ink toward each other. It stays well clear of the
 * 4.5:1 floor for body text and for the small-caps captions; what it gives up is the glare,
 * not the legibility. The category hues do not move — they are contrast-validated against
 * these surfaces too, and re-tinting them for a preference would invalidate that work.
 */
enum class ThemeTone { CRISP, SOFT }

/**
 * The one colour that carries meaning, and the only one that is a matter of taste.
 *
 * Constrained on purpose to the blue-through-violet arc plus teal. Green means money arriving
 * here, amber means a warning and red means a loss, so an accent borrowed from any of those
 * would make the interface say something it does not mean.
 *
 * Each option carries **three** values, because the accent has two jobs. [fill] is the button,
 * and white sits on it — every one of these clears 4.5:1, which two of them did not before:
 * Teal was 3.39:1 and Sky 3.86:1 with white on them, so both were darkened until they passed.
 * [textDark] and [textLight] are the same accent as text, at a tone that is legible on the
 * theme's own surfaces.
 */
enum class AccentColor(
    val label: String,
    val fill: Color,
    val textDark: Color,
    val textLight: Color,
) {
    BLUE("Blue", Color(0xFF2F5BFF), Color(0xFFB3C4FF), Color(0xFF1F44D6)),
    INDIGO("Indigo", Color(0xFF5B4BE0), Color(0xFFC6BEFF), Color(0xFF4436C4)),
    VIOLET("Violet", Color(0xFF8A4BD8), Color(0xFFDCBEFF), Color(0xFF6E2FB8)),
    PLUM("Plum", Color(0xFFA8459B), Color(0xFFF2B4E4), Color(0xFF8B2A80)),
    // textLight deepened from #00706A, which was 4.27:1 on the softened paper's highest
    // container. Teal is the one accent whose text tone had no margin to give.
    TEAL("Teal", Color(0xFF0C837B), Color(0xFF6FE0D5), Color(0xFF00655F)),
    SKY("Sky", Color(0xFF1B79C1), Color(0xFF93CBFF), Color(0xFF0B5F9E));

    fun textOn(isDark: Boolean): Color = if (isDark) textDark else textLight
}

/** A mix, for deriving the accent container and the softened surfaces. */
private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun darkColors(accent: AccentColor) = OursColors(
    surface = DarkPalette.Surface,
    surfaceDim = DarkPalette.SurfaceDim,
    surfaceContainerLowest = DarkPalette.SurfaceContainerLowest,
    surfaceContainerLow = DarkPalette.SurfaceContainerLow,
    surfaceContainer = DarkPalette.SurfaceContainer,
    surfaceContainerHigh = DarkPalette.SurfaceContainerHigh,
    surfaceContainerHighest = DarkPalette.SurfaceContainerHighest,
    onSurface = DarkPalette.OnSurface,
    onSurfaceVariant = DarkPalette.OnSurfaceVariant,
    onSurfaceMuted = DarkPalette.OnSurfaceMuted,
    outline = DarkPalette.Outline,
    outlineVariant = DarkPalette.OutlineVariant,
    primary = accent.textOn(true),
    primaryFixed = accent.fill,
    onPrimaryFixed = Color.White,
    // Derived rather than hand-picked: the accent sunk almost into the ground, so a new
    // accent cannot leave a stale container behind.
    primaryContainer = mix(accent.fill, DarkPalette.Surface, 0.78f),
    onPrimaryContainer = mix(accent.textOn(true), Color.White, 0.35f),
    secondaryContainer = DarkPalette.SecondaryContainer,
    onSecondaryContainer = DarkPalette.OnSecondaryContainer,
    success = DarkPalette.Success,
    warning = DarkPalette.Warning,
    error = DarkPalette.Error,
    neutral = DarkPalette.Neutral,
    isDark = true,
)

private fun lightColors(accent: AccentColor) = OursColors(
    surface = LightPalette.Surface,
    surfaceDim = LightPalette.SurfaceDim,
    surfaceContainerLowest = LightPalette.SurfaceContainerLowest,
    surfaceContainerLow = LightPalette.SurfaceContainerLow,
    surfaceContainer = LightPalette.SurfaceContainer,
    surfaceContainerHigh = LightPalette.SurfaceContainerHigh,
    surfaceContainerHighest = LightPalette.SurfaceContainerHighest,
    onSurface = LightPalette.OnSurface,
    onSurfaceVariant = LightPalette.OnSurfaceVariant,
    onSurfaceMuted = LightPalette.OnSurfaceMuted,
    outline = LightPalette.Outline,
    outlineVariant = LightPalette.OutlineVariant,
    primary = accent.textOn(false),
    primaryFixed = accent.fill,
    onPrimaryFixed = Color.White,
    primaryContainer = mix(accent.fill, Color.White, 0.88f),
    onPrimaryContainer = mix(accent.textOn(false), Color.Black, 0.45f),
    secondaryContainer = LightPalette.SecondaryContainer,
    onSecondaryContainer = LightPalette.OnSecondaryContainer,
    success = LightPalette.Success,
    warning = LightPalette.Warning,
    error = LightPalette.Error,
    neutral = LightPalette.Neutral,
    isDark = false,
)

fun oursColors(
    dark: Boolean,
    tone: ThemeTone = ThemeTone.CRISP,
    accent: AccentColor = AccentColor.BLUE,
): OursColors {
    val base = if (dark) darkColors(accent) else lightColors(accent)
    if (tone == ThemeTone.CRISP) return base

    // Soft: bring ground and ink toward each other, and warm the paper slightly. The steps
    // are small deliberately — enough to take the glare off, not enough to make the hairlines
    // disappear, which is what carries every layout in this app. Every value below was
    // re-measured against the softened surfaces rather than nudged by eye.
    return if (dark) base.copy(
        surface = Color(0xFF16161C),
        surfaceDim = Color(0xFF101015),
        surfaceContainerLowest = Color(0xFF121218),
        surfaceContainerLow = Color(0xFF1B1B22),
        surfaceContainer = Color(0xFF1F1F27),
        // Not lifted past the crisp value, unlike the surfaces below it.
        //
        // Soft raises the ground and lowers the ink, but a category mark still has to clear
        // 4.5:1 on whatever it sits on — and at #2A2A32 five of the twelve dropped to about
        // 4.1:1. The elevated containers are where a light-on-dark category tint has least room,
        // so this one stops where the crisp theme's does. The comfort comes from the page, which
        // is most of the pixels; nothing is gained by softening a dialog into illegibility.
        surfaceContainerHigh = Color(0xFF232329),
        surfaceContainerHighest = Color(0xFF2D2D35),
        onSurface = Color(0xFFE4E4EC),          // 11.25:1 on the softened ground
        onSurfaceVariant = Color(0xFFB0B0C2),   //  6.66:1
        onSurfaceMuted = Color(0xFF9496A8),     //  4.87:1
        outlineVariant = Color(0xFF34343F),
    ) else base.copy(
        // Paper, not a warmed sheet of white.
        //
        // Glare on a light theme comes from the sheer area of lit pixels, so this steps the
        // ground down about 5% and the panels with it. Any further and it stops reading as
        // paper and starts reading as a screen that needs cleaning.
        surface = Color(0xFFF3F0E8),
        surfaceDim = Color(0xFFDDD9CE),
        surfaceContainerLowest = Color(0xFFFBF9F3),
        surfaceContainerLow = Color(0xFFEFEBE2),
        surfaceContainer = Color(0xFFEDE9E0),
        surfaceContainerHigh = Color(0xFFE6E2D8),
        surfaceContainerHighest = Color(0xFFDFDACE),
        onSurface = Color(0xFF1B1A17),          // 13.45:1
        onSurfaceVariant = Color(0xFF4C4A44),   //  6.85:1
        // 5.17:1 rather than the 3.9:1 the old warmed paper gave its captions. A mode whose
        // whole purpose is comfort should not be the one that fails the contrast floor, and
        // the small-caps caption is exactly the text that needs the help.
        onSurfaceMuted = Color(0xFF5E5C55),     //  5.17:1
        outlineVariant = Color(0xFFD2CDC0),
    )
}

val LocalOursColors = staticCompositionLocalOf { darkColors(AccentColor.BLUE) }

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
 * Deterministic on purpose — a merchant that changed colour between screens would read as two
 * different merchants.
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

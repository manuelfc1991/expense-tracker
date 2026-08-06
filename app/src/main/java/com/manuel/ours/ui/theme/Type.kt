package com.manuel.ours.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tabular figures, everywhere a number appears.
 *
 * Without this a column of rupee values visibly jitters as digits change, and the right-hand
 * amount column — the thing that makes the list read as a statement rather than a feed —
 * stops lining up at all.
 */
private const val TABULAR = "tnum"

val OursTypography = Typography(
    // The one very large number. Everything else is restrained specifically so this can be
    // this big; the jump from an 11sp label to a 44sp figure *is* the hierarchy.
    displayLarge = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = (-2.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    // The summary's net figure and the detail screen's amount. A step down, because it carries
    // a sign and needs the room for it without wrapping.
    displayMedium = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-1.4).sp,
        fontFeatureSettings = TABULAR,
    ),
    // The figure a bottom sheet leads with. A sheet is not a screen: it sits over one, keeps
    // its own grab handle above it, and still has a grid and two buttons to fit underneath.
    displaySmall = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.8).sp,
        fontFeatureSettings = TABULAR,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // The merchant name on an entry row, at SemiBold.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
    ),
)

/**
 * The small uppercase caption that sits **above** every value — "SPENT THIS MONTH", "BUDGET",
 * "LEFT". Never beside: the eye should land on the number first and only read the caption if
 * it needs to.
 *
 * **11sp, not 9sp.** This is the most-used style in the app — it captions every figure on
 * every screen — and 9sp is below Material's own `labelSmall` floor and hard for a lot of
 * readers. The wide tracking is what signals "this is a deliberate caption rather than body
 * text someone forgot to enlarge", so nothing is lost by giving it the extra two points.
 *
 * The cost is real and was budgeted for: roughly 4–6dp of extra height per captioned figure,
 * and about 3 characters off the width of a row caption. The row caption is one line with
 * ellipsis, so anything that overflows loses its tail rather than wrapping — which is why the
 * captions were re-composed to fit 25 characters. See `design/v7/design-system.html`.
 */
val MicroLabelStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.5.sp,
)

/** The figure a bottom sheet leads with. Kept as a name for the call sites that read better. */
val SheetAmountStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.8).sp,
    fontFeatureSettings = TABULAR,
)

/** The amount in the shared right-hand column. Bold, tabular, right-aligned. */
val AmountTextStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    fontFeatureSettings = TABULAR,
)

/** A value under a [MicroLabelStyle] caption — the "₹32,000" in a BUDGET/USED/LEFT triple. */
val ValueTextStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    fontFeatureSettings = TABULAR,
)

/** "OURS" in the top-left. Tracked out far enough to read as a mark, not a word. */
val WordmarkStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    letterSpacing = 3.4.sp,
)

/** The month stepper — "AUGUST 2026". */
val MonthTitleStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 1.8.sp,
)

/** Chart axis ends, e.g. the "1" and "31" under the daily columns. */
val AxisLabelStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    letterSpacing = 0.8.sp,
    fontFeatureSettings = TABULAR,
)

/** Status pills: SYNCED, 3 WAITING, OFF. */
val PillTextStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    letterSpacing = 1.0.sp,
)

/**
 * The Material 3 shape scale, exactly.
 *
 * The set this replaces (6/9/13/16/22dp) sat near these values without landing on them, so
 * nothing could be reused between two components without someone making a rounding decision.
 *
 * extraSmall snackbar · small chips, grid cells, small fields · medium panels, notices,
 * the action card · large FAB · extraLarge dialogs and bottom sheets.
 */
val OursShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The 4dp baseline grid, and the one screen gutter.
 *
 * Every screen used to declare its own `private val EDGE = 15.dp`, eight times over, off-grid.
 * One token, on the grid, in one place.
 */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp

    /** The screen gutter. Was 15dp. */
    val edge = 16.dp

    /**
     * The Material minimum touch target, and the absolute floor for two adjacent controls.
     *
     * Named so they can be cited in review rather than re-argued. The build this replaces
     * tapped bare glyphs — a 16dp back arrow, a 13dp bill dismiss, a 9dp rule-chip delete on a
     * destructive action — all of them under half the minimum.
     */
    val target = 48.dp
    val targetTight = 40.dp
}

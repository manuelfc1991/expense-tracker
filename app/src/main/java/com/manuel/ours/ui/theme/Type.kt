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
 * Without this a column of rupee values visibly jitters as digits change, and the
 * right-hand amount column — the thing that makes the list read as a statement rather
 * than a feed — stops lining up at all.
 */
private const val TABULAR = "tnum"

val OursTypography = Typography(
    // The one very large number. Everything else is restrained specifically so this
    // can be this big; the jump from a 9sp label to a 48sp figure *is* the hierarchy.
    displayLarge = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 44.sp,
        letterSpacing = (-2.6).sp,
        fontFeatureSettings = TABULAR,
    ),
    // The summary's net figure. A step down, because it carries a sign and needs the
    // room for it without wrapping.
    displayMedium = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 38.sp,
        letterSpacing = (-2.0).sp,
        fontFeatureSettings = TABULAR,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
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
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // The merchant name on an entry row: 13px semibold in the mockup.
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
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = OursMono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 1.6.sp,
    ),
)

/**
 * The small uppercase caption that sits **above** every value — "SPENT THIS MONTH",
 * "BUDGET", "LEFT". Never beside: the eye should land on the number first and only
 * read the caption if it needs to.
 *
 * The wide tracking is what makes 9sp read as a deliberate caption rather than as
 * body text someone forgot to enlarge.
 */
val MicroLabelStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 1.6.sp,
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
    letterSpacing = 3.6.sp,
)

/** The month stepper — "JULY 2026". */
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
    fontSize = 9.sp,
    letterSpacing = 0.9.sp,
    fontFeatureSettings = TABULAR,
)

/** Status pills: SYNCED, 3 WAITING, OFF. */
val PillTextStyle = TextStyle(
    fontFamily = OursMono,
    fontWeight = FontWeight.Medium,
    fontSize = 9.sp,
    letterSpacing = 1.1.sp,
)

val OursShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

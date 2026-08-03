package com.manuel.ours.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.manuel.ours.R

/**
 * The one monospace face, shipped with the app rather than borrowed from the OS.
 *
 * `FontFamily.Monospace` resolves to whatever the manufacturer happens to ship — Droid
 * Sans Mono on most phones, something else on others. Since nearly every number and
 * every small-caps label in this app is set in mono, that made the app look materially
 * different from the design on every device, and different from itself across devices.
 *
 * The face is a subset of **Noto Sans Mono** (SIL OFL 1.1, `licenses/`), cut to the
 * ~235 characters this app can actually render — 18 KB a weight instead of 396.
 *
 * ### Why not JetBrains Mono, which the mockup names first
 *
 * It has no ₹ (U+20B9). Every amount in this app begins with one, so the sign would be
 * drawn from a fallback face beside JetBrains Mono digits — two different designs, two
 * different weights, in the 48sp figure that is the whole hierarchy of the home screen.
 * Roboto Mono, the mockup's next choice, is no better: Google serves a 228-glyph Latin
 * subset that also omits it. Noto Sans Mono is the same class of grotesque mono and
 * draws ₹ itself, so the sign and the digits are one typeface.
 */
val OursMono = FontFamily(
    Font(R.font.ours_mono_regular, FontWeight.Normal),
    Font(R.font.ours_mono_medium, FontWeight.Medium),
    Font(R.font.ours_mono_bold, FontWeight.Bold),
)

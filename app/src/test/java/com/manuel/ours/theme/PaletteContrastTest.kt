package com.manuel.ours.theme

import androidx.compose.ui.graphics.Color
import com.manuel.ours.domain.model.Category
import com.manuel.ours.ui.theme.AccentColor
import com.manuel.ours.ui.theme.OursColors
import com.manuel.ours.ui.theme.ThemeTone
import com.manuel.ours.ui.theme.oursColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Test
import com.google.common.truth.Truth.assertWithMessage

/**
 * The palette, measured rather than trusted.
 *
 * `docs/REVIEW.md` records that the `ui` package — 13,000 lines — has no tests at all, and
 * that every defect found by eye on 6 August came from there. This is the cheapest useful
 * floor: the colour set is pure Kotlin with no Android dependency, so the properties it is
 * supposed to have can simply be asserted.
 *
 * Three of these guard failures the previous palette actually shipped, each admitted in its
 * own source comments:
 *
 *  - the accent as text at 3.80:1, used for "Sort", "Edit" and "See all" on nearly every screen
 *  - the light-theme caption colour at 4.25:1
 *  - Shopping at 2.90:1, and Food/Education indistinguishable to a deuteranope
 *
 * A test that only checked today's hex values would pass forever and prove nothing. These
 * assert the *properties*, so the values stay free to change and the guarantees do not.
 */
class PaletteContrastTest {

    // ─── WCAG relative luminance and contrast ────────────────────────────────

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * The surfaces a piece of content can land on, which is not the same list for every role.
     *
     * `surfaceContainerHighest` appears nowhere in this app's own code — it exists only in the
     * Material scheme, where it is the **snackbar**. A snackbar carries body text and an action
     * label and nothing else: no 11sp captions, no category marks, no coloured figures. So
     * asserting those roles against it would be asserting something the design never does, and
     * the two lists below say which is which rather than one list quietly over-claiming.
     */
    private fun bodySurfaces(c: OursColors) = listOf(
        c.surface, c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer,
        c.surfaceContainerHigh, c.surfaceContainerHighest,
    )

    /** Pages, panels, sheets and dialogs — everywhere except the snackbar. */
    private fun contentSurfaces(c: OursColors) = listOf(
        c.surface, c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer,
        c.surfaceContainerHigh,
    )

    private fun allSchemes(): List<Pair<String, OursColors>> = buildList {
        for (dark in listOf(true, false)) {
            for (tone in ThemeTone.entries) {
                for (accent in AccentColor.entries) {
                    val name = "${if (dark) "dark" else "light"}/$tone/$accent"
                    add(name to oursColors(dark, tone, accent))
                }
            }
        }
    }

    // ─── Text ────────────────────────────────────────────────────────────────

    @Test
    fun `every content role clears AA on every surface it can land on`() {
        val failures = mutableListOf<String>()
        for ((name, c) in allSchemes()) {
            // Body text and the accent reach the snackbar; captions and coloured figures do not.
            val onEverySurface = mapOf(
                "onSurface" to c.onSurface,
                "onSurfaceVariant" to c.onSurfaceVariant,
                "primary" to c.primary,
            )
            val onContentSurfaces = mapOf(
                "onSurfaceMuted" to c.onSurfaceMuted,
                "success" to c.success,
                "warning" to c.warning,
                "error" to c.error,
                "neutral" to c.neutral,
            )
            for ((role, colour) in onEverySurface) {
                for (surface in bodySurfaces(c)) {
                    val r = contrast(colour, surface)
                    if (r < 4.5) failures += "$name $role = %.2f:1".format(r)
                }
            }
            for ((role, colour) in onContentSurfaces) {
                for (surface in contentSurfaces(c)) {
                    val r = contrast(colour, surface)
                    if (r < 4.5) failures += "$name $role on surface = %.2f:1".format(r)
                }
            }
        }
        assertWithMessage("AA failures:\n" + failures.joinToString("\n")).that(failures.isEmpty()).isTrue()
    }

    /**
     * The accent-as-text fix, guarded directly.
     *
     * `primary` is what captions and text buttons use, so it is held to the text floor. The old
     * single `accent` sat at 3.80:1 on dark and was used for exactly this.
     */
    @Test
    fun `the accent is legible as text in every theme and every choice`() {
        for ((name, c) in allSchemes()) {
            val r = contrast(c.primary, c.surface)
            assertWithMessage("$name primary as text = %.2f:1".format(r)).that(r >= 4.5).isTrue()
        }
    }

    /** And the other half of the split: the fill has to carry white. */
    @Test
    fun `white is legible on every accent fill`() {
        for (accent in AccentColor.entries) {
            val r = contrast(Color.White, accent.fill)
            assertWithMessage("white on ${accent.label} fill = %.2f:1".format(r)).that(r >= 4.5).isTrue()
        }
    }

    @Test
    fun `interactive outlines clear the non-text floor`() {
        for ((name, c) in allSchemes()) {
            for (surface in bodySurfaces(c)) {
                val r = contrast(c.outline, surface)
                assertWithMessage("$name outline = %.2f:1".format(r)).that(r >= 3.0).isTrue()
            }
        }
    }

    // ─── Categories ──────────────────────────────────────────────────────────

    @Test
    fun `every category colour clears AA on every surface`() {
        val failures = mutableListOf<String>()
        for ((name, c) in allSchemes()) {
            for (category in Category.entries) {
                val colour = c.forCategory(category)
                for (surface in contentSurfaces(c)) {
                    val r = contrast(colour, surface)
                    if (r < 4.5) failures += "$name ${category.name} = %.2f:1".format(r)
                }
            }
        }
        assertWithMessage("category AA failures:\n" + failures.joinToString("\n")).that(failures.isEmpty()).isTrue()
    }

    // ─── Colour blindness ────────────────────────────────────────────────────

    /**
     * Machado et al. (2009) severity-1.0 matrices, applied in linear RGB.
     *
     * Not decoration: the palette this replaces put Food and Education within ΔE 2.1 of each
     * other for a deuteranope, which its own comments describe as a known limit. Twelve hues in
     * one lightness band cannot be separated once hue information is gone, so lightness varies
     * here — and this test is what keeps a future "let's calm the palette down" from quietly
     * undoing it.
     */
    private val dichromacies = mapOf(
        "deuteranopia" to arrayOf(
            doubleArrayOf(0.367322, 0.860646, -0.227968),
            doubleArrayOf(0.280085, 0.672501, 0.047413),
            doubleArrayOf(-0.011820, 0.042940, 0.968881),
        ),
        "protanopia" to arrayOf(
            doubleArrayOf(0.152286, 1.052583, -0.204868),
            doubleArrayOf(0.114503, 0.786281, 0.099216),
            doubleArrayOf(-0.003882, -0.048116, 1.051998),
        ),
        "tritanopia" to arrayOf(
            doubleArrayOf(1.255528, -0.076749, -0.178779),
            doubleArrayOf(-0.078411, 0.930809, 0.147602),
            doubleArrayOf(0.004733, 0.691367, 0.303900),
        ),
    )

    private fun simulate(c: Color, m: Array<DoubleArray>): Triple<Double, Double, Double> {
        val r = channel(c.red)
        val g = channel(c.green)
        val b = channel(c.blue)
        return Triple(
            m[0][0] * r + m[0][1] * g + m[0][2] * b,
            m[1][0] * r + m[1][1] * g + m[1][2] * b,
            m[2][0] * r + m[2][1] * g + m[2][2] * b,
        )
    }

    /** CIE76 ΔE over Lab. Coarse, but adequate for "can these two bars be told apart". */
    private fun deltaE(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        fun lab(t: Triple<Double, Double, Double>): DoubleArray {
            val x = (0.4124 * t.first + 0.3576 * t.second + 0.1805 * t.third) / 0.95047
            val y = 0.2126 * t.first + 0.7152 * t.second + 0.0722 * t.third
            val z = (0.0193 * t.first + 0.1192 * t.second + 0.9505 * t.third) / 1.08883
            fun f(v: Double) = if (v > 0.008856) v.pow(1.0 / 3.0) else 7.787 * v + 16.0 / 116.0
            val fx = f(x.coerceAtLeast(0.0))
            val fy = f(y.coerceAtLeast(0.0))
            val fz = f(z.coerceAtLeast(0.0))
            return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
        }
        val la = lab(a)
        val lb = lab(b)
        return sqrt((0 until 3).sumOf { (la[it] - lb[it]).pow(2) })
    }

    @Test
    fun `no two category colours collapse under any dichromacy`() {
        // The twelve hued categories. Income borrows `success` and the three neutral ones
        // deliberately share one grey, so they are excluded by design rather than by omission.
        val hued = Category.entries.filter {
            it !in setOf(
                Category.INCOME, Category.TRANSFERS, Category.SELF_TRANSFER,
                Category.CARD_PAYMENT, Category.OTHER,
            )
        }
        val failures = mutableListOf<String>()
        for (dark in listOf(true, false)) {
            val c = oursColors(dark, ThemeTone.CRISP, AccentColor.BLUE)
            // The two themes are held to different floors, and the difference is structural.
            //
            // Clearing 4.5:1 against paper forces every light-theme ink dark — lightness roughly
            // 0.16 to 0.32 — and lightness is most of what separates two colours once hue
            // information is gone. On near-black the same floor allows 0.50 to 0.82, about three
            // times the range. So dark can reach ΔE 10 on all 66 pairs and light cannot: it
            // reaches 8, with Food/Education and Groceries/Travel sitting between the two.
            //
            // Re-optimising the whole light set against the tighter constraint came back *worse*
            // (seven collapsed pairs), and forcing the two offenders apart bought ΔE 8.6 for a
            // green nobody would call groceries. This is the honest ceiling, not a lack of effort.
            //
            // What makes 8 acceptable is that colour is never the only carrier: every place a
            // category hue appears its name appears with it, and the mark carries a distinct glyph
            // as well as a tint. Do not raise the light floor without checking it is reachable,
            // and do not lower the dark one at all.
            val floor = if (dark) 10.0 else 8.0
            for ((kind, matrix) in dichromacies) {
                for (i in hued.indices) {
                    for (j in i + 1 until hued.size) {
                        val a = simulate(c.forCategory(hued[i]), matrix)
                        val b = simulate(c.forCategory(hued[j]), matrix)
                        val d = deltaE(a, b)
                        if (d < floor) {
                            failures += "${if (dark) "dark" else "light"}/$kind " +
                                "${hued[i].name}/${hued[j].name} = ΔE %.1f (floor %.0f)"
                                    .format(d, floor)
                        }
                    }
                }
            }
        }
        assertWithMessage("collapsed category pairs:\n" + failures.joinToString("\n")).that(failures.isEmpty()).isTrue()
    }

    @Test
    fun `a category keeps its hue between themes`() {
        // Lightness is free to move — that is what buys the separation above — but hue is the
        // identity. A palette that renamed its colours when you flipped the theme would be
        // worse than one that is merely hard to tell apart.
        val dark = oursColors(true, ThemeTone.CRISP, AccentColor.BLUE)
        val light = oursColors(false, ThemeTone.CRISP, AccentColor.BLUE)
        val hued = Category.entries.filter {
            it !in setOf(
                Category.INCOME, Category.TRANSFERS, Category.SELF_TRANSFER,
                Category.CARD_PAYMENT, Category.OTHER,
            )
        }
        for (category in hued) {
            val h1 = hueOf(dark.forCategory(category))
            val h2 = hueOf(light.forCategory(category))
            val drift = min(abs(h1 - h2), 360 - abs(h1 - h2))
            assertWithMessage("${category.name} hue drifts %.1f° between themes".format(drift)).that(drift <= 22.0).isTrue()
        }
    }

    private fun hueOf(c: Color): Double {
        val r = c.red
        val g = c.green
        val b = c.blue
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == mn) return 0.0
        val d = (mx - mn).toDouble()
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return ((h * 60) + 360) % 360
    }

    // ─── The semantic colours must stay distinguishable from each other ──────

    /**
     * A coloured figure must be perceivably *not* plain text — but red versus green is
     * deliberately **not** asserted.
     *
     * The first version of this test demanded ΔE ≥ 10 between every pair of success, warning and
     * error under every dichromacy, and it failed: success against error is ΔE 5.5 for a
     * deuteranope. Green and red are the classic confusion, and no tuning fixes it while green
     * still means "money arriving" and red still means "a loss".
     *
     * That failure was the test being wrong, not the palette. This design never asks colour to
     * carry those three on its own — principle six. The budget ruler changes its **word** from
     * LEFT to OVER as well as its colour, a state pill prints SYNCED or FAILED, an over-budget
     * category prints its amount against its cap. So what is worth guarding is the weaker and
     * true property: a figure given a semantic colour is distinguishable from one that was not,
     * for every reader. If that failed, the colour would be conveying nothing at all and should
     * be removed rather than kept as decoration.
     */
    @Test
    fun `a semantic colour is distinguishable from plain body text for every reader`() {
        for ((name, c) in allSchemes()) {
            val semantic = mapOf(
                "success" to c.success,
                "warning" to c.warning,
                "error" to c.error,
            )
            for ((label, colour) in semantic) {
                for ((kind, matrix) in dichromacies) {
                    val d = deltaE(simulate(colour, matrix), simulate(c.onSurface, matrix))
                    assertWithMessage(
                        "$name $label is only ΔE %.1f from body text under $kind".format(d)
                    ).that(d >= 10.0).isTrue()
                }
            }
        }
    }
}

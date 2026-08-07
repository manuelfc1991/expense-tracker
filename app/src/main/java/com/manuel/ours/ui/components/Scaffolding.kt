package com.manuel.ours.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.WordmarkStyle

/**
 * The frame every screen is hung on, and the three states the app had no design for.
 *
 * Sixteen screens each hand-rolled their own header row — a wordmark, sometimes a back arrow
 * that was a bare 16dp glyph, sometimes a count, sometimes an action as a 9sp text label. That
 * is sixteen chances for them to disagree, and they did. One bar, one set of slots.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Touch targets
// ─────────────────────────────────────────────────────────────────────────────

/**
 * An icon button with a real target.
 *
 * **The glyph is not the target.** The build this replaces tapped bare glyphs directly — a 16dp
 * back arrow, a 13dp bill dismiss, a 15dp delete a thumb's width from Back, and a 9dp delete on
 * a rule chip, which was the smallest tap target in the app and sat on a destructive action.
 * Material's minimum is 48dp and none of those came close.
 *
 * [size] is the *target*, not the glyph; [glyph] is drawn centred inside it. Use
 * [Space.targetTight] only where two controls are unavoidably adjacent, and never anything
 * smaller — 24dp is the absolute floor WCAG 2.5.8 allows and nothing here needs to go there.
 */
@Composable
fun OursIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ours.onSurfaceVariant,
    size: Dp = Space.target,
    glyph: Dp = 20.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OursIconView(
            icon = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Ours.onSurfaceMuted,
            modifier = Modifier.size(glyph),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top app bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The one screen header.
 *
 * A 56dp bar: an optional back button, the title as a tracked-out wordmark, and whatever
 * trailing controls the screen needs. The title is a wordmark rather than a sentence because it
 * is a mark for the place you are in, not a heading to be read.
 */
@Composable
fun OursTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Back",
    titleColor: Color = Ours.onSurface,
    container: Color = Ours.surface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(container)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            OursIconButton(
                icon = OursIcon.Back,
                contentDescription = backDescription,
                onClick = onBack,
            )
        } else {
            // Keeps the title on the same x as a screen that does have a back button, so the
            // wordmark does not shift by 40dp as you navigate.
            Box(Modifier.width(Space.s2))
        }
        Text(
            text = title.uppercase(),
            style = WordmarkStyle,
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * The selection variant, for a list that is being acted on in bulk.
 *
 * The bar this replaces was four identical 9sp uppercase words — `All · Categorize · Delete ·
 * Done` — with **Delete third, between two harmless ones**, each of them a bare text label with
 * no target padding. What a bulk delete destroys can include hand-made entries that exist in no
 * backup, so three things changed: the actions became 48dp icon buttons, Delete moved to the far
 * end where a thumb reaching for "done" cannot land on it, and it takes the error colour. The
 * confirm dialog and the undo were already there and stay.
 */
@Composable
fun OursSelectionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Ours.secondaryContainer)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = Space.s2)
            // Announced as a unit, because the count changing is the thing a screen reader
            // user needs told and the individual buttons cannot say it.
            .semantics {
                contentDescription = "$selectedCount selected"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OursIconButton(
            icon = OursIcon.Dismiss,
            contentDescription = "Clear selection",
            onClick = onClear,
            tint = Ours.onSecondaryContainer,
        )
        Text(
            text = "$selectedCount SELECTED",
            style = WordmarkStyle,
            color = Ours.primary,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Offline
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What is stale, and what is not.
 *
 * The app had **no offline design at all** — sync state was a pill on Home and nothing else. For
 * a ledger that exists to keep two phones in step, "the other phone is behind" is not a corner
 * case; it is the normal condition of the second phone.
 *
 * The distinction this draws is the whole point. This phone's own bank messages are read locally
 * and are *certainly* current; what may be missing is anything the other person paid since the
 * last sync. So figures are never hidden, greyed or replaced with a spinner — they are correct,
 * just possibly incomplete, and the ribbon says exactly which part.
 *
 * A ribbon rather than a dialog: nothing here blocks anything.
 */
@Composable
fun StaleRibbon(
    label: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Ours.surfaceContainerHigh)
            .padding(start = Space.edge, end = Space.s2)
            .defaultMinSize(minHeight = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        OursIconView(
            OursIcon.NotSynced,
            contentDescription = null,
            tint = Ours.onSurfaceMuted,
            modifier = Modifier.size(14.dp),
        )
        MicroLabel(label, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            Text(
                "Retry",
                style = MaterialTheme.typography.labelMedium,
                color = Ours.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = Space.s3, vertical = Space.s2),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Notices
// ─────────────────────────────────────────────────────────────────────────────

/** What a notice is reporting, which is the only thing that varies about it. */
enum class NoticeTone { Warning, Error, Info, Success }

/**
 * One thing that needs the reader, with the way to deal with it.
 *
 * Three screens had grown their own version of this — a private `Notice` on Home, a
 * `NeedsYouRow` in Settings, a `ResultCard` in the parser tester — and they had drifted in
 * padding, corner and border alpha.
 *
 * **Amber means something is not running; red means a figure is wrong or lost.** Nothing about a
 * revoked permission makes the ledger wrong, so those are amber however alarming they sound.
 */
@Composable
fun Notice(
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScopeAlias.() -> Unit = {},
) {
    val hue = when (tone) {
        NoticeTone.Warning -> Ours.warning
        NoticeTone.Error -> Ours.error
        NoticeTone.Info -> Ours.primary
        NoticeTone.Success -> Ours.success
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(hue.copy(alpha = 0.10f))
            .border(1.dp, hue.copy(alpha = 0.38f), MaterialTheme.shapes.medium)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Ours.onSurface,
            )
        }
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceVariant,
            )
        }
        content()
        action?.invoke()
    }
}

/** Alias so [Notice]'s trailing lambda reads as a column without importing ColumnScope twice. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * A single-line notice for something that is merely switched off, with a chevron into the fix.
 *
 * Drawn **only when something is actually wrong**, and absent entirely when nothing is. A banner
 * that is always there stops being read, and every failure this app has actually had was a
 * permission that was off while everything else looked fine.
 */
@Composable
fun NeedsYouRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Needs you",
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Ours.warning.copy(alpha = 0.10f))
            .border(1.dp, Ours.warning.copy(alpha = 0.38f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = Space.target)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        MicroLabel(label, color = Ours.warning)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OursIconView(
            OursIcon.More,
            contentDescription = null,
            tint = Ours.warning,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A placeholder shaped like the thing it is standing in for.
 *
 * Not a centred spinner: a spinner does not say what it is waiting for, and when the real
 * content arrives at a different height the page jumps. A skeleton at the real row height does
 * neither.
 *
 * The shimmer is one shared [rememberInfiniteTransition] per skeleton block rather than one per
 * bar, because a screen of twelve independently animating placeholders is twelve animation
 * clocks driving recomposition.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
    shimmer: Float = 0f,
) {
    val base = Ours.surfaceContainerHigh
    val highlight = Ours.onSurface.copy(alpha = 0.06f)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    // The bright band sweeps across; the stops move rather than the box.
                    0f to base,
                    (shimmer - 0.2f).coerceIn(0f, 1f) to base,
                    shimmer.coerceIn(0f, 1f) to highlight,
                    (shimmer + 0.2f).coerceIn(0f, 1f) to base,
                    1f to base,
                )
            )
    )
}

/**
 * Drives one shimmer clock for a whole skeleton screen.
 *
 * Read it once at the top of the placeholder block and hand the value to each [Skeleton].
 */
@Composable
fun rememberShimmer(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-sweep",
    )
    return value
}

/**
 * The statement list, before it has loaded.
 *
 * The same geometry as [StatementEntry] — a 32dp mark, two lines of text, an amount on the
 * right — so nothing moves when the rows arrive.
 */
@Composable
fun StatementSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 5,
) {
    val shimmer = rememberShimmer()
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        // Deterministic widths rather than random: a placeholder that reshuffles on every
        // recomposition reads as content arriving and then changing its mind.
        val widths = listOf(0.46f, 0.62f, 0.38f, 0.54f, 0.44f)
        val captions = listOf(0.28f, 0.34f, 0.24f, 0.30f, 0.26f)
        repeat(rows) { index ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(
                    Modifier.size(32.dp),
                    height = 32.dp,
                    shape = RoundedCornerShape(10.dp),
                    shimmer = shimmer,
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    Skeleton(
                        Modifier.fillMaxWidth(widths[index % widths.size]),
                        height = 13.dp,
                        shimmer = shimmer,
                    )
                    Skeleton(
                        Modifier.fillMaxWidth(captions[index % captions.size]),
                        height = 11.dp,
                        shimmer = shimmer,
                    )
                }
                Skeleton(Modifier.width(56.dp), height = 13.dp, shimmer = shimmer)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Says what is missing, never "no data".
 *
 * "Nothing yet this month" is a fact about the month. "No data" is a fact about a database, and
 * the person reading it does not have one.
 *
 * [body] earns its place by saying why the emptiness is fine, and [action] appears **only** when
 * there is genuinely something to do — an empty Trash offers nothing, an empty search offers to
 * clear the filter.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    @DrawableRes icon: Int = OursIcon.Activity,
    iconTint: Color = Ours.onSurfaceMuted,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.s6, vertical = Space.s10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        OursIconView(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(32.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = Ours.onSurface,
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

/**
 * Summary, before the month has been aggregated.
 *
 * Shaped like figures rather than rows, because that is what this screen is: a headline, a
 * three-figure line, then ranked bars. Aggregating a month over several hundred transactions
 * takes long enough to be worth showing.
 */
@Composable
fun SummarySkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmer()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s5)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Skeleton(Modifier.fillMaxWidth(0.4f), height = 11.dp, shimmer = shimmer)
            Skeleton(Modifier.fillMaxWidth(0.7f), height = 36.dp, shimmer = shimmer)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            repeat(3) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Skeleton(Modifier.fillMaxWidth(0.7f), height = 11.dp, shimmer = shimmer)
                    Skeleton(Modifier.fillMaxWidth(0.9f), height = 15.dp, shimmer = shimmer)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            listOf(0.38f, 0.30f, 0.24f, 0.18f).forEach { width ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Skeleton(Modifier.fillMaxWidth(0.35f), height = 13.dp, shimmer = shimmer)
                    Skeleton(Modifier.fillMaxWidth(width), height = 6.dp,
                        shape = RoundedCornerShape(3.dp), shimmer = shimmer)
                }
            }
        }
    }
}

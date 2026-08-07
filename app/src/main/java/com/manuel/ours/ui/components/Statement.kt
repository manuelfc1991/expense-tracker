package com.manuel.ours.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manuel.ours.core.Money
import com.manuel.ours.core.OursZone
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.theme.AmountTextStyle
import com.manuel.ours.ui.theme.MicroLabelStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.PillTextStyle
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.colorForCategory
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The elements every screen is assembled from.
 *
 * These exist so the screens become arrangement rather than invention: a screen that
 * needs a measured quantity reaches for [Ruler], one that needs a transaction reaches
 * for [StatementEntry], and neither gets to invent its own spacing or type size. That
 * is what keeps three screens looking like one product.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Type primitives
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The 11sp uppercase caption. Always above its value, never beside it.
 *
 * Was 9sp — below Material's own `labelSmall` floor, on the most-used style in the app. The wide
 * tracking is what makes it read as a deliberate caption, not the smallness.
 *
 * One line with ellipsis, which costs about 25 characters at this size: a caption that overflows
 * loses its tail rather than wrapping, so the tail must never be where the meaning is.
 */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ours.onSurfaceMuted,
) {
    Text(
        text = text.uppercase(),
        style = MicroLabelStyle,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A caption stacked over its value.
 *
 * Label above rather than beside, so the eye lands on the number first and only reads
 * the caption if it needs to. Beside-the-value forces both to be read in sequence.
 */
@Composable
fun LabelOverValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ours.onSurface,
    valueStyle: TextStyle = ValueTextStyle,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1), horizontalAlignment = alignment) {
        MicroLabel(label)
        Text(value, style = valueStyle, color = valueColor, maxLines = 1)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ruler
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A measured quantity drawn as discrete ticks rather than a continuous bar.
 *
 * A solid fill says "roughly this much". Ticks say "this many out of that many", which
 * is what a budget actually is — and it borrows the vernacular of a printed scale,
 * which is the material the whole interface is made of.
 *
 * @param fraction 0f..1f of budget consumed; values above 1f render entirely as [over].
 */
@Composable
fun Ruler(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    over: Boolean = fraction > 1f,
) {
    val track = Ours.outlineVariant
    val fill = if (over) Ours.error else Ours.success
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pitch = 7.dp.toPx()
        val tickWidth = 1.5.dp.toPx()
        val count = (size.width / pitch).toInt().coerceAtLeast(1)
        // Over budget fills the whole scale in the warning colour — a ruler that ran
        // past its own end would just look broken.
        val filled = (count * fraction.coerceIn(0f, 1f)).roundToInt()
        for (i in 0 until count) {
            // Every tick full height and identical. An earlier pass made every fifth
            // taller, from a design note rather than the design — the mockup draws a
            // plain repeating gradient, and the varied heights read as a chart axis
            // with meaning in the tall marks that they do not carry.
            drawRect(
                color = if (i < filled) fill else track,
                topLeft = Offset(i * pitch, 0f),
                size = Size(tickWidth, size.height),
            )
        }
    }
}

/** A plain proportional meter, for the ranked category bars where ticks would be noise. */
@Composable
fun Meter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Ours.outlineVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Entry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The rounded-square category mark.
 *
 * A tint of the category's own colour with the icon a step lighter on top. On dark the
 * icon is lifted toward white because the base hue against its own 20% tint is legible
 * but flat; on paper the base hue is already the darker of the two and needs no help.
 */
@Composable
fun CategoryAvatar(
    category: Category,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    @DrawableRes overrideIcon: Int? = null,
) {
    val base = colorForCategory(category)
    val dark = Ours.isDark
    val tint = if (dark) lerp(base, Color.White, 0.20f) else base
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(base.copy(alpha = if (dark) 0.20f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        OursIconView(
            icon = overrideIcon ?: OursIcon.forCategory(category),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * The amount, in the shared right-hand column.
 *
 * [defaultMinSize] rather than a fixed width so a seven-figure amount can still grow,
 * while everything smaller pads out to the same edge. Tabular figures do the rest.
 */
@Composable
fun AmountColumn(
    paise: Long,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    signed: Boolean = false,
    color: Color = if (dim) Ours.onSurfaceVariant else Ours.onSurface,
) {
    // Paise, always — two places on every row, never "only when there are some".
    //
    // Dropping them read as wrong, because it was: a ₹450.75 payment printed as 450 and
    // the 75 paise were simply gone from the page. The reason they were dropped still
    // holds, though — a column where some rows carry paise and others don't has no
    // shared decimal point and stops lining up. Printing .00 on the round ones keeps
    // the point in the same place on every row, so the column survives and the figure
    // is the real one.
    val text = Money.bare(paise, withDecimals = true)
    Text(
        text = if (signed && paise > 0) "+$text" else text,
        style = AmountTextStyle.copy(
            fontWeight = if (dim) FontWeight.Medium else FontWeight.Bold,
        ),
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier.defaultMinSize(minWidth = 62.dp),
    )
}

/**
 * One printed line: mark, name over caption, amount flush right.
 *
 * The hairline underneath is the divider — a card per transaction would put a box
 * around every line of a statement, which is precisely what a statement does not do.
 */
@Composable
fun StatementEntry(
    title: String,
    caption: String,
    paise: Long,
    modifier: Modifier = Modifier,
    category: Category = Category.OTHER,
    captionColor: Color = Ours.onSurfaceMuted,
    amountDim: Boolean = false,
    divider: Boolean = true,
    @DrawableRes overrideIcon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                // A row is a touch target, so it gets the minimum whether or not its content
                // fills it. 8dp vertical over a 32dp mark comes to exactly 48.
                .defaultMinSize(minHeight = Space.target)
                .padding(vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            CategoryAvatar(category, overrideIcon = overrideIcon)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MicroLabel(caption, color = captionColor)
            }
            AmountColumn(paise, dim = amountDim)
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Ours.outlineVariant)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Controls
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A filter or category chip. The selected one is the only filled thing in view, which
 * is what makes "which is chosen" answerable without reading any of them.
 */
@Composable
fun OursChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    /**
     * How many rows this chip would show.
     *
     * Only meaningful for a filter, where it turns "which of these is worth tapping"
     * into something answerable by reading rather than by tapping each one.
     */
    count: Int? = null,
    /** Overrides the accent — the untagged chip is amber, matching its captions. */
    tint: Color? = null,
    /**
     * Colours the icon independently of the label.
     *
     * A category's mark carries its own hue everywhere else in the app — the avatar on a
     * row, the header on the Rules screen — and tinting it with the chip's text colour
     * instead would make sixteen filter chips identical but for the word.
     */
    iconTint: Color? = null,
) {
    val accent = tint ?: Ours.primaryFixed
    val bg = if (selected) accent else Color.Transparent
    val fg = when {
        selected -> Color.White
        tint != null -> tint
        else -> Ours.onSurfaceVariant
    }
    Row(
        modifier
            // The chip reads 32dp but is tapped over 48: the outer padding is part of the
            // clickable and outside the visible shape, so a row of chips has no dead gaps
            // between targets without the chips themselves growing.
            .clip(RoundedCornerShape(Space.s2))
            .clickable(onClick = onClick)
            .padding(vertical = Space.s2)
            .clip(RoundedCornerShape(Space.s2))
            .background(bg)
            .border(
                1.dp,
                when {
                    selected -> accent
                    tint != null -> tint.copy(alpha = 0.5f)
                    else -> Ours.outlineVariant
                },
                RoundedCornerShape(Space.s2),
            )
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = Space.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            OursIconView(
                icon,
                contentDescription = null,
                tint = if (selected) fg else iconTint ?: fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        if (count != null) {
            Text(
                count.toString(),
                style = MicroLabelStyle,
                color = if (selected) Color.White.copy(alpha = 0.7f) else Ours.onSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The one primary action on a screen. Title over caption, chevron trailing.
 *
 * There is exactly one of these per screen on purpose — a second accent-filled button
 * makes the first one stop meaning "do this".
 */
@Composable
fun PrimaryAction(
    title: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Ours.primaryFixed)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = Space.target)
            .padding(horizontal = Space.s4, vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
            )
            MicroLabel(caption, color = Color.White.copy(alpha = 0.72f))
        }
        OursIconView(
            OursIcon.More,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The secondary action. Outline only — it never competes with [PrimaryAction]. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** For the rare secondary action that is destructive. Accent by default. */
    tint: Color = Ours.primary,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .border(
                1.dp,
                if (enabled) Ours.outline else Ours.outlineVariant,
                RoundedCornerShape(percent = 50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            // Both this and [AccentButton] are exactly Space.target tall, so the pair cannot
            // disagree by a pixel — which is what the hand-tuned 13dp was compensating for.
            .height(Space.target),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else Ours.onSurfaceMuted,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

enum class PillTone { Ok, Warn, Neutral }

/** SYNCED · 3 WAITING · OFF. Outline pills, coloured only by what they report. */
@Composable
fun StatePill(
    text: String,
    tone: PillTone = PillTone.Neutral,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val fg = when (tone) {
        PillTone.Ok -> Ours.success
        PillTone.Warn -> Ours.warning
        PillTone.Neutral -> Ours.onSurfaceVariant
    }
    val edge = when (tone) {
        PillTone.Neutral -> Ours.outlineVariant
        else -> fg.copy(alpha = 0.42f)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .border(1.dp, edge, RoundedCornerShape(percent = 50))
            .defaultMinSize(minHeight = 24.dp)
            .padding(horizontal = 10.dp, vertical = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            OursIconView(icon, contentDescription = null, tint = fg, modifier = Modifier.size(10.dp))
        }
        Text(text.uppercase(), style = PillTextStyle, color = fg, maxLines = 1)
    }
}

/**
 * A section rule: caption left, figure right, hairline under both.
 *
 * This is the "TODAY ... ₹597" header — the running total belongs on the rule itself
 * rather than in a row of its own, exactly as a statement subtotals a page.
 */
@Composable
fun TapeHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color = Ours.onSurfaceVariant,
    /**
     * The hairline under the label, which is what makes this a section head.
     *
     * Dropped for a heading *inside* a section — a person's name over their accounts is
     * a sub-total, not a new section, and ruling it the same way would make one list of
     * accounts read as three unrelated ones.
     */
    rule: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = if (rule) 7.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MicroLabel(label)
            if (trailing != null) MicroLabel(trailing, color = trailingColor)
        }
        if (rule) Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
    }
}

/**
 * Empty says what is missing, never "no data".
 *
 * "Nothing yet this month" is a fact about the month. "No data" is a fact about the
 * database, and the person reading it does not have one.
 */
@Composable
fun QuietEmpty(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = OursIcon.Activity,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OursIconView(icon, contentDescription = null, tint = Ours.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ours.onSurfaceVariant)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transactions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A [Transaction] as a printed line.
 *
 * Home and Activity both use this rather than each formatting a row of their own —
 * the amount column only reads as a column if every screen agrees on where it is.
 */
@Composable
fun TransactionEntry(
    txn: Transaction,
    modifier: Modifier = Modifier,
    showOwner: Boolean = false,
    divider: Boolean = true,
    /**
     * Replaces the whole caption. Trash needs "Food · 4 Aug · 30 days left" where this
     * would print a clock time — the row is otherwise identical, and forking the element
     * to change one line of text is how a second design system starts.
     */
    captionOverride: String? = null,
    captionColorOverride: Color? = null,
    /** Dim the amount for a row that is in no total. */
    dimAmount: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    // An untagged row says so in the caption and dims its amount, because it is not in the
    // month's total yet. Showing it at full weight would imply it was counted.
    val untagged = txn.needsReview || txn.category == Category.OTHER

    // One remember for the whole caption, keyed on everything it reads.
    //
    // This is the hottest path in the app — it runs for every row of a list that holds several
    // hundred — and it was doing two avoidable things on every recomposition: building a
    // `DateTimeFormatter` from its pattern string (now a shared instance on `OursZone`), and
    // running `buildString` plus a `String.split` outside any remember, so scrolling reallocated
    // the caption of every visible row on every frame.
    val caption = remember(txn.occurredAt, txn.category, txn.needsReview, txn.ownerName, showOwner) {
        // The mark already states the category, so the caption spends its width on what the
        // mark cannot say.
        //
        // It is one line with ellipsis, and at 11sp there is room for about 25 characters —
        // so `Groceries · 11:20 am · Anu` (26) would have rendered as
        // `GROCERIES · 11:20 AM · A…`, clipping the one thing the owner suffix was added for.
        // Once a name has to fit, the category word goes. "Untagged" is the exception: a grey
        // dots mark cannot convey it, which is the whole reason that state has a word.
        //
        // Assembled as parts and joined, rather than appended with separators inline: the clock
        // is now optional, and hand-managed " · " left a dangling separator the moment any one
        // piece went away.
        buildList {
            when {
                untagged -> add("Untagged")
                !showOwner -> add(txn.category.label)
            }
            // No clock when the bank never gave one. Midnight is the parser's marker for a
            // date-only message, and printing it as "12:00 am" states a time nobody was told —
            // on this ledger a card bill dated 3 August read as though it were paid at midnight.
            if (!OursZone.isDateOnly(txn.occurredAt)) {
                add(OursZone.format(txn.occurredAt, OursZone.clock))
            }
            if (showOwner) add(txn.ownerName.substringBefore(' '))
        }.joinToString(" · ")
    }
    StatementEntry(
        title = txn.merchant,
        caption = captionOverride ?: caption,
        paise = txn.amountPaise,
        category = txn.category,
        captionColor = captionColorOverride
            ?: if (untagged) Ours.warning else Ours.onSurfaceMuted,
        amountDim = dimAmount || untagged,
        divider = divider,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * A full-width accent button.
 *
 * [PrimaryAction] is the version that explains itself with a caption and a chevron;
 * this is the one for a step you have already read the explanation for. Disabled state
 * drops to the hairline rather than a tinted accent, so "not yet" never looks like a
 * dimmer shade of "go".
 */
@Composable
fun AccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    /**
     * Fade the accent instead of dropping to the hairline when disabled.
     *
     * The hairline treatment is right for a button standing alone: "not yet" should not
     * look like a dimmer shade of "go". Beside a [GhostButton] it is wrong — the two
     * become the same grey outline-ish block and the pair stops reading as
     * secondary-then-primary. A faded accent keeps the hierarchy visible while still
     * being obviously inactive.
     */
    dimWhenDisabled: Boolean = false,
) {
    val fg = when {
        enabled -> Ours.onPrimaryFixed
        dimWhenDisabled -> Ours.onPrimaryFixed.copy(alpha = 0.6f)
        else -> Ours.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(
                when {
                    enabled -> Ours.primaryFixed
                    // A flat tint of the content colour, so "not yet" cannot be mistaken for a
                    // dimmer shade of "go".
                    dimWhenDisabled -> Ours.primaryFixed.copy(alpha = 0.45f)
                    else -> Ours.onSurface.copy(alpha = 0.12f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .height(Space.target),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                OursIconView(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

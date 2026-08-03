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
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.theme.AmountTextStyle
import com.manuel.ours.ui.theme.MicroLabelStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.PillTextStyle
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.colorForCategory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

/** The 9sp uppercase caption. Always above its value, never beside it. */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ours.textLabel,
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
    valueColor: Color = Ours.text,
    valueStyle: TextStyle = ValueTextStyle,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = alignment) {
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
 * which is the material the whole interface is made of. Every fifth tick runs full
 * height so the eye can count without a legend.
 *
 * @param fraction 0f..1f of budget consumed; values above 1f render entirely as [over].
 */
@Composable
fun Ruler(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    over: Boolean = fraction > 1f,
) {
    val track = Ours.hairline
    val fill = if (over) Ours.negative else Ours.positive
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pitch = 7.dp.toPx()
        val tickWidth = 1.5.dp.toPx()
        val count = (size.width / pitch).toInt().coerceAtLeast(1)
        // Over budget fills the whole scale in the warning colour — a ruler that ran
        // past its own end would just look broken.
        val filled = (count * fraction.coerceIn(0f, 1f)).roundToInt()
        for (i in 0 until count) {
            val tall = i % 5 == 0
            val h = if (tall) size.height else size.height * 0.55f
            drawRect(
                color = if (i < filled) fill else track,
                topLeft = Offset(i * pitch, size.height - h),
                size = Size(tickWidth, h),
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
            .background(Ours.hairline)
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
    size: Dp = 28.dp,
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
        BiIconView(
            icon = overrideIcon ?: BiIcon.forCategory(category),
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
    color: Color = if (dim) Ours.textSecondary else Ours.text,
) {
    // Whole rupees, always. A column where some rows carry paise and others don't has
    // no shared decimal point, so the digits stop lining up and the column stops being
    // one — which was the entire reason for putting the amounts here. The exact figure,
    // paise included, is on the entry's own screen, where you go to reconcile.
    val text = Money.bare(paise - paise % 100)
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
    captionColor: Color = Ours.textLabel,
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
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CategoryAvatar(category, overrideIcon = overrideIcon)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.text,
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
                    .background(Ours.hairline)
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
) {
    val bg = if (selected) Ours.accent else Color.Transparent
    val fg = if (selected) Color.White else Ours.textSecondary
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (selected) Ours.accent else Ours.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            BiIconView(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
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
            .clip(RoundedCornerShape(12.dp))
            .background(Ours.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
            )
            MicroLabel(caption, color = Color.White.copy(alpha = 0.72f))
        }
        BiIconView(
            BiIcon.NextMonth,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** The secondary action. Outline only — it never competes with [PrimaryAction]. */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, Ours.hairline, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = Ours.textSecondary,
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
        PillTone.Ok -> Ours.positive
        PillTone.Warn -> Ours.warning
        PillTone.Neutral -> Ours.textSecondary
    }
    val edge = when (tone) {
        PillTone.Neutral -> Ours.hairline
        else -> fg.copy(alpha = 0.42f)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, edge, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            BiIconView(icon, contentDescription = null, tint = fg, modifier = Modifier.size(10.dp))
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
    trailingColor: Color = Ours.textSecondary,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MicroLabel(label)
            if (trailing != null) MicroLabel(trailing, color = trailingColor)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
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
    @DrawableRes icon: Int = BiIcon.Activity,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BiIconView(icon, contentDescription = null, tint = Ours.textSecondary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ours.textSecondary)
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
    onClick: (() -> Unit)? = null,
) {
    val time = remember(txn.occurredAt) {
        Instant.ofEpochMilli(txn.occurredAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }
    // An untagged row says so in the caption and dims its amount, because it is not
    // in the month's total yet. Showing it at full weight would imply it was counted.
    val untagged = txn.needsReview || txn.category == Category.OTHER
    val caption = buildString {
        append(if (untagged) "Untagged" else txn.category.label)
        append(" · ")
        append(time)
        if (showOwner) {
            append(" · ")
            append(txn.ownerName.split(" ").firstOrNull().orEmpty())
        }
    }
    StatementEntry(
        title = txn.merchant,
        caption = caption,
        paise = txn.amountPaise,
        category = txn.category,
        captionColor = if (untagged) Ours.warning else Ours.textLabel,
        amountDim = untagged,
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
) {
    val fg = if (enabled) Color.White else Ours.textLabel
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) Ours.accent else Ours.hairline)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                BiIconView(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

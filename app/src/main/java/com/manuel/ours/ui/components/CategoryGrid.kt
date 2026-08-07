package com.manuel.ours.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.manuel.ours.ui.theme.Space
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manuel.ours.domain.model.Category
import com.manuel.ours.ui.theme.MicroLabelStyle
import com.manuel.ours.ui.theme.Ours

/**
 * One cell that is not a [Category] — the Untagged filter, and nothing else so far.
 *
 * It exists because the grid has to be able to draw the whole set of things you can
 * filter by, and untagged is one of them without being an enum value.
 */
data class GridExtra(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val count: Int? = null,
    val tint: Color? = null,
    @DrawableRes val icon: Int? = null,
)

/**
 * Every category at once, in a grid of equal cells.
 *
 * Three things this is not, each of which was tried first and is worse.
 *
 * It is not a **horizontal strip**: that showed five of sixteen, and Android draws no
 * scrollbar, so the rest were invisible rather than merely off-screen. A ledger fills
 * up with Other when nobody knows the other options exist.
 *
 * It is not a **wrapped flow of chips**: variable widths give a ragged right edge and a
 * different row length every time the list changes, so the eye has to re-scan for a
 * category it has already used a hundred times. A fixed grid puts Rent in the same
 * place every time, which is what makes it findable without reading.
 *
 * It is not a **lazy grid**: these screens are already inside a scrolling column, and
 * nesting a lazy grid in one is both a crash and a lie — sixteen items never needed
 * recycling.
 *
 * Pass [counts] when the grid is being used to *filter* rather than to assign. Each cell
 * then carries how many rows it would show and fades to almost nothing at zero, so the
 * question "did anything land under Health this month" is answered by looking rather
 * than by tapping and finding an empty screen.
 */
@Composable
fun CategoryGrid(
    selected: Category?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Category> = Category.EVERY,
    columns: Int = 3,
    counts: Map<Category, Int>? = null,
    extras: List<GridExtra> = emptyList(),
) {
    val cells = options.map { option ->
        val count = counts?.get(option) ?: 0
        Cell(
            label = option.label,
            selected = option == selected,
            onClick = { onSelect(option) },
            count = counts?.let { count },
            dim = counts != null && count == 0,
            icon = OursIcon.forCategory(option),
            // The category's own hue, the same one the avatar on a row and the header on
            // the Rules screen use. Sixteen words in a grid are told apart by reading
            // them; sixteen coloured marks are told apart by looking.
            iconTint = Ours.forCategory(option),
        )
    } + extras.map { extra ->
        Cell(
            label = extra.label,
            selected = extra.selected,
            onClick = extra.onClick,
            count = extra.count,
            dim = extra.count == 0,
            tint = extra.tint,
            icon = extra.icon,
            iconTint = extra.tint,
        )
    }

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell -> CategoryCell(cell, Modifier.weight(1f)) }
                // Keeps the last row's cells the same width as every other row's,
                // rather than letting one or two orphans stretch across the screen.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class Cell(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val count: Int? = null,
    val dim: Boolean = false,
    val tint: Color? = null,
    @DrawableRes val icon: Int? = null,
    val iconTint: Color? = null,
)

@Composable
private fun CategoryCell(cell: Cell, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(9.dp)
    val accent = cell.tint ?: Ours.primaryFixed
    Row(
        modifier
            // Faded rather than removed: an empty category is still an answer, and a
            // grid whose contents move around between visits stops being findable
            // without reading, which is the whole point of a fixed grid.
            .alpha(if (cell.dim && !cell.selected) 0.32f else 1f)
            .clip(shape)
            .then(
                if (cell.selected) Modifier.background(accent)
                else Modifier.border(1.dp, cell.tint?.copy(alpha = 0.5f) ?: Ours.outlineVariant, shape)
            )
            .clickable(onClick = cell.onClick)
            // heightIn, not more padding: the cells sit in a fixed grid and growing the
            // padding would grow the grid. 9dp of padding around a 12sp label left them
            // about 34dp tall — under the app's own `Space.targetTight` floor, on the
            // picker used by four screens.
            .heightIn(min = Space.targetTight)
            .padding(vertical = 9.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mark, in the category's colour.
        //
        // This grid was drawn as centred words and nothing else, on the argument that
        // sixteen small glyphs are texture rather than information. That holds only if
        // the glyphs are all one colour — which they are not here. Each category owns a
        // hue, already used by the avatar on every row and by the Rules screen, and
        // carrying it into the picker is what lets you find Rent without reading four
        // words on the way to it.
        if (cell.icon != null) {
            OursIconView(
                icon = cell.icon,
                contentDescription = null,
                tint = when {
                    cell.selected -> Color.White
                    else -> cell.iconTint ?: Ours.onSurfaceVariant
                },
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = cell.label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                cell.selected -> Color.White
                cell.tint != null -> cell.tint
                else -> Ours.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (cell.count != null) {
            Text(
                text = cell.count.toString(),
                style = MicroLabelStyle,
                color = if (cell.selected) Color.White.copy(alpha = 0.7f) else Ours.onSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

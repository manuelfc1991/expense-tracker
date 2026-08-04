package com.manuel.ours.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manuel.ours.domain.model.Category
import com.manuel.ours.ui.theme.Ours

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
 */
@Composable
fun CategoryGrid(
    selected: Category?,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Category> = Category.PICKABLE,
    columns: Int = 3,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    CategoryCell(
                        category = option,
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the last row's cells the same width as every other row's,
                // rather than letting one or two orphans stretch across the screen.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryCell(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(Ours.accent)
                else Modifier.border(1.dp, Ours.hairline, shape)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp),
        // No icon. Sixteen glyphs at 13dp across three columns is texture rather than
        // information, and it steals the width the longest labels need — the design
        // draws these as centred words and nothing else.
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else Ours.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

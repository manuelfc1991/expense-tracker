package com.manuel.ours.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryFilter
import com.manuel.ours.ui.theme.Ours

/**
 * The whole filter, when the chip row is not enough.
 *
 * The row on the Activity screen only offers what the month actually contains, which is
 * right nearly always and wrong in one case: wanting to know whether *anything* landed
 * under Health, or hunting a row you know you filed wrongly. A category with no rows has
 * no chip, so without this there would be no way to ask.
 *
 * Every count is shown, including the zeroes, because the count **is** the answer to that
 * question — dimming an empty category answers it without a tap, and hiding it would send
 * you looking somewhere else for something that was never there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilterSheet(
    current: CategoryFilter,
    counts: Map<Category, Int>,
    untaggedCount: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
    onApply: (CategoryFilter) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pending by remember { mutableStateOf(current) }

    val shown = when (val p = pending) {
        CategoryFilter.All -> totalCount
        CategoryFilter.Untagged -> untaggedCount
        is CategoryFilter.Of -> counts[p.category] ?: 0
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicroLabel("Filter by category")

            CategoryGrid(
                selected = (pending as? CategoryFilter.Of)?.category,
                onSelect = { category ->
                    // Tapping the chosen one again clears it, which is how the chips
                    // behave; a sheet that could only ever narrow would be a trap.
                    pending = if (pending == CategoryFilter.Of(category)) {
                        CategoryFilter.All
                    } else {
                        CategoryFilter.Of(category)
                    }
                },
                // Income belongs here even though it is never something you *assign* —
                // it is very much something you filter for.
                options = Category.PICKABLE + Category.INCOME,
                counts = counts,
                extras = listOf(
                    GridExtra(
                        label = "Untagged",
                        selected = pending == CategoryFilter.Untagged,
                        count = untaggedCount,
                        tint = Ours.warning,
                        onClick = {
                            pending = if (pending == CategoryFilter.Untagged) {
                                CategoryFilter.All
                            } else {
                                CategoryFilter.Untagged
                            }
                        },
                    )
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.weight(1f)) {
                    GhostButton(
                        label = "Clear",
                        onClick = {
                            onApply(CategoryFilter.All)
                            onDismiss()
                        },
                    )
                }
                Box(Modifier.weight(1f)) {
                    // "Show 3", not "Apply" — the button says what is on the other side
                    // of it, so a filter that would empty the screen reads "Show 0" and
                    // you do not have to tap it to find that out.
                    AccentButton(
                        label = "Show $shown",
                        onClick = {
                            onApply(pending)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

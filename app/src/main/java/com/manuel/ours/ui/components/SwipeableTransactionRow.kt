package com.manuel.ours.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.theme.Ours

/**
 * Swipe left to delete, swipe right to categorise.
 *
 * The directions are not arbitrary. Delete is the destructive one, so it sits on the
 * left where a right-handed thumb has to travel further, and it still requires a full
 * swipe rather than a flick. Categorising — the thing you do dozens of times — is the
 * easy direction.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableTransactionRow(
    txn: Transaction,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCategorize: () -> Unit,
    modifier: Modifier = Modifier,
    showOwner: Boolean = true,
    divider: Boolean = true,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // While a selection is open the row stops swiping.
    //
    // Swipe acts on one row and selection acts on many, so leaving both live means a
    // gesture that deletes the row you touched rather than the eight you had picked —
    // and the two intents are indistinguishable at the moment the finger moves.
    if (selectionMode) {
        SelectableRow(
            txn = txn,
            showOwner = showOwner,
            divider = divider,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
        )
        return
    }

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                // Categorising opens a sheet, so the row must snap back rather than
                // fly off screen — it is not going anywhere.
                SwipeToDismissBoxValue.StartToEnd -> { onCategorize(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        // A generous threshold: these rows are scrolled past far more often than they
        // are acted on, and an accidental delete costs more than a missed swipe.
        positionalThreshold = { distance -> distance * 0.55f },
    )

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val isDelete = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val colour = if (isDelete) Ours.negative else Ours.accent

            Box(
                Modifier
                    .fillMaxSize()
                    .background(colour.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BiIconView(
                        icon = if (isDelete) BiIcon.Delete else BiIcon.Categorise,
                        contentDescription = null,
                        tint = colour,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isDelete) "Delete" else "Categorise",
                        style = MaterialTheme.typography.labelLarge,
                        color = colour,
                    )
                }
            }
        },
    ) {
        // The row keeps its own opaque ground so the swipe colour behind it never
        // shows through the gaps between the printed lines.
        Box(
            Modifier
                .background(Ours.ink)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            TransactionEntry(
                txn = txn,
                showOwner = showOwner,
                divider = divider,
                modifier = Modifier.padding(horizontal = 15.dp),
            )
        }
    }
}

/**
 * The same printed line, with a selection state.
 *
 * Selection is shown by tinting the row and marking the left edge rather than by adding
 * a checkbox column. A checkbox would push every merchant, time and amount sideways the
 * instant selection began, so the list you were reading would rearrange itself under
 * your thumb at exactly the moment you were aiming at something.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableRow(
    txn: Transaction,
    showOwner: Boolean,
    divider: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = Ours.accent
    Box(
        modifier
            .background(if (selected) accent.copy(alpha = 0.14f) else Ours.ink)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (selected) {
            // matchParentSize, then paint only the left strip. `fillMaxHeight` on a
            // 3dp-wide child collapses to nothing here: the parent Box wraps its
            // content, so the incoming height constraint is unbounded and there is no
            // height to fill.
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(
                        color = accent,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
            )
        }
        TransactionEntry(
            txn = txn,
            showOwner = showOwner,
            divider = divider,
            modifier = Modifier.padding(horizontal = 15.dp),
        )
    }
}

/** Full category list, for when the offered guesses were wrong. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    onDismiss: () -> Unit,
    onPick: (Category) -> Unit,
    title: String = "Choose a category",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicroLabel(title)
            // The same grid as the add sheet, the detail screen and the filter. This was
            // a wrapped flow of full-label chips with icons — a fourth way of drawing the
            // one list, and the only one where Rent was somewhere different every time
            // the set changed.
            CategoryGrid(selected = null, onSelect = onPick)
        }
    }
}

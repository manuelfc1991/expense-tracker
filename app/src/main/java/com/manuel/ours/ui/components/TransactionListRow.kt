package com.manuel.ours.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
 * One printed line in the list.
 *
 * **No swipe.** This used to be a SwipeToDismissBox — left to delete, right to
 * categorise — and it had to go: the gesture that dismisses a row and the gesture that
 * scrolls a list start out identical, and a list exists to be scrolled. A thumb travelling
 * up the screen at a slight angle was deleting entries and opening category sheets by
 * accident, which is not a threshold that can be tuned away. It was already at 55% of the
 * row width.
 *
 * Nothing is lost. Deleting is on the detail screen and in the selection toolbar; a
 * category can be set from the detail screen, from Sort, from the notification and from
 * the capture prompt. Every one of those needs a deliberate tap, which is the correct
 * price for changing a row you did not open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionListRow(
    txn: Transaction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showOwner: Boolean = true,
    divider: Boolean = true,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    captionOverride: String? = null,
    captionColorOverride: androidx.compose.ui.graphics.Color? = null,
    dimAmount: Boolean = false,
) {
    val accent = Ours.primaryFixed
    Box(
        modifier
            .background(if (selected) accent.copy(alpha = 0.14f) else Ours.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (selected) {
            // matchParentSize, then paint only the left strip. `fillMaxHeight` on a
            // 3dp-wide child collapses to nothing here: the parent Box wraps its
            // content, so the incoming height constraint is unbounded and there is no
            // height to fill.
            //
            // Selection is shown by tinting the row and marking its left edge rather
            // than by adding a checkbox column. A checkbox would push every merchant,
            // time and amount sideways the instant selection began, so the list you were
            // reading would rearrange itself under your thumb at exactly the moment you
            // were aiming at something.
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
            captionOverride = captionOverride,
            captionColorOverride = captionColorOverride,
            dimAmount = dimAmount,
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
        containerColor = Ours.surface,
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

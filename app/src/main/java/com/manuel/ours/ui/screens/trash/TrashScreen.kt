package com.manuel.ours.ui.screens.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.OursZone
import com.manuel.ours.domain.Trash
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursSelectionBar
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionListRow
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


/**
 * What has been deleted, and how long it can still be brought back.
 *
 * Built from `TransactionListRow` rather than from anything new, so a row here is the
 * same printed statement line as a row in Activity — one line, amount flush right in the
 * shared column, hairline underneath. Everything Trash has to add rides in the caption.
 *
 * ## The window is over `deletedAt`, not over the tombstone table
 *
 * The first real household had 476 transactions and 446 of them were already soft-deleted
 * — dedupe repairs and bulk tidy-ups, almost none of it a person throwing something away.
 * Listing `deleted = 1` would have opened this screen on 446 rows nobody deleted and
 * buried the one they came for. `deletedAt` is null on every one of those, so it starts
 * empty and fills only with real deletions.
 *
 * ## Selection rather than a button per row
 *
 * An earlier version put "Put back" on every row, which gave a screen of five entries
 * five competing accent actions. Selection is the pattern Activity already uses for bulk
 * delete; this is the same gesture pointed the other way, so one entry and twelve are the
 * same work and the screen keeps one call to action.
 */
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onTransactionClick: (String) -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val restored by viewModel.restored.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // Back closes the selection before it closes the screen — the same guard Activity
    // uses, because leaving with a selection open is how an action gets aimed at rows
    // the reader has forgotten about.
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    LaunchedEffect(restored) {
        val message = restored ?: return@LaunchedEffect
        // No Undo offered. Putting something back *is* the undo, and offering to undo an
        // undo is a loop — the entry is one tap from the bin again if it was a slip.
        snackbarHost.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.clearRestored()
    }

    Scaffold(
        containerColor = Ours.surface,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                if (state.selectionMode) {
                    OursSelectionBar(
                        selectedCount = state.selected.size,
                        onClear = { viewModel.clearSelection() },
                    ) {
                        // A real button, not a 12sp label. Putting something back is the only
                        // action on this screen and the reason anyone opens it.
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.restoreSelected() },
                        ) {
                            Text(
                                "Put back",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ours.primary,
                            )
                        }
                    }
                } else {
                    OursTopBar(title = "Trash", onBack = onBack) {
                        if (state.items.isNotEmpty()) MicroLabel("${state.items.size}")
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
                    TapeHeader(
                        "DELETED",
                        trailing = if (state.items.isEmpty()) null else "${state.items.size}",
                    )
                }
            }

            if (state.items.isEmpty()) {
                item { EmptyBin() }
            }

            items(state.items, key = { it.id }) { txn ->
                TransactionListRow(
                    txn = txn,
                    showOwner = false,
                    divider = txn.id != state.items.last().id,
                    selected = txn.id in state.selected,
                    captionOverride = caption(txn, viewModel.now),
                    captionColorOverride = urgency(txn, viewModel.now),
                    dimAmount = true,
                    // In selection mode a tap picks rather than opens: opening a detail
                    // screen mid-selection loses the set you had built up.
                    onClick = {
                        if (state.selectionMode) viewModel.toggle(txn.id)
                        else onTransactionClick(txn.id)
                    },
                    onLongClick = { viewModel.toggle(txn.id) },
                )
            }

            if (state.items.isNotEmpty()) {
                item {
                    Text(
                        text = "Tap to open an entry, hold to choose several. Entries leave " +
                            "this list ${Trash.WINDOW_DAYS} days after they were deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.onSurfaceMuted,
                        modifier = Modifier.padding(horizontal = Space.edge, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * The state this household will see nearly every time it opens the screen.
 *
 * Two lines rather than one: what is here, then what the room is for. An empty screen
 * that only reports its own emptiness reads as a broken feature, and this is the one
 * chance to teach the thirty days before anybody needs them.
 */
@Composable
private fun EmptyBin() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The wastebasket, the same glyph the delete button on an entry uses — this is
        // where that button sends things, and drawing the two differently would hide the
        // connection. It was OursIcon.Activity, which is the receipt used for the ledger
        // itself: the one icon in the set that means the opposite of an empty bin.
        OursIconView(
            OursIcon.Delete,
            contentDescription = null,
            tint = Ours.onSurfaceMuted,
            modifier = Modifier.size(30.dp),
        )
        Text(
            "Nothing deleted in the last ${Trash.WINDOW_DAYS} days.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ours.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "Anything you delete waits here for ${Trash.WINDOW_DAYS} days, so a mistake " +
                "stays a mistake and does not become a loss.",
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "Food · 4 Aug · 30 days left".
 *
 * The day rather than the clock time the row would otherwise print: "7:25 pm" for
 * something deleted last week answers a question nobody asked, and the date is what
 * places it.
 */
private fun caption(txn: Transaction, now: Long): String {
    val day = OursZone.format(txn.occurredAt, OursZone.day)
    val left = txn.deletedAt?.let { Trash.expiryLabel(it, now) }
    return listOfNotNull(txn.category.label, day, left).joinToString(" · ")
}

/**
 * Amber under a fortnight, red under three days — and the caption says the number either
 * way. Colour is never the only carrier here, for the same reason it is never the only
 * carrier on a category bar.
 */
@Composable
private fun urgency(txn: Transaction, now: Long): Color {
    val days = txn.deletedAt?.let { Trash.daysLeft(it, now) } ?: return Ours.onSurfaceMuted
    return when {
        days <= 3 -> Ours.error
        days <= 14 -> Ours.warning
        else -> Ours.onSurfaceMuted
    }
}

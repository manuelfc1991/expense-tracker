package com.manuel.ours.ui.screens.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryFilter
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.StatementSkeleton
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursSelectionBar
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.CategoryFilterSheet
import com.manuel.ours.ui.components.CategoryPickerSheet
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.domain.Trash
import com.manuel.ours.ui.components.TransactionListRow
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space


/**
 * The full statement.
 *
 * Home shows today; this is every line, still printed the same way. The date rules
 * stick to the top while you scroll, because when every row is structurally alike the
 * date is the only thing telling you where you are.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun TransactionsScreen(
    onTransactionClick: (String) -> Unit,
    onSort: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bulkCategorize by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Back should close the selection, not the screen. Leaving a selection open while
    // navigating away is how a bulk delete gets aimed at rows the reader forgot about.
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    val snackbarHost = remember { SnackbarHostState() }

    // One snackbar for the batch, not one per row. Ten stacked "Transaction deleted"
    // toasts would bury the Undo that matters under nine that no longer do.
    LaunchedEffect(state.lastBulkDeleted) {
        val ids = state.lastBulkDeleted
        if (ids.isEmpty()) return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = "${ids.size} transactions deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoBulkDelete()
        else viewModel.clearBulkUndo()
    }

    // A member's delete is a request, and the row stays put until the owner agrees. Say
    // so: an unchanged list after pressing delete otherwise reads as a broken button,
    // and the person tries again rather than waiting for an answer that is on its way.
    val requested by viewModel.deleteRequestNotice.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        if (requested <= 0) return@LaunchedEffect
        snackbarHost.showSnackbar(
            message = if (requested == 1) {
                "Asked the household owner to remove it"
            } else {
                "Asked the household owner to remove $requested transactions"
            },
            duration = SnackbarDuration.Short,
        )
        viewModel.clearDeleteRequestNotice()
    }

    // Asked before the batch goes, not just offered back afterwards. Undo covers a delete
    // you noticed; this covers the one you did not — Delete sits between Categorize and
    // Done in a row of identical labels, and the snackbar is gone in four seconds. What
    // the owner loses that way is unrecoverable: manual entries and hand-made category
    // corrections live in this database and nowhere else.
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    if (confirmingDelete && state.selectionMode) {
        val n = state.selected.size
        val rows = if (n == 1) "1 transaction" else "$n transactions"
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = Ours.surfaceContainer,
            title = {
                Text(
                    if (isOwner) "Delete $rows?" else "Ask to remove $rows?",
                    color = Ours.onSurface,
                )
            },
            text = {
                Text(
                    if (isOwner) {
                        // "gone for good" stopped being true when Trash arrived. Undo is
                        // still the quick way back; the thirty days are the safety net
                        // under it, and saying so is what keeps the sentence credible.
                        "They go from both phones. Undo is offered for a few seconds " +
                            "afterwards, and they wait in Trash for " +
                            "${Trash.WINDOW_DAYS} days after that."
                    } else {
                        "The household owner has to agree to each one, and they still " +
                            "count until they do."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.deleteSelectedWithUndo()
                    },
                ) { Text(if (isOwner) "Delete" else "Ask", color = Ours.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel", color = Ours.onSurfaceVariant)
                }
            },
        )
    }

    // The Scaffold stays for the snackbar host, but its padding is deliberately not
    // applied: `Navigation.kt` already gives the NavHost this Scaffold's outer
    // innerPadding, so consuming it again inset the screen twice and cost ~73dp — the
    // list stopped short of the tab bar with a dead band under the last row.
    Scaffold(
        modifier = modifier,
        containerColor = Ours.surface,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { _ ->
        Column(Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                // The bar this replaces was four identical 9sp uppercase words —
                // All · Categorize · Delete · Done — with **Delete third, between two harmless
                // ones**, each a bare text label with no target padding. A bulk delete can take
                // hand-made entries that exist in no backup, so three things changed: 48dp icon
                // buttons, Delete moved to the far end where a thumb reaching for "done" cannot
                // land on it, and it takes the error colour. The confirm and the undo stay.
                OursSelectionBar(
                    selectedCount = state.selected.size,
                    onClear = { viewModel.clearSelection() },
                ) {
                    OursIconButton(
                        icon = OursIcon.Check,
                        contentDescription = "Select all shown",
                        onClick = { viewModel.selectAllVisible() },
                        tint = Ours.onSecondaryContainer,
                    )
                    OursIconButton(
                        icon = OursIcon.Categorise,
                        contentDescription = "Set a category for the selected entries",
                        onClick = { bulkCategorize = true },
                        tint = Ours.onSecondaryContainer,
                    )
                    OursIconButton(
                        icon = OursIcon.Delete,
                        contentDescription = "Delete the selected entries",
                        onClick = { confirmingDelete = true },
                        tint = Ours.error,
                    )
                }
            } else {
                OursTopBar(title = "Activity") {
                    // "3 of 19" while filtered. A filtered screen that still claims the month's
                    // full count is describing a list nobody is looking at.
                    MicroLabel(
                        if (state.filtering) {
                            "${state.shownCount} of ${state.baseCount}"
                        } else {
                            "${state.shownCount} ${if (state.shownCount == 1) "entry" else "entries"}"
                        }
                    )
                    // The only permanent way into Sort. Home shows a "Sort N expenses" card, but
                    // only while something is untagged — so the moment the last one was
                    // categorised the screen became unreachable from anywhere in the app.
                    OursIconButton(
                        icon = OursIcon.Categorise,
                        contentDescription = "Sort untagged expenses",
                        onClick = onSort,
                        tint = Ours.primary,
                    )
                }
            }

            SearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = Space.edge, vertical = 8.dp),
            )

            if (state.hasPartner) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OursChip(
                        label = "Everyone",
                        selected = state.memberFilter == MemberFilter.Everyone,
                        onClick = { viewModel.setMemberFilter(MemberFilter.Everyone) },
                    )
                    state.people.forEach { person ->
                        OursChip(
                            label = person.chipLabel,
                            selected =
                                (state.memberFilter as? MemberFilter.Person)?.uid == person.uid,
                            onClick = {
                                viewModel.setMemberFilter(MemberFilter.Person(person.uid))
                            },
                        )
                    }
                }
            }

            // Chips while unfiltered; one line once something is chosen.
            //
            // The row was every category the app knows, in enum order, scrolling
            // horizontally — eighteen chips of which three and a half fit, and eleven of
            // which could only ever return an empty screen. It now lists what is actually
            // in front of you, biggest first, and wraps rather than scrolling, because
            // Android draws no scrollbar and off-screen chips are not merely out of
            // reach: there is nothing to say they exist.
            if (state.filtering) {
                ActiveFilterLine(
                    label = state.categoryFilter.label(),
                    detail = buildString {
                        append(state.shownCount)
                        append(if (state.shownCount == 1) " entry · " else " entries · ")
                        append(Money.exact(state.filteredTotalPaise))
                    },
                    tint = if (state.categoryFilter == CategoryFilter.Untagged) {
                        Ours.warning
                    } else {
                        Ours.primary
                    },
                    onOpen = { showFilterSheet = true },
                    onClear = { viewModel.setCategoryFilter(CategoryFilter.All) },
                    modifier = Modifier.padding(horizontal = Space.edge, vertical = 6.dp),
                )
            } else {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OursChip(
                        label = "All",
                        selected = true,
                        count = state.baseCount,
                        onClick = {},
                    )
                    state.chips.forEach { (filter, count) ->
                        val category = (filter as? CategoryFilter.Of)?.category
                        OursChip(
                            label = filter.label(),
                            selected = false,
                            count = count,
                            icon = OursIcon.forCategory(category ?: Category.OTHER),
                            iconTint = if (filter == CategoryFilter.Untagged) Ours.warning
                            else category?.let { Ours.forCategory(it) },
                            tint = if (filter == CategoryFilter.Untagged) Ours.warning else null,
                            onClick = { viewModel.setCategoryFilter(filter) },
                        )
                    }
                    OursChip(
                        label = "More ›",
                        selected = false,
                        onClick = { showFilterSheet = true },
                    )
                }
            }

            when {
                // A skeleton at the real row height, not a line of text: nothing moves when the
                // rows arrive, and it says what is coming rather than that something is happening.
                state.loading -> StatementSkeleton(
                    Modifier.padding(horizontal = Space.edge, vertical = Space.s3),
                )

                state.groups.isEmpty() -> EmptyState(
                    title = if (state.query.isNotBlank()) {
                        "Nothing matches \"${state.query}\""
                    } else if (state.filtering) {
                        "Nothing under ${state.categoryFilter.label()}"
                    } else {
                        "Nothing recorded yet"
                    },
                    body = if (state.query.isNotBlank()) {
                        "Search looks at the payee and your notes, not the bank's original message."
                    } else null,
                    icon = OursIcon.NoResults,
                    // An action only where there is genuinely one to take.
                    action = if (state.query.isNotBlank() || state.filtering) {
                        {
                            GhostButton(
                                label = if (state.query.isNotBlank()) "Clear search" else "Clear filter",
                                onClick = {
                                    if (state.query.isNotBlank()) viewModel.setQuery("")
                                    else viewModel.setCategoryFilter(CategoryFilter.All)
                                },
                            )
                        }
                    } else null,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
                ) {
                    state.groups.forEach { group ->
                        stickyHeader(key = "header-${group.label}") {
                            // Opaque, or the rows scroll visibly through the rule.
                            Box(Modifier.background(Ours.surface)) {
                                TapeHeader(
                                    label = group.label,
                                    trailing = Money.exact(group.totalPaise),
                                    modifier = Modifier.padding(
                                        horizontal = Space.edge,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                        }
                        items(group.transactions, key = { it.id }) { txn ->
                            TransactionListRow(
                                txn = txn,
                                showOwner = state.hasPartner,
                                divider = txn.id != group.transactions.last().id,
                                selected = txn.id in state.selected,
                                // In selection mode a tap picks rather than opens.
                                // Opening a detail screen mid-selection would lose the
                                // set you had built up.
                                onClick = {
                                    if (state.selectionMode) viewModel.toggleSelected(txn.id)
                                    else onTransactionClick(txn.id)
                                },
                                onLongClick = { viewModel.toggleSelected(txn.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (bulkCategorize) {
        CategoryPickerSheet(
            title = "Category for ${state.selected.size} transactions",
            onDismiss = { bulkCategorize = false },
            onPick = { category ->
                viewModel.recategorizeSelected(category)
                bulkCategorize = false
            },
        )
    }

    if (showFilterSheet) {
        CategoryFilterSheet(
            current = state.categoryFilter,
            counts = state.counts,
            untaggedCount = state.untaggedCount,
            totalCount = state.baseCount,
            onDismiss = { showFilterSheet = false },
            onApply = viewModel::setCategoryFilter,
        )
    }
}

/** What a filter calls itself on a chip and on the collapsed line. */
private fun CategoryFilter.label(): String = when (this) {
    CategoryFilter.All -> "All"
    CategoryFilter.Untagged -> "Untagged"
    is CategoryFilter.Of -> category.label
}

/**
 * The chosen filter, once the chips have done their job and got out of the way.
 *
 * The old row left the selected chip highlighted among the others, which fails in a way
 * that is easy to miss: filter to Rent, scroll the strip back to the left, and the screen
 * is indistinguishable from an unfiltered one that happens to be short. Hidden rows then
 * read as absent rows, which is the worst thing a filter can do to a ledger.
 *
 * This cannot scroll away. It sits where the chips were, says what is being shown and how
 * much it comes to, and carries its own way out.
 */
@Composable
private fun ActiveFilterLine(
    label: String,
    detail: String,
    tint: Color,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.13f))
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            // The body reopens the sheet: having decided to filter, the next thing you
            // want is usually a different filter, not no filter.
            .clickable(onClick = onOpen)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicroLabel(label, color = tint)
        MicroLabel(detail, modifier = Modifier.weight(1f))
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClear)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Search as a hairline field rather than a filled Material box.
 *
 * An `OutlinedTextField` brings its own label, container tint and 56dp minimum with it,
 * none of which belong on a page whose whole structure is one-pixel rules.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ours.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        OursIconView(
            OursIcon.Search,
            contentDescription = null,
            tint = Ours.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search merchant or note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ours.onSurfaceMuted,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium
                ).copy(color = Ours.onSurface),
                cursorBrush = SolidColor(Ours.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            OursIconButton(
                icon = OursIcon.Dismiss,
                contentDescription = "Clear search",
                onClick = { onQueryChange("") },
                tint = Ours.onSurfaceVariant,
                glyph = 16.dp,
                // 40dp: it sits inside the field, so a full 48 would make the field taller
                // than the 48dp the field itself already is.
                size = Space.targetTight,
            )
        }
    }
}

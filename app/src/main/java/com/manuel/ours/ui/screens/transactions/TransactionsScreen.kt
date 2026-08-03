package com.manuel.ours.ui.screens.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.CategoryPickerSheet
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.SwipeableTransactionRow
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.WordmarkStyle

private val EDGE = 15.dp

/**
 * The full statement.
 *
 * Home shows today; this is every line, still printed the same way. The date rules
 * stick to the top while you scroll, because when every row is structurally alike the
 * date is the only thing telling you where you are.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    onTransactionClick: (String) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingCategoryFor by remember { mutableStateOf<String?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    // Undo is what makes a destructive swipe safe. Deleting rows behind a gesture with
    // no way back is how people lose data they only meant to scroll past.
    LaunchedEffect(state.lastDeletedId) {
        val deleted = state.lastDeletedId ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = "Transaction deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(deleted)
        else viewModel.clearUndo()
    }

    Scaffold(
        containerColor = Ours.ink,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ACTIVITY", style = WordmarkStyle, color = Ours.text)
                val shown = state.groups.sumOf { it.transactions.size }
                MicroLabel("$shown ${if (shown == 1) "entry" else "entries"}")
            }

            SearchField(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = EDGE, vertical = 8.dp),
            )

            if (state.hasPartner) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
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

            LazyRow(
                contentPadding = PaddingValues(horizontal = EDGE, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    OursChip(
                        label = "All",
                        selected = state.categoryFilter == null,
                        onClick = { viewModel.setCategoryFilter(null) },
                    )
                }
                items(Category.entries) { category ->
                    OursChip(
                        label = category.label,
                        selected = state.categoryFilter == category,
                        icon = BiIcon.forCategory(category),
                        onClick = {
                            viewModel.setCategoryFilter(
                                if (state.categoryFilter == category) null else category
                            )
                        },
                    )
                }
            }

            when {
                state.loading -> QuietEmpty(
                    "Reading the month",
                    modifier = Modifier.padding(top = 32.dp),
                )

                state.groups.isEmpty() -> QuietEmpty(
                    text = if (state.query.isNotBlank()) {
                        "Nothing matches \"${state.query}\""
                    } else {
                        "Nothing recorded yet"
                    },
                    icon = BiIcon.NoResults,
                    modifier = Modifier.padding(top = 32.dp),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
                ) {
                    state.groups.forEach { group ->
                        stickyHeader(key = "header-${group.label}") {
                            // Opaque, or the rows scroll visibly through the rule.
                            Box(Modifier.background(Ours.ink)) {
                                TapeHeader(
                                    label = group.label,
                                    trailing = Money.whole(group.totalPaise),
                                    modifier = Modifier.padding(
                                        horizontal = EDGE,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                        }
                        items(group.transactions, key = { it.id }) { txn ->
                            SwipeableTransactionRow(
                                txn = txn,
                                showOwner = state.hasPartner,
                                divider = txn.id != group.transactions.last().id,
                                onClick = { onTransactionClick(txn.id) },
                                onDelete = { viewModel.deleteWithUndo(txn.id) },
                                onCategorize = { pendingCategoryFor = txn.id },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingCategoryFor?.let { txnId ->
        CategoryPickerSheet(
            onDismiss = { pendingCategoryFor = null },
            onPick = { category ->
                viewModel.recategorize(txnId, category)
                pendingCategoryFor = null
            },
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
            .background(Ours.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        BiIconView(
            BiIcon.Search,
            contentDescription = null,
            tint = Ours.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search merchant or note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ours.textLabel,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium
                ).copy(color = Ours.text),
                cursorBrush = SolidColor(Ours.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            BiIconView(
                BiIcon.Dismiss,
                contentDescription = "Clear search",
                tint = Ours.textSecondary,
                modifier = Modifier
                    .size(13.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

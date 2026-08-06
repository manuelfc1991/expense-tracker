package com.manuel.ours.ui.screens.sort

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.StatementSkeleton
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.CategoryGrid
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space


/**
 * Sorting, grouped by merchant.
 *
 * Ninety-four unsorted rows is a chore nobody starts. The same rows grouped by who was
 * paid is roughly six decisions, and each one is a decision a person can actually make
 * — "Keecheril St is food" is knowledge you have; "transaction 47 of 94" is not.
 *
 * Every assignment is also a rule, so the same merchant never asks again.
 */
@Composable
fun SortScreen(
    onBack: () -> Unit,
    viewModel: SortViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<SortGroup?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
                OursTopBar(
                    title = "SORT",
                    onBack = onBack,
                ) {
                    MicroLabel(
                    "${state.totalRemaining} left · ${state.groups.size} " +
                        if (state.groups.size == 1) "group" else "groups"
                )
                }
            }

        item {
            val sorted = (state.startingTotal - state.totalRemaining).toFloat()
            Ruler(
                fraction = if (state.startingTotal > 0) sorted / state.startingTotal else 0f,
                height = 12.dp,
                modifier = Modifier.padding(horizontal = Space.edge),
            )
        }

        if (state.loading) {
            item { StatementSkeleton(Modifier.padding(horizontal = Space.edge, vertical = Space.s3)) }
        } else if (state.groups.isEmpty()) {
            item {
                EmptyState(
                    title = "Everything is sorted",
                    body = "Every payment has a category, and the rules you set along the way " +
                        "will handle the next ones.",
                    icon = OursIcon.Done,
                    iconTint = Ours.success,
                    action = { GhostButton("Back to Activity", onClick = onBack) },
                )
            }
        } else {
            items(state.groups, key = { it.merchant }) { group ->
                GroupCard(
                    group = group,
                    settledAs = state.doneMerchants[group.merchant],
                    onAssign = { viewModel.assign(group, it) },
                    onMore = { picking = group },
                )
            }
        }
    }

    picking?.let { group ->
        CategorySheet(
            group = group,
            onPick = {
                viewModel.assign(group, it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: SortGroup,
    settledAs: Category?,
    onAssign: (Category) -> Unit,
    onMore: () -> Unit,
) {
    val done = settledAs != null
    // A sorted group fades instead of disappearing. Rows vanishing under your thumb
    // makes the list feel unstable and costs you the chance to notice a mistake.
    val edge = when {
        done -> Ours.outlineVariant
        group.unknownPayee -> Ours.warning.copy(alpha = 0.35f)
        else -> Ours.outlineVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge)
            .alpha(if (done) 0.45f else 1f)
            .clip(RoundedCornerShape(13.dp))
            .background(Ours.surfaceContainer)
            .border(1.dp, edge, RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (settledAs != null) {
                // A settled group states its outcome: a tick in the positive colour and
                // the category it landed in. Leaving the guess-tinted mark and the
                // payment count would make a finished decision look like a pending one.
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ours.success.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    OursIconView(
                        OursIcon.Done,
                        contentDescription = null,
                        tint = Ours.success,
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else {
                // Tinted with the leading guess rather than left grey: the colour is a
                // preview of what tapping the first chip would do.
                CategoryAvatar(
                    category = group.suggestions.firstOrNull() ?: Category.OTHER,
                    size = 32.dp,
                    overrideIcon = if (group.unknownPayee) OursIcon.NeedsReview else null,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    group.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                    maxLines = 1,
                )
                MicroLabel(
                    if (settledAs != null) {
                        "${settledAs.label} · ${group.count} sorted"
                    } else {
                        "${group.count} ${if (group.count == 1) "payment" else "payments"}"
                    }
                )
            }
            if (settledAs == null) AmountColumn(group.totalPaise)
        }

        if (done) {
            // No chips on a settled group: there is nothing left to choose.
        } else if (group.unknownPayee) {
            // Plain language, not accounting language. "Uncategorised debit" is a
            // description of the database; this is a description of what happened.
            // Counted, and said so. This used to read "Held out of spending until you
            // decide", which was the opposite of the truth: a debit the bank named no
            // payee for lands in Transfers, and Transfers counts as spending — on the
            // evidence that 83 of 85 such rows were money sent to somebody else. The
            // row is in the total from the moment it arrives, so the screen has to say
            // so, or the one number the household trusts is quietly wrong.
            Text(
                "The bank named nobody, so it's counted as spending for now.",
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // "It was spending" is the filled chip, and the money model is why.
                //
                // A filled chip is a recommendation, and this one used to recommend
                // "Moving money" — against the app's own evidence that **83 of 85**
                // unnamed-payee debits were money sent to other people, not shuffled
                // between one's own accounts (see `Category.TRANSFERS`). The easiest
                // tap on the screen was the wrong answer roughly ninety-eight times in
                // a hundred, and getting it wrong removes a real expense from the
                // month — the one number the household actually reads.
                //
                // Both answers are still one tap. Only the emphasis moved.
                OursChip(
                    label = "It was spending",
                    selected = true,
                    onClick = onMore,
                )
                // Assigns Ours, not Transfers.
                //
                // Tapping this used to write Transfers — the category the row was
                // already in — so it changed nothing at all: same category, same total,
                // and the payment came straight back next time. "Moving money" has to
                // mean money that left one of your accounts for another, which is Ours,
                // and Ours is neutral.
                //
                // Kept because `markSelfTransfers` can only recognise a self-transfer
                // from the matching pair; when the other leg never arrives as a message,
                // a person saying so is the only evidence there is.
                OursChip(
                    label = "Moving money",
                    selected = false,
                    icon = OursIcon.forCategory(Category.SELF_TRANSFER),
                    onClick = { onAssign(Category.SELF_TRANSFER) },
                )
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.suggestions.forEachIndexed { index, category ->
                    OursChip(
                        label = category.label,
                        // The leading guess is filled and the rest are outlines, so the
                        // one-tap path is obvious at a glance. Ninety-four rows become
                        // six decisions only if each decision is already half-made.
                        selected = index == 0,
                        icon = OursIcon.forCategory(category),
                        iconTint = Ours.forCategory(category),
                        onClick = { onAssign(category) },
                    )
                }
                OursChip(label = "More", selected = false, onClick = onMore)
            }
            if (group.count > 1) {
                MicroLabel("Applies to all ${group.count}, and every future one")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategorySheet(
    group: SortGroup,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.edge).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicroLabel(group.merchant)
            Text(
                "${group.count} ${if (group.count == 1) "payment" else "payments"} · " +
                    Money.exact(group.totalPaise),
                style = MaterialTheme.typography.titleMedium,
                color = Ours.onSurface,
            )
            // The same grid as the detail screen, the add sheet and the filter.
            //
            // This was the last wrapped flow of chips left: variable widths, a ragged
            // right edge, and a different row length whenever the list changed, so the
            // one screen where you categorise dozens of rows in a row was the one screen
            // where the categories were never in the same place twice.
            CategoryGrid(
                selected = null,
                onSelect = onPick,
                options = Category.EVERY,
            )
        }
    }
}

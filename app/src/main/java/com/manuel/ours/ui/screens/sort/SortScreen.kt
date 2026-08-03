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
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.WordmarkStyle

private val EDGE = 15.dp

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BiIconView(
                        BiIcon.Back,
                        contentDescription = "Back",
                        tint = Ours.textSecondary,
                        modifier = Modifier.size(16.dp).clickable(onClick = onBack),
                    )
                    Text("SORT", style = WordmarkStyle, color = Ours.text)
                }
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
                modifier = Modifier.padding(horizontal = EDGE),
            )
        }

        if (state.loading) {
            item { QuietEmpty("Looking through this month", modifier = Modifier.padding(top = 24.dp)) }
        } else if (state.groups.isEmpty()) {
            item {
                QuietEmpty(
                    "Everything is sorted",
                    icon = BiIcon.Done,
                    modifier = Modifier.padding(top = 24.dp),
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
        done -> Ours.hairline
        group.unknownPayee -> Ours.warning.copy(alpha = 0.35f)
        else -> Ours.hairline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE)
            .alpha(if (done) 0.45f else 1f)
            .clip(RoundedCornerShape(13.dp))
            .background(Ours.surface)
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
                        .background(Ours.positive.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BiIconView(
                        BiIcon.Done,
                        contentDescription = null,
                        tint = Ours.positive,
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else {
                // Tinted with the leading guess rather than left grey: the colour is a
                // preview of what tapping the first chip would do.
                CategoryAvatar(
                    category = group.suggestions.firstOrNull() ?: Category.OTHER,
                    size = 32.dp,
                    overrideIcon = if (group.unknownPayee) BiIcon.NeedsReview else null,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    group.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.text,
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
            Text(
                "The bank named nobody. Held out of spending until you decide.",
                style = MaterialTheme.typography.bodySmall,
                color = Ours.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Filled, as the mockup has it: the likely answer for a payment the
                // bank named nobody for is that it was money moved, not spent. It is a
                // recommendation rather than a selection — nothing is applied until it
                // is tapped — but it is the one chip worth aiming a thumb at.
                OursChip(
                    label = "Moving money",
                    selected = true,
                    icon = BiIcon.forCategory(Category.TRANSFERS),
                    onClick = { onAssign(Category.TRANSFERS) },
                )
                OursChip(
                    label = "It was spending",
                    selected = false,
                    onClick = onMore,
                )
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                group.suggestions.forEachIndexed { index, category ->
                    OursChip(
                        label = category.shortLabel,
                        // The leading guess is filled and the rest are outlines, so the
                        // one-tap path is obvious at a glance. Ninety-four rows become
                        // six decisions only if each decision is already half-made.
                        selected = index == 0,
                        icon = BiIcon.forCategory(category),
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
        containerColor = Ours.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = EDGE).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicroLabel(group.merchant)
            Text(
                "${group.count} ${if (group.count == 1) "payment" else "payments"} · " +
                    Money.whole(group.totalPaise),
                style = MaterialTheme.typography.titleMedium,
                color = Ours.text,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Category.entries.filter { it != Category.OTHER }.forEach { category ->
                    OursChip(
                        label = category.label,
                        selected = false,
                        icon = BiIcon.forCategory(category),
                        onClick = { onPick(category) },
                    )
                }
            }
        }
    }
}

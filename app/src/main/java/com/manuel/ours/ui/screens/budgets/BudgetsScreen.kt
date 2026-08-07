package com.manuel.ours.ui.screens.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.Meter
import com.manuel.ours.ui.components.PrimaryAction
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.colorForCategory


/**
 * Budgets, measured on the same ruler Home uses.
 *
 * The overall budget gets the tick scale because it is the one you check against a
 * decision; the per-category rows get plain meters, because a dozen tick scales stacked
 * up stops reading as measurement and starts reading as texture.
 */
@Composable
fun BudgetsScreen(viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingOverall by rememberSaveable { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    // Nothing to reset when the household has never set a cap, and an action that would
    // do nothing is worse than no action: it reads as broken rather than as inapplicable.
    val anyBudgetSet = state.overallLimit != null ||
        state.categoryProgress.any { it.limitPaise != null }

    // No Scaffold: nothing here needs one, and nesting it inside the nav host — which
    // already carries the outer Scaffold's innerPadding — applied the system bar inset
    // a second time and cost the screen ~73dp against Home and Summary.
    Box(Modifier.fillMaxSize().background(Ours.surface).imePadding()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 96dp is Home's allowance for the floating add button, and Activity's
            // for the undo snackbar. Nothing floats over this list, so the same figure
            // was simply a screen-height of blank below the last panel.
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OursTopBar(title = "Budgets") {
                    if (state.overallLimit != null) {
                        OursIconButton(
                            icon = OursIcon.Categorise,
                            contentDescription = "Edit the monthly budget",
                            onClick = { editingOverall = !editingOverall },
                            tint = Ours.primary,
                        )
                    }
                }
            }

            item { OverallBudget(state.spentThisMonth, state.overallLimit) }

            if (state.overallLimit == null) {
                item {
                    PrimaryAction(
                        title = "Set a monthly budget",
                        caption = "One number · everything else is optional",
                        onClick = { editingOverall = true },
                        modifier = Modifier.padding(horizontal = Space.edge),
                    )
                }
            }

            if (editingOverall) {
                item {
                    BudgetEditor(
                        initial = state.overallLimit,
                        label = "Monthly budget",
                        onSave = {
                            viewModel.setOverall(it)
                            editingOverall = false
                        },
                        onCancel = { editingOverall = false },
                        onClear = {
                            viewModel.clearBudget(null)
                            editingOverall = false
                        },
                    )
                }
            }

            item {
                TapeHeader(
                    label = "Per category",
                    modifier = Modifier.padding(horizontal = Space.edge, vertical = 4.dp),
                )
            }

            // Keyed by category, never by index.
            //
            // The list is sorted by spend off a live flow, so an arriving SMS can reorder
            // it. With index keys the open editor and its remembered text stayed with the
            // *slot*: type a limit for Food, let a payment push another category above it,
            // and Save wrote Food's figure onto whatever had taken its place. A stable key
            // moves the state with the row.
            items(
                state.categoryProgress,
                key = { it.category.name },
            ) { progress ->
                CategoryBudgetRow(
                    progress = progress,
                    onSetLimit = { limit ->
                        viewModel.setCategoryBudget(progress.category, limit)
                    },
                    onClearLimit = {
                        viewModel.clearBudget(progress.category)
                    },
                )
            }

            if (anyBudgetSet) {
                item {
                    MicroLabel(
                        "Reset all budgets",
                        color = Ours.error,
                        modifier = Modifier
                            .padding(horizontal = Space.edge, vertical = 12.dp)
                            .clickable { confirmingReset = true },
                    )
                }
            }
        }
    }

    // Confirmed, unlike the single-budget clear: this drops the overall cap and every
    // category at once, it travels to the other phone, and retyping them is not one
    // number but all of them.
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = Ours.surfaceContainer,
            title = { Text("Reset all budgets?", color = Ours.onSurface) },
            text = {
                Text(
                    "The monthly budget and every category limit are dropped, on both " +
                        "phones. Spending itself is untouched — only the caps go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetAllBudgets()
                        editingOverall = false
                        confirmingReset = false
                    },
                ) { Text("Reset", color = Ours.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text("Cancel", color = Ours.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun OverallBudget(spentPaise: Long, limitPaise: Long?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel("Spent this month")
        Text(
            text = Money.exact(spentPaise),
            style = MaterialTheme.typography.displayLarge,
            color = Ours.onSurface,
            maxLines = 1,
        )

        if (limitPaise != null && limitPaise > 0) {
            val fraction = spentPaise.toFloat() / limitPaise
            val over = spentPaise > limitPaise
            Ruler(fraction = fraction, over = over)
            Row(
                Modifier.fillMaxWidth().padding(top = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabelOverValue("Budget", Money.formatCompact(limitPaise))
                LabelOverValue(
                    label = "Used",
                    value = "${(fraction * 100).toInt()}%",
                    valueColor = if (over) Ours.error else Ours.success,
                    alignment = Alignment.CenterHorizontally,
                )
                LabelOverValue(
                    label = if (over) "Over" else "Left",
                    value = Money.whole(kotlin.math.abs(limitPaise - spentPaise)),
                    valueColor = if (over) Ours.error else Ours.success,
                    alignment = Alignment.End,
                )
            }
        }
    }
}

@Composable
private fun CategoryBudgetRow(
    progress: CategoryBudgetProgress,
    onSetLimit: (Long) -> Unit,
    onClearLimit: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val limit = progress.limitPaise
    val fraction = if (limit != null && limit > 0) progress.spentPaise.toFloat() / limit else 0f
    val over = limit != null && progress.spentPaise > limit

    // Category colour until it is in trouble, then the semantic one. A red bar has to
    // mean "over" everywhere, so a category whose own hue happens to be red cannot own
    // that meaning.
    val barColor = when {
        limit == null -> Ours.outlineVariant
        over -> Ours.error
        fraction >= 0.8f -> Ours.warning
        else -> colorForCategory(progress.category)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { editing = !editing },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CategoryAvatar(progress.category, size = 26.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    progress.category.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                    maxLines = 1,
                )
                MicroLabel(
                    if (limit == null) "No limit" else "of ${Money.formatCompact(limit)}",
                    color = if (over) Ours.error else Ours.onSurfaceMuted,
                )
            }
            Text(
                text = Money.bare(progress.spentPaise, withDecimals = true),
                style = ValueTextStyle,
                color = Ours.onSurface,
                maxLines = 1,
            )
        }

        if (limit != null) {
            Meter(fraction = fraction, color = barColor)
        }

        if (editing) {
            BudgetEditor(
                initial = limit,
                label = "${progress.category.label} limit",
                onSave = { onSetLimit(it); editing = false },
                onCancel = { editing = false },
                inset = false,
                onClear = { onClearLimit(); editing = false },
            )
        }
    }
}

/**
 * Amount entry as one hairline field.
 *
 * Whole rupees only — a budget with paise in it is a number nobody chose, and the
 * digit filter means the keyboard cannot produce one.
 */
@Composable
private fun BudgetEditor(
    initial: Long?,
    label: String,
    onSave: (Long) -> Unit,
    onCancel: () -> Unit,
    inset: Boolean = true,
    /** Offered only where there is a cap to drop — a budget that is not set has none. */
    onClear: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial?.let { (it / 100).toString() } ?: "") }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    val rupees = text.toLongOrNull()
    val valid = rupees != null && rupees > 0

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) Space.edge else 0.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Ours.surfaceContainer)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("₹", style = MaterialTheme.typography.displayMedium, color = Ours.onSurfaceMuted)
            BasicTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(9) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.displayMedium)
                    .copy(color = Ours.onSurface),
                cursorBrush = SolidColor(Ours.primary),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                GhostButton("Cancel", onClick = onCancel)
            }
            Box(Modifier.weight(1f)) {
                AccentButton(
                    label = "Save",
                    enabled = valid,
                    icon = OursIcon.Done,
                    onClick = { rupees?.let { onSave(it * 100) } },
                )
            }
        }
        // Below the pair rather than beside them: removing a cap is not the third of
        // three equal choices, and a full-width red button next to Save invites the
        // wrong one.
        if (onClear != null && initial != null) {
            MicroLabel(
                "Clear this budget",
                color = Ours.error,
                modifier = Modifier.clickable { confirmingClear = true },
            )
        }
    }

    // Confirmed like the reset is. The label sits directly under Save, so a thumb aiming
    // for one can reach the other, and the two do opposite things — a mis-tap that
    // silently drops the cap looks identical to a save that quietly failed.
    if (confirmingClear && onClear != null) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            containerColor = Ours.surfaceContainer,
            title = { Text("Clear this budget?", color = Ours.onSurface) },
            text = {
                Text(
                    "The limit is dropped on both phones. Spending itself is untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClear()
                    },
                ) { Text("Clear", color = Ours.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text("Cancel", color = Ours.onSurfaceVariant)
                }
            },
        )
    }
}

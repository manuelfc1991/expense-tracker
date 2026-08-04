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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.Meter
import com.manuel.ours.ui.components.PrimaryAction
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.WordmarkStyle
import com.manuel.ours.ui.theme.colorForCategory

private val EDGE = 15.dp

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
    var editingOverall by remember { mutableStateOf(false) }

    Scaffold(containerColor = Ours.ink) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // 96dp is Home's allowance for the floating add button, and Activity's
            // for the undo snackbar. Nothing floats over this list, so the same figure
            // was simply a screen-height of blank below the last panel.
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("BUDGETS", style = WordmarkStyle, color = Ours.text)
                    if (state.overallLimit != null) {
                        MicroLabel(
                            "Edit",
                            color = Ours.accent,
                            modifier = Modifier.clickable { editingOverall = !editingOverall },
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
                        modifier = Modifier.padding(horizontal = EDGE),
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
                    )
                }
            }

            item {
                TapeHeader(
                    label = "Per category",
                    modifier = Modifier.padding(horizontal = EDGE, vertical = 4.dp),
                )
            }

            items(state.categoryProgress.size) { index ->
                CategoryBudgetRow(
                    progress = state.categoryProgress[index],
                    onSetLimit = { limit ->
                        viewModel.setCategoryBudget(state.categoryProgress[index].category, limit)
                    },
                )
            }
        }
    }
}

@Composable
private fun OverallBudget(spentPaise: Long, limitPaise: Long?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel("Spent this month")
        Text(
            text = Money.whole(spentPaise),
            style = MaterialTheme.typography.displayLarge,
            color = Ours.text,
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
                    valueColor = if (over) Ours.negative else Ours.positive,
                    alignment = Alignment.CenterHorizontally,
                )
                LabelOverValue(
                    label = if (over) "Over" else "Left",
                    value = Money.whole(kotlin.math.abs(limitPaise - spentPaise)),
                    valueColor = if (over) Ours.negative else Ours.positive,
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
) {
    var editing by remember { mutableStateOf(false) }
    val limit = progress.limitPaise
    val fraction = if (limit != null && limit > 0) progress.spentPaise.toFloat() / limit else 0f
    val over = limit != null && progress.spentPaise > limit

    // Category colour until it is in trouble, then the semantic one. A red bar has to
    // mean "over" everywhere, so a category whose own hue happens to be red cannot own
    // that meaning.
    val barColor = when {
        limit == null -> Ours.hairline
        over -> Ours.negative
        fraction >= 0.8f -> Ours.warning
        else -> colorForCategory(progress.category)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = EDGE),
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
                    color = Ours.text,
                    maxLines = 1,
                )
                MicroLabel(
                    if (limit == null) "No limit" else "of ${Money.formatCompact(limit)}",
                    color = if (over) Ours.negative else Ours.textLabel,
                )
            }
            Text(
                text = Money.bare(progress.spentPaise - progress.spentPaise % 100),
                style = ValueTextStyle,
                color = Ours.text,
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
) {
    var text by remember { mutableStateOf(initial?.let { (it / 100).toString() } ?: "") }
    val rupees = text.toLongOrNull()
    val valid = rupees != null && rupees > 0

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) EDGE else 0.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Ours.surface)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("₹", style = MaterialTheme.typography.displayMedium, color = Ours.textLabel)
            BasicTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(9) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.displayMedium)
                    .copy(color = Ours.text),
                cursorBrush = SolidColor(Ours.accent),
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
                    icon = BiIcon.Done,
                    onClick = { rupees?.let { onSave(it * 100) } },
                )
            }
        }
    }
}

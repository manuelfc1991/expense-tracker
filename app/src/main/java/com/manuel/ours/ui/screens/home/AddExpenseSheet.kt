package com.manuel.ours.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours

/**
 * Manual entry, for the payment no bank messaged you about.
 *
 * Amount first and very large, on the numeric keypad: it is the only field you always
 * know, and it is also the only one that cannot be guessed later.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    onConfirm: (
        amountPaise: Long,
        merchant: String,
        category: Category,
        split: SplitType,
        note: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.FOOD) }
    var splitType by remember { mutableStateOf(SplitType.SHARED) }

    val amountPaise = Money.parseToPaise(amountText)
    val valid = amountPaise != null && amountPaise > 0 && merchant.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MicroLabel("New expense")

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("₹", style = MaterialTheme.typography.displayLarge, color = Ours.textLabel)
                BasicTextField(
                    value = amountText,
                    onValueChange = { input ->
                        amountText = input.filter { it.isDigit() || it == '.' }.take(12)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = LocalTextStyle.current
                        .merge(MaterialTheme.typography.displayLarge)
                        .copy(color = Ours.text),
                    cursorBrush = SolidColor(Ours.accent),
                    modifier = Modifier.weight(1f),
                )
            }

            Field(
                value = merchant,
                onValueChange = { merchant = it },
                placeholder = "Paid to",
            )

            TapeHeader("Category")
            // Wrapped, like the detail screen and the capture sheet. A sideways strip
            // showed five of these and gave no sign the rest existed, which is how a
            // ledger fills up with Other.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Category.entries.filter { it != Category.INCOME }.forEach { option ->
                    OursChip(
                        label = option.shortLabel,
                        selected = category == option,
                        icon = BiIcon.forCategory(option),
                        onClick = { category = option },
                    )
                }
            }

            TapeHeader("Counts as")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OursChip(
                    label = "Household",
                    selected = splitType == SplitType.SHARED,
                    onClick = { splitType = SplitType.SHARED },
                )
                OursChip(
                    label = "Personal",
                    selected = splitType == SplitType.PERSONAL,
                    onClick = { splitType = SplitType.PERSONAL },
                )
            }

            // Cash is the reason this screen exists — the one kind of spending no bank
            // will ever text about — so the note carries what the missing message
            // would have said.
            Field(
                value = note,
                onValueChange = { note = it },
                placeholder = "Add a note — optional",
            )

            AccentButton(
                label = if (amountPaise != null && amountPaise > 0) {
                    "Add ${Money.format(amountPaise)}"
                } else "Add expense",
                enabled = valid,
                onClick = {
                    onConfirm(
                        amountPaise ?: 0L, merchant.trim(), category, splitType, note.trim(),
                    )
                },
            )
        }
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ours.surface)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ours.textLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.bodyLarge)
                .copy(color = Ours.text),
            cursorBrush = SolidColor(Ours.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

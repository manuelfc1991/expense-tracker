package com.manuel.ours.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.CategoryGrid
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.SheetField
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.SheetAmountStyle

/**
 * Manual entry, for the payment no bank messaged you about.
 *
 * The same sheet as the capture prompt, asked in the other direction. A captured payment
 * already knows its amount and its payee and only needs sorting; a manual one knows
 * nothing and has to ask — but it asks in the same shape, with the same grid and the
 * same optional note, because it is the same job.
 *
 * **The amount leads**, and the keyboard opens on it. It is the only field that cannot
 * be guessed, corrected later from a message, or left out: an expense with an amount and
 * nothing else is still a true row, while one with a payee and no amount is not a row at
 * all. So it is also the only thing Save waits for — the sheet does not mark three
 * fields optional and then refuse to proceed without them.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }

    val amountPaise = Money.parseToPaise(amountText)
    val valid = amountPaise != null && amountPaise > 0

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

            // Both halves dim until there is a figure, so an untouched sheet shows a
            // grey ₹0 rather than a lone rupee sign floating beside nothing.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "₹",
                    style = SheetAmountStyle,
                    color = if (valid) Ours.text else Ours.textLabel,
                )
                Box(Modifier.weight(1f)) {
                    if (amountText.isEmpty()) {
                        Text("0", style = SheetAmountStyle, color = Ours.textLabel)
                    }
                    BasicTextField(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input.filter { it.isDigit() || it == '.' }.take(12)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = LocalTextStyle.current
                            .merge(SheetAmountStyle)
                            .copy(color = Ours.text),
                        cursorBrush = SolidColor(Ours.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(amountFocus),
                    )
                }
            }

            SheetField(
                value = merchant,
                onValueChange = { merchant = it },
                placeholder = "Who did you pay?",
                tag = "Optional",
                filledTag = "Edit",
            )

            MicroLabel("Category")
            CategoryGrid(
                selected = category,
                onSelect = { category = it },
                options = Category.SPENDING,
            )

            MicroLabel("Counts as")
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
            // will ever text about — so the note carries what the missing message would
            // have said.
            SheetField(
                value = note,
                onValueChange = { note = it },
                placeholder = "Add a note",
                tag = "Optional",
                filledTag = "Note",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GhostButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                AccentButton(
                    label = "Save",
                    enabled = valid,
                    dimWhenDisabled = true,
                    onClick = {
                        onConfirm(
                            amountPaise ?: 0L,
                            // An unnamed manual row is almost always cash, and "Cash" is
                            // a truer answer than an empty merchant column that reads as
                            // a parsing failure later.
                            merchant.trim().ifBlank { "Cash" },
                            category,
                            splitType,
                            note.trim(),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

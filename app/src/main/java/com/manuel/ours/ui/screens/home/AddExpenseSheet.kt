package com.manuel.ours.ui.screens.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.manuel.ours.core.OursZone
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.shortLabel
import com.manuel.ours.domain.model.PaidFrom
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.CategoryGrid
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.OursIcon
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    /**
     * The accounts to offer under "Paid from". Empty is fine — the sheet still offers Cash
     * and "Not sure", which are the two answers no list of accounts can contain.
     */
    accounts: List<AccountBalance> = emptyList(),
    onConfirm: (
        amountPaise: Long,
        merchant: String,
        category: Category,
        split: SplitType,
        note: String,
        occurredAt: Long,
        accountTail: String?,
        bank: String?,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.FOOD) }
    // "Now" is the default, so the common case still costs nothing. Without this row a cash
    // lunch entered in the evening lands in the wrong hour — and entered after midnight, in the
    // wrong day's subtotal, which is the one figure a statement has to get right.
    var whenPicked by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var splitType by remember { mutableStateOf(SplitType.SHARED) }
    // Cash by default. This sheet exists for the payment no bank messaged about, and that
    // is overwhelmingly cash — a default of "not sure" would make the common case the one
    // that needs a tap.
    var paidFrom by remember { mutableStateOf<PaidFrom>(PaidFrom.Cash) }

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }

    val amountPaise = Money.parseToPaise(amountText)
    val valid = amountPaise != null && amountPaise > 0

    if (pickingDate) {
        ManualDatePicker(
            initial = whenPicked ?: System.currentTimeMillis(),
            onPick = { whenPicked = it; pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // imePadding shrinks the viewport, verticalScroll lets the rest be reached.
                // Without the scroll this sheet simply clipped: it was already close to a
                // phone's height with the keyboard up, and adding the "Paid from" row put
                // Counts as, the note and **Save** underneath the keyboard with no way to
                // get at them. A form whose submit button cannot be reached is not a form.
                .imePadding()
                .verticalScroll(rememberScrollState())
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
                    color = if (valid) Ours.onSurface else Ours.onSurfaceMuted,
                )
                Box(Modifier.weight(1f)) {
                    if (amountText.isEmpty()) {
                        Text("0", style = SheetAmountStyle, color = Ours.onSurfaceMuted)
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
                            .copy(color = Ours.onSurface),
                        cursorBrush = SolidColor(Ours.primary),
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
            CategoryGrid(selected = category, onSelect = { category = it })

            MicroLabel("When")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OursChip(
                    label = "Now",
                    selected = whenPicked == null,
                    onClick = { whenPicked = null },
                )
                OursChip(
                    label = "Earlier today",
                    selected = whenPicked?.let { OursZone.dateOf(it) == OursZone.today() } == true,
                    onClick = {
                        // Midday today: a time somebody would recognise as "earlier", and safely
                        // inside the same day in the household's zone.
                        whenPicked = OursZone.startOfDay(OursZone.today()) + 12 * 3_600_000L
                    },
                )
                OursChip(
                    label = whenPicked
                        ?.takeIf { OursZone.dateOf(it) != OursZone.today() }
                        ?.let { OursZone.format(it, OursZone.day) }
                        ?: "Pick a date",
                    selected = whenPicked?.let { OursZone.dateOf(it) != OursZone.today() } == true,
                    onClick = { pickingDate = true },
                )
            }

            // Which account it came out of.
            //
            // Nothing filled this in before, and `accountBalances()` discards rows with
            // neither a tail nor a bank — so every hand-added payment was invisible to the
            // Accounts tab. Attribution only: selecting an account does not move its
            // balance, because balances here are quoted by the bank, never derived.
            MicroLabel("Paid from")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OursChip(
                    label = "Cash",
                    selected = paidFrom == PaidFrom.Cash,
                    icon = OursIcon.Cash,
                    onClick = { paidFrom = PaidFrom.Cash },
                )
                accounts.forEach { account ->
                    val option = PaidFrom.Account(account.accountTail, account.bank)
                    OursChip(
                        label = account.shortLabel(),
                        selected = paidFrom == option,
                        icon = OursIcon.Bank,
                        onClick = { paidFrom = option },
                    )
                }
                OursChip(
                    label = "Not sure",
                    selected = paidFrom == PaidFrom.Unknown,
                    onClick = { paidFrom = PaidFrom.Unknown },
                )
            }

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
                            whenPicked ?: System.currentTimeMillis(),
                            paidFrom.accountTail,
                            paidFrom.bank,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A date for a payment that already happened.
 *
 * Future dates are refused: an expense dated tomorrow would sit outside the month's range checks
 * and could not be reconciled against anything. Snapped to midday in the household's zone rather
 * than midnight, so it cannot land in the previous day for a reader east of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualDatePicker(
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = OursZone.today()
    val state = rememberDatePickerState(
        // The picker speaks UTC in both directions, so the initial value has to be UTC midnight
        // of the intended local day — handing it local midnight puts an IST user a day behind.
        initialSelectedDateMillis = OursZone.dateOf(initial)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableYear(year: Int) = year <= today.year
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !java.time.Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = DatePickerDefaults.colors(containerColor = Ours.surfaceContainerHigh),
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis ?: return@TextButton
                    val day = java.time.Instant.ofEpochMilli(picked)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    onPick(OursZone.startOfDay(day) + 12 * 3_600_000L)
                },
            ) { Text("Use this date", color = Ours.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    ) {
        DatePicker(state = state, title = null)
    }
}

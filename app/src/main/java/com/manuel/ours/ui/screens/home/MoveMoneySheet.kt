package com.manuel.ours.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.CASH_ACCOUNT
import com.manuel.ours.domain.model.MoveSide
import com.manuel.ours.domain.model.shortLabel
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.SheetField
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.SheetAmountStyle

/**
 * Money going from one of the household's own places to another.
 *
 * Not an expense, and deliberately not the expense sheet with an extra field on it. A "paid to"
 * box on every row would be blank or meaningless on almost all of them — the payee of a shop
 * purchase is already the merchant column — and a control that is irrelevant nine times in ten
 * is one people stop reading. The cases that genuinely need a destination are all the same case:
 * money that did not leave the household. So it gets its own action, and answering "where did it
 * go" is the whole of what that action is.
 *
 * That also means there is no separate "this was my own payment" switch to set. Using this sheet
 * *is* the statement. A second control saying the same thing as [com.manuel.ours.domain.model.Category.SELF_TRANSFER]
 * would be a second place for the answer to live, free to disagree with the first — which is how
 * every category in this app once ended up with two names that different screens rendered
 * differently.
 *
 * ## The second amount
 *
 * Offered only for a card, and only as a correction. Settling a bill through a rewards app pays
 * part of it in points: ₹468.41 reaches the card while ₹425.41 leaves the bank. Both are true,
 * and each belongs against the account it moved on, so the sheet takes both rather than picking
 * one and being wrong about the other. The gap is left unnamed — see `moveMoney`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoveMoneySheet(
    onDismiss: () -> Unit,
    /**
     * Everywhere the household keeps money — accounts, cards, deposits, whoever owns them.
     *
     * Not partitioned here. A card is a destination like any other and a partner's account is
     * too: the app already treats the partner's balance as household capacity, so money sent
     * there is the same wash as money moved between two of one's own accounts.
     */
    accounts: List<AccountBalance>,
    onConfirm: (
        from: MoveSide,
        to: MoveSide,
        amountOutPaise: Long,
        amountInPaise: Long,
        occurredAt: Long,
        note: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountText by rememberSaveable { mutableStateOf("") }
    var reachedText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var whenPicked by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    // Held as keys rather than as objects: `accounts` is a flow and re-emits new instances
    // whenever any balance moves, which would drop a selection mid-entry.
    var fromKey by rememberSaveable { mutableStateOf<String?>(null) }
    var toKey by rememberSaveable { mutableStateOf<String?>(null) }

    val cash = remember {
        AccountBalance(key = CASH_ACCOUNT, accountTail = null, bank = CASH_ACCOUNT,
            balancePaise = null, asOf = null, source = null)
    }
    // Cash is an end like any other: an ATM withdrawal is money moving from an account to a
    // pocket, and counting it as spending is how a month reads as ₹5,000 worse than it was.
    val options = remember(accounts) { accounts + cash }

    val from = options.firstOrNull { it.key == fromKey }
    val to = options.firstOrNull { it.key == toKey }

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }

    val out = Money.parseToPaise(amountText)
    // Blank means "the same amount arrived", which is the ordinary case and should cost
    // nothing to say. Only a card offers the field at all.
    val reached = if (reachedText.isBlank()) out else Money.parseToPaise(reachedText)
    val valid = out != null && out > 0 && reached != null && reached > 0 &&
        from != null && to != null && from.key != to.key

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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MicroLabel("Move money")

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "₹",
                    style = SheetAmountStyle,
                    color = if (out != null && out > 0) Ours.onSurface else Ours.onSurfaceMuted,
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

            MicroLabel("Out of")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { account ->
                    OursChip(
                        label = account.moveLabel(),
                        selected = account.key == fromKey,
                        icon = account.moveIcon(),
                        // Picking the same place twice is not a move. Rather than refuse it
                        // on Save, taking it as the source clears a destination it collides
                        // with — the tap that was just made is the one the person meant.
                        onClick = {
                            fromKey = account.key
                            if (toKey == account.key) toKey = null
                        },
                    )
                }
            }

            MicroLabel("Into")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { account ->
                    OursChip(
                        label = account.moveLabel(),
                        selected = account.key == toKey,
                        icon = account.moveIcon(),
                        onClick = {
                            toKey = account.key
                            if (fromKey == account.key) fromKey = null
                        },
                    )
                }
            }

            // Only a card, and only ever optional. A bank-to-bank move arrives whole, and
            // asking after a second figure everywhere would imply money routinely goes
            // missing between two accounts, which it does not.
            if (to?.isCard == true) {
                MicroLabel("Reached the card")
                SheetField(
                    value = reachedText,
                    onValueChange = { input ->
                        reachedText = input.filter { it.isDigit() || it == '.' }.take(12)
                    },
                    placeholder = "Same as above",
                    tag = "If points paid part",
                    filledTag = "Edit",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }

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

            SheetField(
                value = note,
                onValueChange = { note = it },
                placeholder = "Add a note",
                tag = "Optional",
                filledTag = "Note",
            )

            // What will actually be written, in the words the statement will use. The two
            // rows this creates are the app's answer to a question nobody can check later,
            // so the sheet says the answer before it is stored rather than after.
            if (from != null && to != null && out != null && out > 0) {
                // Two lines, because one could not hold it. Two account names joined by an
                // arrow is most of a phone's width before the amounts start, and on a real
                // card payment this read "FEDERAL BANK ···4657 → ICICI BANK ···3008 · ₹42…" —
                // truncated exactly where the second figure, the whole point of the field
                // above, would have been.
                MicroLabel("${from.moveLabel()} → ${to.moveLabel()}")
                MicroLabel(
                    buildString {
                        if (reached != null && reached != out) {
                            append("${Money.whole(out)} out, ${Money.whole(reached)} in · ")
                        }
                        append("not spending")
                    },
                )
            }

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
                            from!!.toMoveSide(),
                            to!!.toMoveSide(),
                            out!!,
                            reached!!,
                            whenPicked ?: System.currentTimeMillis(),
                            note.trim(),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * How an account reads on a chip and, afterwards, on the statement line that names both ends.
 *
 * [shortLabel] with the tail is right for a "paid from" chip standing on its own, but two of
 * them joined by an arrow is most of a phone's width before the amount column starts. So a
 * card or an account the household has named is given the shorter of the two forms it has.
 */
private fun AccountBalance.moveLabel(): String =
    bank?.takeIf { it == CASH_ACCOUNT } ?: shortLabel()

// No card glyph in the set, and this is not the place to add one: a card and an account are
// both "somewhere money sits" to this sheet, and the chip's label already says which.
private fun AccountBalance.moveIcon(): Int =
    if (bank == CASH_ACCOUNT) OursIcon.Cash else OursIcon.Bank

private fun AccountBalance.toMoveSide() = MoveSide(
    accountTail = accountTail,
    bank = bank,
    label = moveLabel(),
)

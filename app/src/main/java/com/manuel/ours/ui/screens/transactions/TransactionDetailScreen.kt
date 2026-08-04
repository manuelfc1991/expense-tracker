package com.manuel.ours.ui.screens.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.OursMono
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.ui.theme.WordmarkStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EDGE = 15.dp

/**
 * One entry, opened up.
 *
 * The amount is the hero here for the same reason the month total is the hero on Home:
 * it is the thing you came to check. Everything below it is provenance — which bank,
 * which account, and the original message the parser read — because the only reason to
 * open a single row is to find out whether the app got it right.
 */
@Composable
fun TransactionDetailScreen(
    txnId: String,
    onBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val txn by viewModel.observe(txnId).collectAsStateWithLifecycle(initialValue = null)
    /** Non-null while the rename dialog is open; holds the text being edited. */
    var renaming by remember { mutableStateOf<String?>(null) }
    var editingAmount by remember { mutableStateOf<String?>(null) }
    val canEditAmount by viewModel.canEditAmount.collectAsStateWithLifecycle(initialValue = false)

    editingAmount?.let { draft ->
        AmountDialog(
            initial = draft,
            onDismiss = { editingAmount = null },
            onConfirm = { rupees ->
                Money.parseToPaise(rupees)?.let { viewModel.editAmount(txnId, it) }
                editingAmount = null
            },
        )
    }

    renaming?.let { draft ->
        RenameDialog(
            initial = draft,
            accountTail = txn?.counterpartyTail,
            onDismiss = { renaming = null },
            onConfirm = { name, remember ->
                viewModel.rename(txnId, name, txn?.counterpartyTail, remember)
                renaming = null
            },
        )
    }

    Scaffold(containerColor = Ours.ink) { padding ->
        val current = txn ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                    Text("ENTRY", style = WordmarkStyle, color = Ours.text)
                }
                BiIconView(
                    BiIcon.Delete,
                    contentDescription = "Delete",
                    tint = Ours.negative,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { viewModel.delete(txnId); onBack() },
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = EDGE),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    CategoryAvatar(current.category, size = 34.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Tap to rename. Banks often send no payee at all — a Kerala
                        // Gramin UPI debit names only the destination account — and the
                        // person who made the payment is the only one who can say who
                        // it went to.
                        Text(
                            current.merchant,
                            style = MaterialTheme.typography.titleLarge,
                            color = Ours.text,
                            modifier = Modifier.clickable { renaming = current.merchant },
                        )
                        MicroLabel(
                            Instant.ofEpochMilli(current.occurredAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a"))
                        )
                    }
                }
                // Paise shown here, unlike every list. This is the one place you are
                // reconciling against a bank statement, so the exact figure matters.
                Text(
                    text = Money.format(current.amountPaise, withDecimals = true),
                    style = MaterialTheme.typography.displayMedium,
                    color = Ours.text,
                    maxLines = 1,
                    modifier = if (canEditAmount) {
                        Modifier.clickable {
                            editingAmount =
                                (current.amountPaise / 100.0).toString()
                        }
                    } else Modifier,
                )

                // Says why this row will not reconcile against the statement. The app
                // cannot recover the bank's original figure once it is overwritten, so
                // the least it can do is admit that somebody changed it.
                current.amountEditedAt?.let { at ->
                    MicroLabel(
                        "Amount edited by hand · " + Instant.ofEpochMilli(at)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                        color = Ours.warning,
                    )
                }
            }

            TapeHeader("Category", modifier = Modifier.padding(horizontal = EDGE))
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = EDGE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Category.entries.forEach { option ->
                    OursChip(
                        label = option.label,
                        selected = current.category == option,
                        icon = BiIcon.forCategory(option),
                        onClick = { viewModel.recategorize(txnId, option) },
                    )
                }
            }

            TapeHeader("Counts as", modifier = Modifier.padding(horizontal = EDGE))
            Row(
                Modifier.padding(horizontal = EDGE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OursChip(
                    label = "Household",
                    selected = current.splitType == SplitType.SHARED,
                    onClick = { viewModel.setSplitType(txnId, SplitType.SHARED) },
                )
                OursChip(
                    label = "Personal",
                    selected = current.splitType == SplitType.PERSONAL,
                    onClick = { viewModel.setSplitType(txnId, SplitType.PERSONAL) },
                )
            }

            TapeHeader("Where it came from", modifier = Modifier.padding(horizontal = EDGE))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = EDGE),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                DetailRow("Paid by", current.ownerName)
                current.bank?.let { DetailRow("Source", it) }
                current.accountTail?.let { DetailRow("Account", "•••• $it") }
                current.refNo?.let { DetailRow("Reference", it) }
                DetailRow("Detected from", current.source.name.lowercase())
            }

            // The raw SMS is kept locally so a mis-parse can be diagnosed. It is never
            // synced — this text exists only on the phone that received it.
            current.rawSms?.let { raw ->
                TapeHeader("Original message", modifier = Modifier.padding(horizontal = EDGE))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(Ours.surface)
                            .padding(13.dp)
                    ) {
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = OursMono,
                            color = Ours.textSecondary,
                        )
                    }
                    MicroLabel("Stays on this phone · never synced")
                    GhostButton(
                        label = "Report wrong parse",
                        onClick = { viewModel.flagWrongParse(txnId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MicroLabel(label)
            Text(
                value,
                style = ValueTextStyle.copy(fontWeight = FontWeight.Medium),
                color = Ours.text,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
    }
}

/**
 * Renaming a payee, on the one screen where you are reconciling against a statement.
 *
 * Prefilled and fully selected, because the common case is replacing a placeholder
 * outright rather than editing it — "Unknown payee" has no useful prefix to keep.
 */
@Composable
private fun RenameDialog(
    initial: String,
    accountTail: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    // Default on when the bank named an account. Somebody correcting a placeholder
    // almost always wants it to stick — and without it the same correction is due again
    // next month, because the next payment arrives just as anonymous as this one.
    var rememberAccount by remember(accountTail) { mutableStateOf(accountTail != null) }
    var text by remember {
        mutableStateOf(
            TextFieldValue(initial, selection = TextRange(0, initial.length))
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surface,
        title = { Text("Who was this?", style = MaterialTheme.typography.titleMedium, color = Ours.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.text),
                    cursorBrush = SolidColor(Ours.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))

                if (accountTail == null) {
                    MicroLabel("Only this row — the bank named no account to remember.")
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { rememberAccount = !rememberAccount },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Switch(
                            checked = rememberAccount,
                            onCheckedChange = { rememberAccount = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Remember account ${'$'}accountTail",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ours.text,
                            )
                            MicroLabel("Names every payment to it, past and future")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text, rememberAccount) },
                enabled = text.text.isNotBlank(),
            ) { Text("Save", color = Ours.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.textSecondary) }
        },
    )
}

/**
 * Editing the one field that came straight from the bank.
 *
 * Deliberately plain and deliberately hard to reach: an amount is evidence, not
 * interpretation, and overwriting it is irreversible — the original is not kept
 * anywhere, because keeping a shadow copy would only move the question of which figure
 * to believe.
 */
@Composable
private fun AmountDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surface,
        title = {
            Text("Correct the amount", style = MaterialTheme.typography.titleMedium, color = Ours.text)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.text),
                    cursorBrush = SolidColor(Ours.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
                MicroLabel(
                    "The bank's figure is not kept. This row will be marked as edited.",
                    color = Ours.warning,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text) },
                enabled = text.text.isNotBlank(),
            ) { Text("Save", color = Ours.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.textSecondary) }
        },
    )
}

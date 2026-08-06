package com.manuel.ours.ui.screens.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.manuel.ours.core.OursZone
import com.manuel.ours.core.Money
import com.manuel.ours.domain.Trash
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnType
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.components.NoticeTone
import com.manuel.ours.ui.components.Notice
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.CategoryGrid
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.CategoryAvatar
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.OursMono
import com.manuel.ours.ui.theme.ValueTextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/**
 * One entry, opened up.
 *
 * The amount is the hero here for the same reason the month total is the hero on Home:
 * it is the thing you came to check. Everything below it is provenance — which bank,
 * which account, and the original message the parser read — because the only reason to
 * open a single row is to find out whether the app got it right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionDetailScreen(
    txnId: String,
    onBack: () -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.observe(txnId)
        .collectAsStateWithLifecycle(initialValue = DetailState.Loading)
    val txn = (state as? DetailState.Found)?.txn
    /** Non-null while the rename dialog is open; holds the text being edited. */
    var renaming by remember { mutableStateOf<String?>(null) }
    var editingAmount by remember { mutableStateOf<String?>(null) }
    var editingNote by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingRefund by remember { mutableStateOf(false) }

    editingNote?.let { draft ->
        NoteDialog(
            initial = draft,
            onDismiss = { editingNote = null },
            onConfirm = { text ->
                viewModel.setNote(txnId, text)
                editingNote = null
            },
        )
    }
    val canEditAmount by viewModel.canEditAmount.collectAsStateWithLifecycle(initialValue = false)
    val awaitingApproval by viewModel.deleteAwaitingApproval.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle(initialValue = false)

    // The one destructive button on this screen. A trash icon 15dp square sits a thumb's
    // width from Back, and the row it removes may be a hand-made entry — so it asks
    // first, offers an Undo afterwards, and what slips past both is recoverable from
    // Trash for thirty days. It used to have none of the three: this screen closed on
    // the tap, which was taken as proof that an undo had nowhere to live.
    if (confirmingDelete && txn != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = Ours.surfaceContainer,
            title = {
                Text(
                    if (isOwner) "Delete this entry?" else "Ask to remove this?",
                    color = Ours.onSurface,
                )
            },
            text = {
                Text(
                    // Name the row. A confirmation that does not say what it is about is
                    // answered from memory of which one was tapped, which is the mistake
                    // it exists to catch — and it has to be the same figure the screen
                    // behind it shows. Money.whole read ₹450 off a row printed ₹450.75,
                    // which invites a second look at whether this is the right entry.
                    // Paise everywhere else are wrong; here they are the identifier.
                    // "cannot be brought back" was true when this was written and is now
                    // false twice over — there is an Undo on the way out and thirty days
                    // in Trash. A confirmation that overstates the damage is one people
                    // learn to dismiss unread, which costs exactly the protection it was
                    // added for.
                    if (isOwner) {
                        "${txn.merchant}, ${Money.format(txn.amountPaise, withDecimals = true)}. " +
                            "It goes from both phones, and waits in Trash for " +
                            "${Trash.WINDOW_DAYS} days in case you change your mind."
                    } else {
                        "${txn.merchant}, ${Money.format(txn.amountPaise, withDecimals = true)}. " +
                            "The household owner has to agree, and it still counts until they do."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        // Back only on a delete that happened. A member's becomes a
                        // request, and closing the screen on it left the row sitting in
                        // the list with nothing to explain why.
                        viewModel.delete(txnId)
                    },
                ) { Text(if (isOwner) "Delete" else "Ask", color = Ours.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel", color = Ours.onSurfaceVariant)
                }
            },
        )
    }

    if (pickingRefund) {
        RefundPickerSheet(
            credit = txn,
            viewModel = viewModel,
            onDismiss = { pickingRefund = false },
            onPick = { debitId, paise ->
                viewModel.linkRefund(txnId, debitId, paise)
                pickingRefund = false
            },
        )
    }

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

    // The row is soft-deleted, so it is still here to look at while the offer stands.
    // When the snackbar goes without being pressed, so does the screen.
    val justDeleted by viewModel.justDeleted.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(justDeleted) {
        if (!justDeleted) return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = "Deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete(txnId)
        } else {
            viewModel.clearJustDeleted()
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Ours.surface,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        // A row can vanish while its notification is still in the tray — the other
        // phone deleted it, or an approved delete synced across. Rendering nothing
        // left a blank page with no way to tell a slow load from a missing row.
        if (state is DetailState.Missing) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OursTopBar(title = "Entry", onBack = onBack)
                // A row can vanish while its notification is still in the tray — the other
                // phone deleted it, or an approved delete synced across. Rendering nothing left
                // a blank page with no way to tell a slow load from a missing row.
                EmptyState(
                    title = "This entry is no longer here",
                    body = "It was removed on the other phone, or an approved delete has synced " +
                        "across.",
                    icon = OursIcon.NoResults,
                    action = { GhostButton("Back to Activity", onClick = onBack) },
                )
            }
            return@Scaffold
        }
        val current = txn ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OursTopBar(title = "Entry", onBack = onBack) {
                // Was a 15dp glyph a thumb's width from Back, on the one control here that
                // destroys a row — and the row may be a hand-made entry that exists in no
                // backup. A 48dp target at the far end of the bar, in the error colour.
                OursIconButton(
                    icon = OursIcon.Delete,
                    contentDescription = "Delete this entry",
                    onClick = { confirmingDelete = true },
                    tint = Ours.error,
                )
            }

            if (awaitingApproval) {
                MicroLabel(
                    "Asked the household owner to remove this — it still counts until they agree",
                    color = Ours.warning,
                    modifier = Modifier.padding(horizontal = Space.edge),
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.edge),
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
                            color = Ours.onSurface,
                            modifier = Modifier.clickable { renaming = current.merchant },
                        )
                        MicroLabel(
                            // Date alone when the bank gave no clock time. This is the screen
                            // people reconcile against a bank statement, so an invented
                            // "12:00 am" is worse here than anywhere else in the app.
                            OursZone.format(
                                current.occurredAt,
                                if (OursZone.isDateOnly(current.occurredAt)) OursZone.date
                                else OursZone.dateTime,
                            )
                        )
                    }
                }
                // Paise shown here, unlike every list. This is the one place you are
                // reconciling against a bank statement, so the exact figure matters.
                Text(
                    text = Money.format(current.amountPaise, withDecimals = true),
                    style = MaterialTheme.typography.displayMedium,
                    color = Ours.onSurface,
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
                        "Amount edited by hand · " + OursZone.format(at, OursZone.date),
                        color = Ours.warning,
                    )
                }
            }

            // A credit that might be money coming back rather than money earned.
            //
            // Every credit that is not a maturing investment becomes Income, so a ₹2,000 return
            // leaves the ledger holding a ₹2,000 debit *and* a ₹2,000 credit: net worth right,
            // spending overstated by ₹2,000, and the budget charged for a purchase that was
            // undone. Only a person can say which credits are refunds — matching on amount is the
            // trap this exists to avoid — so it is asked, once, and only where it can apply.
            if (current.type == TxnType.CREDIT) {
                val purchase by viewModel.refundedPurchase(current.refundsTxnId)
                    .collectAsStateWithLifecycle(initialValue = null)

                if (current.refundsTxnId == null) {
                    Notice(
                        tone = NoticeTone.Info,
                        title = "Is this money coming back?",
                        body = "Credits count as income. If this is a refund for something you " +
                            "bought, say so and it will cancel that purchase instead of " +
                            "inflating what you earned.",
                        modifier = Modifier.padding(horizontal = Space.edge),
                        action = {
                            GhostButton(
                                label = "This is a refund",
                                onClick = { pickingRefund = true },
                            )
                        },
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                        verticalArrangement = Arrangement.spacedBy(Space.s2),
                    ) {
                        TapeHeader("Cancels")
                        purchase?.let { debit ->
                            TransactionEntry(
                                txn = debit,
                                divider = false,
                                dimAmount = true,
                                captionOverride = buildString {
                                    append(OursZone.format(debit.occurredAt, OursZone.day))
                                    append(" · ")
                                    append(
                                        if (debit.refundedPaise >= debit.amountPaise) {
                                            "fully refunded"
                                        } else {
                                            "partly refunded"
                                        }
                                    )
                                },
                                onClick = { /* already the pair; opening it would loop */ },
                            )
                        }
                        MicroLabel(
                            "Refund · not counted as income",
                        )
                        GhostButton(
                            label = "Unlink",
                            onClick = { viewModel.unlinkRefund(txnId) },
                        )
                    }
                }
            }

            TapeHeader("Category", modifier = Modifier.padding(horizontal = Space.edge))
            CategoryGrid(
                selected = current.category,
                onSelect = { viewModel.recategorize(txnId, it) },
                modifier = Modifier.padding(horizontal = Space.edge),
            )

            TapeHeader(
                "Note",
                trailing = if (current.note.isNullOrBlank()) null else "Edit",
                modifier = Modifier
                    .padding(horizontal = Space.edge)
                    .clickable { editingNote = current.note.orEmpty() },
            )
            // The only field holding something the bank could never have sent, and the
            // only defence against a row that made sense in August and is a mystery by
            // December. Never read by the app — it is for the household, not the
            // categoriser.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.edge)
                    .clickable { editingNote = current.note.orEmpty() }
            ) {
                if (current.note.isNullOrBlank()) {
                    MicroLabel("Add a note — optional")
                } else {
                    Text(
                        current.note!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ours.onSurface,
                    )
                }
            }

            TapeHeader("Counts as", modifier = Modifier.padding(horizontal = Space.edge))
            Row(
                Modifier.padding(horizontal = Space.edge),
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

            TapeHeader("Where it came from", modifier = Modifier.padding(horizontal = Space.edge))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.edge),
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
                TapeHeader("Original message", modifier = Modifier.padding(horizontal = Space.edge))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(Ours.surfaceContainer)
                            .padding(13.dp)
                    ) {
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = OursMono,
                            color = Ours.onSurfaceVariant,
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
                color = Ours.onSurface,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
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
        containerColor = Ours.surfaceContainer,
        title = { Text("Who was this?", style = MaterialTheme.typography.titleMedium, color = Ours.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.onSurface),
                    cursorBrush = SolidColor(Ours.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))

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
                                color = Ours.onSurface,
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
            ) { Text("Save", color = Ours.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
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
        containerColor = Ours.surfaceContainer,
        title = {
            Text("Correct the amount", style = MaterialTheme.typography.titleMedium, color = Ours.onSurface)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.onSurface),
                    cursorBrush = SolidColor(Ours.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
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
            ) { Text("Save", color = Ours.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    )
}

/**
 * A sentence about why, for the one row that will not explain itself later.
 *
 * Multi-line and unpunished for being left empty: clearing the text removes the note
 * rather than storing a blank, so the row goes back to offering the dim invitation
 * instead of showing an empty box.
 */
@Composable
private fun NoteDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surfaceContainer,
        title = { Text("Note", style = MaterialTheme.typography.titleMedium, color = Ours.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.onSurface),
                    cursorBrush = SolidColor(Ours.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
                MicroLabel("Only you and your household see this")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.text) }) {
                Text("Save", color = Ours.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    )
}

/**
 * Which purchase a refund cancels.
 *
 * Same-amount candidates are offered first and labelled, but **nothing is preselected on amount
 * alone**: two ₹2,000 movements in a month are far more often two real payments than a purchase
 * and its refund, and this household has already been bitten by a matcher that was too eager —
 * two ₹10,000 movements a minute apart, an FD maturing and rent paid to a person, both real. Only
 * an exact amount *and* a matching payee earns a highlight, and even then it is a recommendation.
 *
 * The whole 60 days stay reachable, because a partial refund will not match on amount at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefundPickerSheet(
    credit: com.manuel.ours.domain.model.Transaction?,
    viewModel: TransactionDetailViewModel,
    onDismiss: () -> Unit,
    onPick: (debitId: String, paise: Long) -> Unit,
) {
    if (credit == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val candidates by viewModel.refundCandidates(credit)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var chosen by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.edge).padding(bottom = Space.s8),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            MicroLabel(
                "Refund of ${Money.whole(credit.amountPaise)} · " +
                    OursZone.format(credit.occurredAt, OursZone.day)
            )
            Text(
                "What did this cancel?",
                style = MaterialTheme.typography.titleMedium,
                color = Ours.onSurface,
            )

            if (candidates.isEmpty()) {
                EmptyState(
                    title = "No purchases to cancel",
                    body = "Nothing in the last 60 days is still unrefunded, so there is nothing " +
                        "for this credit to undo.",
                    icon = OursIcon.NoResults,
                )
            } else {
                candidates.take(12).forEach { candidate ->
                    val selected = chosen == candidate.txn.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (selected) {
                                    Ours.primaryFixed.copy(alpha = 0.14f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { chosen = candidate.txn.id }
                            .padding(horizontal = Space.s2),
                    ) {
                        TransactionEntry(
                            txn = candidate.txn,
                            divider = false,
                            captionOverride = buildString {
                                append(OursZone.format(candidate.txn.occurredAt, OursZone.day))
                                if (candidate.recommended) append(" · match")
                                else if (candidate.exactAmount) append(" · same amount")
                            },
                            captionColorOverride = if (candidate.recommended) Ours.primary else null,
                        )
                    }
                }
                Text(
                    "A partial refund is fine — the purchase keeps whatever it is not cancelling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Box(Modifier.weight(1f)) { GhostButton("Cancel", onClick = onDismiss) }
                    Box(Modifier.weight(1f)) {
                        AccentButton(
                            label = "Link them",
                            enabled = chosen != null,
                            dimWhenDisabled = true,
                            onClick = {
                                val debit = candidates.first { it.txn.id == chosen }.txn
                                // Capped at the purchase: a refund larger than the thing it
                                // refunds is a mis-link, not a windfall.
                                onPick(debit.id, minOf(credit.amountPaise, debit.amountPaise))
                            },
                        )
                    }
                }
            }
        }
    }
}

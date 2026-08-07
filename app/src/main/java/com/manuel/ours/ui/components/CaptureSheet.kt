package com.manuel.ours.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.SheetAmountStyle

/**
 * The payment you just made, while you still remember what it was for.
 *
 * The heads-up notification already offers three one-tap guesses and stays put when the
 * app is closed, which is most of the time. What it cannot do is rename a payee or take
 * a note, and it is gone in seconds. This is the same moment given room, and it appears
 * **only when the app is already open** — a sheet that seized focus mid-payment would
 * be worse than no prompt at all.
 *
 * What it asks depends on what the bank left out. A named merchant needs only a
 * category; an unnamed debit leads with the question nobody else can answer, and offers
 * the destination account, because naming that once names every payment to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    txn: Transaction,
    suggestions: List<Category>,
    onDismiss: () -> Unit,
    onCategorize: (Category) -> Unit,
    onRename: (name: String, rememberAccount: Boolean) -> Unit,
    onNote: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ours.surfaceContainer,
    ) {
        CaptureSheetContent(txn, suggestions, onDismiss, onCategorize, onRename, onNote)
    }
}

/**
 * The body of the capture prompt, with no window of its own.
 *
 * Split out because the same content is shown two ways: as a bottom sheet inside the app,
 * and as a standalone popup over whatever you were doing when the app is closed. Those
 * need different containers — one is a ModalBottomSheet, the other is an activity with a
 * transparent window — but they must not become different prompts.
 */
@Composable
fun CaptureSheetContent(
    txn: Transaction,
    suggestions: List<Category>,
    onDismiss: () -> Unit,
    onCategorize: (Category) -> Unit,
    onRename: (name: String, rememberAccount: Boolean) -> Unit,
    onNote: (String) -> Unit,
) {
    var showAllCategories by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var noting by remember { mutableStateOf<String?>(null) }

    val unnamed = txn.merchant.equals(UNKNOWN_PAYEE, ignoreCase = true)

    renaming?.let { draft ->
        CaptureTextDialog(
            title = "Who was this?",
            initial = draft,
            selectAll = true,
            hint = txn.counterpartyTail?.let { "Remember account $it" },
            onDismiss = { renaming = null },
            onConfirm = { text, remember ->
                onRename(text, remember)
                renaming = null
            },
        )
    }

    noting?.let { draft ->
        CaptureTextDialog(
            title = "Note",
            initial = draft,
            selectAll = false,
            hint = null,
            onDismiss = { noting = null },
            onConfirm = { text, _ ->
                onNote(text)
                noting = null
            },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            // Same reason as the add sheet: imePadding shrinks the viewport, and without
            // a scroll the buttons at the bottom go under the keyboard unreachable.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 15.dp)
            .padding(bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CategoryAvatar(txn.category, size = 34.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    txn.merchant,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Ours.onSurface,
                )
                MicroLabel(listOfNotNull(txn.bank, "just now").joinToString(" · "))
            }
        }

        Text(
            Money.whole(txn.amountPaise),
            style = SheetAmountStyle,
            color = Ours.onSurface,
            maxLines = 1,
        )

        // Only asked when the bank left it unanswered. A named merchant has nothing
        // to correct, and drawing the question anyway would make every payment look
        // like it needed attention.
        if (unnamed) {
            MicroLabel("Who was this?")
            SheetTapRow(
                text = txn.counterpartyTail
                    ?.let { "Tap to name — account ····$it" }
                    ?: "Tap to name",
                tag = "Name",
                onClick = { renaming = txn.merchant },
            )
        }

        MicroLabel("Category")
        if (showAllCategories) {
            CategoryGrid(selected = txn.category, onSelect = onCategorize)
        } else {
            // The guesses first, because they are right most of the time and this
            // sheet exists to be dismissed in one tap. The grid is one tap further
            // for when they are not.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { option ->
                    OursChip(
                        label = option.label,
                        selected = txn.category == option,
                        icon = OursIcon.forCategory(option),
                        onClick = { onCategorize(option) },
                    )
                }
                OursChip(
                    label = "All ›",
                    selected = false,
                    onClick = { showAllCategories = true },
                )
            }
        }

        SheetTapRow(
            text = txn.note?.takeIf { it.isNotBlank() } ?: "Add a note",
            tag = if (txn.note.isNullOrBlank()) "Optional" else "Note",
            filled = !txn.note.isNullOrBlank(),
            onClick = { noting = txn.note.orEmpty() },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                // Dismissing marks nothing. The row stays in Sort, which is where
                // an undecided payment belongs.
                GhostButton(label = "Later", onClick = onDismiss)
            }
            Box(Modifier.weight(1f)) {
                AccentButton(label = "Save", onClick = onDismiss)
            }
        }
    }
}

private const val UNKNOWN_PAYEE = "Unknown payee"

/**
 * One text field, optionally with a switch under it.
 *
 * Shared by the two questions the capture sheet asks, because they differ only in what
 * they are called and whether the answer is worth remembering beyond this row.
 */
@Composable
private fun CaptureTextDialog(
    title: String,
    initial: String,
    selectAll: Boolean,
    hint: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initial,
                selection = if (selectAll) {
                    androidx.compose.ui.text.TextRange(0, initial.length)
                } else {
                    androidx.compose.ui.text.TextRange(initial.length)
                },
            )
        )
    }
    var rememberIt by remember(hint) { mutableStateOf(hint != null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surfaceContainer,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = Ours.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = selectAll,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ours.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ours.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .padding(vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
                if (hint != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { rememberIt = !rememberIt },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.Switch(
                            checked = rememberIt,
                            onCheckedChange = { rememberIt = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                hint,
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
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(text.text, rememberIt) },
                enabled = text.text.isNotBlank() || hint == null,
            ) { Text("Save", color = Ours.primary) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = Ours.onSurfaceVariant)
            }
        },
    )
}

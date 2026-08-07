package com.manuel.ours.ui.screens.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.manuel.ours.core.OursZone
import com.manuel.ours.core.Money
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.LocalDate
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.model.CategoryTotal
import com.manuel.ours.domain.model.MoneyFlow
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.export.ExportManager
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.SummarySkeleton
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.Meter
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.StatementEntry
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.MonthTitleStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.colorForCategory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale


/**
 * The month, told as a net figure rather than a spend figure.
 *
 * Spending alone cannot tell you whether the month went well — ₹21,979 spent is good
 * news on ₹38,240 earned and bad news on ₹18,000. So the headline is what actually
 * changed, and spend is demoted to one of three supporting numbers.
 */
@Composable
fun SummaryScreen(viewModel: SummaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var settingBalance by rememberSaveable { mutableStateOf<AccountBalance?>(null) }
    var addingAccount by rememberSaveable { mutableStateOf(false) }
    var tab by remember { mutableStateOf(SummaryTab.Month) }
    val context = LocalContext.current
    val summary = state.summary

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OursIconButton(
                    icon = OursIcon.PreviousMonth,
                    contentDescription = "Previous month",
                    onClick = { viewModel.previousMonth() },
                    tint = Ours.onSurfaceVariant,
                    glyph = 20.dp,
                )
                Text(
                    text = state.yearMonth.format(OursZone.month).uppercase(),
                    style = MonthTitleStyle,
                    color = Ours.onSurface,
                    modifier = Modifier.padding(horizontal = Space.s2),
                )
                OursIconButton(
                    icon = OursIcon.NextMonth,
                    contentDescription = "Next month",
                    onClick = { viewModel.nextMonth() },
                    tint = Ours.onSurfaceVariant,
                    glyph = 20.dp,
                )
            }
        }

        if (state.loading || summary == null) {
            item { SummarySkeleton(Modifier.padding(horizontal = Space.edge, vertical = Space.s4)) }
        } else if (
            summary.totalSpentPaise == 0L &&
            summary.totalReceivedPaise == 0L &&
            tab == SummaryTab.Month
        ) {
            // Only the Month tab is empty, and only the Month tab is replaced.
            //
            // This branch used to swallow the whole body including the tab bar, so a quiet
            // month — or a fresh install — hid "What is left", every recorded balance and
            // the way to add an account. Balances are explicitly *not* month-scoped, so
            // there was nothing empty about them.
            item { SummaryTabs(selected = tab, onSelect = { tab = it }) }
            item {
                EmptyState(
                    title = "Nothing in ${state.yearMonth.format(OursZone.month)}",
                    body = "Nothing was earned or spent in this month, or tracking starts after it.",
                    icon = OursIcon.Inbox,
                )
            }
        } else {
            // Three questions, three tabs.
            //
            // This was one continuous scroll holding a retrospective ("where did it go"), a
            // per-account register ("what is actually there") and a forward-looking commitments
            // list ("what is already spoken for"). Three different questions, and reaching the
            // third meant scrolling past both of the others every time.
            item {
                SummaryTabs(selected = tab, onSelect = { tab = it })
            }

            if (tab == SummaryTab.Month) {
            item { NetHeadline(summary) }
            item { EarnedSpentSaved(summary) }
            item { LeftAccounts(state.leftAccountsPaise, summary) }

            if (summary.byCategory.isNotEmpty()) {
                item { WhereItWent(summary) }
            }

            if (summary.excluded.isNotEmpty()) {
                item { NotCounted(summary) }
            }
            }

            // The budget and the balances reconciled, directly above the per-account
            // detail that backs the capacity half of it.
            if (tab == SummaryTab.Accounts) state.affordability?.let { afford ->
                item {
                    SafeToSpend(
                        state = afford,
                        savedPaise = summary.totalSavedPaise,
                        movedPaise = (
                            state.leftAccountsPaise -
                                summary.totalSpentPaise -
                                summary.totalSavedPaise
                            ).coerceAtLeast(0L),
                    )
                }
            }

            // Everyone sees their own accounts; the owner sees all of them. The list
            // itself is filtered, rather than the panel being hidden — a partner has to
            // be able to record their own balances even if the household's full position
            // is not theirs to read.
            if (tab == SummaryTab.Accounts) {
                item {
                    WhatsLeft(
                        balances = state.balances,
                        onSet = { settingBalance = it },
                        onAdd = { addingAccount = true },
                    )
                }
            }

            if (tab == SummaryTab.Committed) {
                if (state.recurring.isNotEmpty()) {
                    item { Committed(state.recurring, state.committedMonthlyPaise) }
                } else {
                    item {
                        EmptyState(
                            title = "Nothing repeating yet",
                            body = "A charge is called recurring after three sightings — the " +
                                "fewest that can be told from a coincidence. Rent and bills " +
                                "appear here once they have happened three times.",
                            icon = OursIcon.Sync,
                        )
                    }
                }
            }

            if (tab == SummaryTab.Month) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    GhostButton(
                        label = "Export CSV",
                        onClick = {
                            ExportManager.shareCsv(context, state.transactions, state.yearMonth)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        label = "Export PDF",
                        onClick = {
                            ExportManager.sharePdf(
                                context, summary, state.transactions, state.yearMonth,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            }
        }
    }

    if (addingAccount) {
        AddAccountDialog(
            onDismiss = { addingAccount = false },
            onConfirm = { key, bank, balance, minimum, isCard, limit ->
                // Always written, even with no figure: the entry is what records that
                // the account exists and who added it, which is what keeps it on their
                // own screen as "tap to set" rather than only on the owner's. A null
                // balance writes exactly that — the account, with nothing claimed about
                // what is in it.
                viewModel.setAccountBalance(key, balance, bank)
                minimum?.let { viewModel.setAccountMinimum(key, it) }
                if (isCard) viewModel.setCard(key, limit, null)
                addingAccount = false
            },
        )
    }

    settingBalance?.let { account ->
        BalanceDialog(
            account = account,
            onDismiss = { settingBalance = null },
            onConfirm = { edit, minimum ->
                when (edit) {
                    // Untouched: not the same as cleared. Saving only a minimum must not
                    // wipe a balance the person never went near.
                    null -> Unit
                    is BalanceEdit.Forget ->
                        viewModel.setAccountBalance(account.key, null, account.bank)
                    is BalanceEdit.Set ->
                        viewModel.setAccountBalance(account.key, edit.paise, account.bank)
                }
                minimum?.let { viewModel.setAccountMinimum(account.key, it) }
                settingBalance = null
            },
        )
    }
}

/**
 * What the balance field is asking for, which "a nullable Long" cannot say.
 *
 * Three answers are possible and all three are different: leave it alone (null), forget
 * the typed figure and go back to the bank's ([Forget]), or record this number — zero
 * included ([Set]). Collapsing the last two onto 0L is what made a zero-balance account
 * impossible to enter.
 */
private sealed interface BalanceEdit {
    data object Forget : BalanceEdit
    data class Set(val paise: Long) : BalanceEdit
}

@Composable
private fun NetHeadline(summary: MonthSummary, modifier: Modifier = Modifier) {
    val net = summary.netPaise
    // Green only when you actually kept something. A negative month rendered in the
    // positive colour would be the app telling you a comfortable lie.
    val color = if (net >= 0) Ours.success else Ours.error
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel("Net this month")
        Text(
            // Whole rupees. Paise on a headline this size is noise.
            text = (if (net > 0) "+" else "") + Money.exact(net),
            style = MaterialTheme.typography.displayMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun EarnedSpentSaved(summary: MonthSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
        HairlineRule()
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelOverValue("Earned", Money.exact(summary.totalReceivedPaise))
            LabelOverValue(
                label = "Spent",
                value = Money.exact(summary.totalSpentPaise),
                alignment = Alignment.CenterHorizontally,
            )
            LabelOverValue(
                label = "Saved",
                value = Money.exact(summary.totalSavedPaise),
                valueColor = if (summary.totalSavedPaise > 0) Ours.success else Ours.onSurface,
                alignment = Alignment.End,
            )
        }
    }
}

/**
 * Every rupee that left the accounts, next to the spending it is not.
 *
 * "Spent" answers what the household consumed, and deliberately leaves out savings and
 * money moved between our own accounts — fold those in and a month of hard saving reads
 * as a month of overspending, and money sent from one of our accounts to another gets
 * counted as spending it never was, then counted again when it is actually spent.
 *
 * But "how much left my account" is a real question with a different answer, and the
 * gap between the two figures is exactly the savings and the transfers. Showing both,
 * with the difference named underneath, is how you get one number without lying about
 * the other.
 */
@Composable
private fun LeftAccounts(
    leftPaise: Long,
    summary: MonthSummary,
    modifier: Modifier = Modifier,
) {
    val moved = leftPaise - summary.totalSpentPaise - summary.totalSavedPaise
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HairlineRule()
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            MicroLabel("Left our accounts")
            AmountColumn(leftPaise)
        }
        Text(
            buildString {
                append("Spending")
                if (summary.totalSavedPaise > 0) append(", savings")
                if (moved > 0) append(", and money moved between our accounts")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceVariant,
        )
    }
}

/**
 * Type in what an account holds, for the banks that never say.
 *
 * Deliberately spare: one figure, no name field. The account already has a name — the
 * bank's — and the only thing missing is the number.
 */
/**
 * Record an account before any payment has touched it.
 *
 * The dialog that edits a balance can only edit one that already exists, and an account
 * exists only once something references it — so a person with no rows of their own had
 * an empty panel and no way to fill it. This is the way in.
 *
 * The last four digits are optional but worth asking for: they are what lets this entry
 * merge with the real thing later, when a bank message finally names the account. Give
 * only a bank name and the two stay separate until somebody tidies up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        key: String,
        bank: String,
        balance: Long?,
        minimum: Long?,
        isCard: Boolean,
        limit: Long?,
    ) -> Unit,
) {
    var bank by rememberSaveable { mutableStateOf("") }
    var tail by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    var minimum by rememberSaveable { mutableStateOf("") }
    // Asked first and in words, because it decides which total the account joins — money
    // held or money owed — and that is not a detail a form field can carry.
    var isCard by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf("") }

    val cleanBank = bank.trim()
    val cleanTail = tail.filter(Char::isDigit).takeLast(4)
    val ready = cleanBank.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surfaceContainer,
        title = {
            Text("Add an account", style = MaterialTheme.typography.titleMedium, color = Ours.onSurface)
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MicroLabel("What kind")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OursChip(
                        label = "Bank account",
                        selected = !isCard,
                        icon = OursIcon.Bank,
                        onClick = { isCard = false },
                    )
                    OursChip(
                        label = "Credit card",
                        selected = isCard,
                        icon = OursIcon.CreditCard,
                        onClick = { isCard = true },
                    )
                }
                MicroLabel(if (isCard) "Card" else "Bank")
                PlainField(
                    bank,
                    if (isCard) "Utkarsh SuperCard" else "Kerala Gramin Bank",
                ) { bank = it }
                MicroLabel("Last four digits — optional")
                PlainField(tail, if (isCard) "8842" else "3062") {
                    tail = it.filter(Char::isDigit).take(4)
                }
                MicroLabel(if (isCard) "Owed now" else "Balance now")
                MoneyField(balance) { balance = it }
                if (isCard) {
                    MicroLabel("Credit limit — optional")
                    MoneyField(limit) { limit = it }
                } else {
                    MicroLabel("Minimum balance")
                    MoneyField(minimum) { minimum = it }
                }
                Text(
                    if (isCard) {
                        "Owed is kept apart from what is left — it is money already spent. " +
                            "If this card's purchases reach the app by SMS, paying its bill " +
                            "will not be counted as spending a second time."
                    } else {
                        "The balance is yours, not the bank's — a real one from a message " +
                            "replaces it once this account starts sending them."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onConfirm(
                        cleanTail.ifEmpty { cleanBank },
                        cleanBank,
                        Money.parseToPaise(balance),
                        // A card has no minimum to hold; it has a limit, which is a
                        // different thing and is stored on the card rule instead.
                        if (isCard) null else Money.parseToPaise(minimum),
                        isCard,
                        Money.parseToPaise(limit),
                    )
                },
            ) { Text("Add", color = if (ready) Ours.primary else Ours.onSurfaceMuted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    )
}

/** A plain text line for a dialog, with the app's hairline underneath rather than a box. */
@Composable
private fun PlainField(value: String, hint: String, onChange: (String) -> Unit) {
    Column {
        Box {
            if (value.isEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ours.onSurfaceMuted,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current
                    .merge(MaterialTheme.typography.bodyLarge)
                    .copy(color = Ours.onSurface),
                cursorBrush = SolidColor(Ours.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HairlineRule()
    }
}

/** One rupee field, sized for a dialog rather than a hero. */
@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("₹", style = MaterialTheme.typography.headlineSmall, color = Ours.onSurfaceMuted)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(12)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.headlineSmall)
                .copy(color = Ours.onSurface),
            cursorBrush = SolidColor(Ours.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceDialog(
    account: AccountBalance,
    onDismiss: () -> Unit,
    onConfirm: (balance: BalanceEdit?, minimum: Long?) -> Unit,
) {
    // The figure as it opened, so an untouched field is not re-saved as though somebody
    // typed it. Saving a minimum used to overwrite a bank-quoted balance with an
    // identical hand-entered one, silently downgrading "the bank said" to "you said".
    val opening = remember { account.balancePaise?.let { (it / 100).toString() }.orEmpty() }
    var text by remember { mutableStateOf(opening) }
    var minText by remember {
        mutableStateOf(account.minimumPaise.takeIf { it > 0 }?.let { (it / 100).toString() }.orEmpty())
    }
    val paise = Money.parseToPaise(text)
    val minPaise = Money.parseToPaise(minText)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ours.surfaceContainer,
        title = {
            Text(
                account.bank ?: "This account",
                style = MaterialTheme.typography.titleMedium,
                color = Ours.onSurface,
            )
        },
        text = {
            // Scrollable, and deliberately compact. A Material dialog caps the height of
            // this slot and clips rather than scrolls, so an earlier, wordier version of
            // this simply lost its second field off the bottom with no sign it was there.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MicroLabel("Balance now")
                MoneyField(text) { text = it }
                MicroLabel("Minimum balance")
                MoneyField(minText) { minText = it }
                Text(
                    "A balance you type is yours, not the bank's — a real one from a " +
                        "message replaces it, and emptying the field hands it back. A " +
                        "zero is a figure, not an empty field: type 0 for an account " +
                        "that really is empty. The minimum is what must stay in the " +
                        "account, and comes off the figure on the summary — 0 for a " +
                        "zero-balance account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Either field alone is a complete answer: a bank-quoted balance needs only
            // a floor, and a zero-balance account needs only a figure.
            val touched = text != opening || minPaise != null
            TextButton(
                enabled = touched,
                onClick = {
                    onConfirm(
                        when {
                            // Never went near the field — leave whatever is stored.
                            text == opening -> null
                            // Emptied on purpose: drop the typed figure, let the bank's
                            // last word stand again.
                            text.isBlank() -> BalanceEdit.Forget
                            // Anything that parses, zero included. An unparseable string
                            // is not a figure, so it is treated as no answer rather than
                            // silently saved as one.
                            else -> paise?.let { BalanceEdit.Set(it) }
                        },
                        minPaise,
                    )
                },
            ) { Text("Save", color = if (touched) Ours.primary else Ours.onSurfaceMuted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
        },
    )
}

/**
 * The budget and the bank, in one figure.
 *
 * This is the panel that answers a question the app used to duck. A ₹40,000 budget sat
 * on Home and a "What is left" total sat here, both of them saying some version of
 * *left*, neither aware the other existed — so "how much can I spend" had two answers
 * and no way to choose between them.
 *
 * They are not rival estimates. The budget is **permission** and the balance is
 * **capacity**, and you can only spend the smaller. Which one that is gets said in
 * words, because the number alone does not tell you what to do about it: a budget that
 * runs past the balance means the plan was optimistic, while a balance that runs past
 * the budget means there is money there you have decided not to spend. Opposite
 * situations, identical-looking figure.
 *
 * The gap between them is spelled out too. That gap is the whole reason the two numbers
 * were confusing: money put into savings, or shifted between our own accounts, leaves
 * the balance and never touches the budget — so the two drift apart during any month
 * where the household does either, which for this household is every month.
 */
@Composable
private fun SafeToSpend(
    state: Affordability,
    savedPaise: Long,
    movedPaise: Long,
    modifier: Modifier = Modifier,
) {
    val safe = state.safeToSpendPaise ?: return
    val budgetLeft = state.budgetLeftPaise
    val usable = state.usablePaise

    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TapeHeader("Safe to spend", trailing = Money.whole(safe))

        // Which constraint is doing the work, in a sentence. Named rather than implied:
        // the figure is the same either way and the response to it is not.
        Text(
            text = when {
                state.overBudget && usable != null ->
                    "You are ${Money.whole(-(budgetLeft ?: 0L))} over budget. " +
                        "The accounts still hold ${Money.whole(usable)}, so this is a " +
                        "decision rather than a wall."
                state.overBudget ->
                    "You are ${Money.whole(-(budgetLeft ?: 0L))} over budget."
                state.limit == Affordability.Limit.BALANCE && budgetLeft != null ->
                    "Your balance is the limit. The budget would allow " +
                        "${Money.whole(state.gapPaise ?: 0L)} more than the accounts hold."
                state.limit == Affordability.Limit.BUDGET && usable != null ->
                    "Your budget is the limit. The accounts hold " +
                        "${Money.whole(state.gapPaise ?: 0L)} more than it allows."
                state.limit == Affordability.Limit.BUDGET ->
                    "What the budget still allows. No account balance is recorded yet, " +
                        "so nothing is checking it against real money."
                else ->
                    "What the accounts hold, less what must stay in them. " +
                        "No budget is set, so nothing is capping it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.overBudget) Ours.error else Ours.onSurfaceVariant,
        )

        HairlineRule()

        // The arithmetic, so the figure above is checkable rather than magic.
        if (budgetLeft != null) {
            LedgerLine(
                label = "Budget left",
                value = Money.whole(budgetLeft),
                dim = state.limit != Affordability.Limit.BUDGET,
                negative = budgetLeft < 0,
            )
        }
        if (usable != null) {
            LedgerLine(
                label = "In the accounts",
                value = Money.whole(usable),
                dim = state.limit != Affordability.Limit.BALANCE,
            )
        }
        if (state.committedPaise > 0) {
            LedgerLine(
                label = "Still due this month",
                value = "−${Money.whole(state.committedPaise)}",
                dim = state.limit != Affordability.Limit.BALANCE,
            )
        }

        // Why the two sides disagree, said only when they actually do. Without this the
        // household is left to work out for itself why the money fell further than the
        // budget did — and the answer is money that is still theirs.
        if (savedPaise > 0 || movedPaise > 0) {
            Text(
                buildString {
                    append("The budget counts spending only. ")
                    if (savedPaise > 0) append("${Money.whole(savedPaise)} put aside")
                    if (savedPaise > 0 && movedPaise > 0) append(" and ")
                    if (movedPaise > 0) append("${Money.whole(movedPaise)} moved between our accounts")
                    append(" left the accounts this month without touching it.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceMuted,
            )
        }

        if (state.unknownAccounts > 0) {
            Text(
                if (state.unknownAccounts == 1) {
                    "One account has no balance recorded, so the real figure is higher."
                } else {
                    "${state.unknownAccounts} accounts have no balance recorded, " +
                        "so the real figure is higher."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ours.warning,
            )
        }

        // A partner sees their own accounts and no one else's, so their capacity figure
        // is a floor. Saying so is the difference between a partial view and a wrong one.
        if (state.partialView) {
            Text(
                "Counting your accounts only.",
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceMuted,
            )
        }
    }
}

/** One line of the reckoning: what it is on the left, what it is worth on the right. */
@Composable
private fun LedgerLine(
    label: String,
    value: String,
    dim: Boolean,
    negative: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            // The binding constraint is the one that matters; the other is context.
            color = if (dim) Ours.onSurfaceVariant else Ours.onSurface,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (dim) FontWeight.Normal else FontWeight.SemiBold,
            color = when {
                negative -> Ours.error
                dim -> Ours.onSurfaceVariant
                else -> Ours.onSurface
            },
        )
    }
}

/**
 * What is actually in each account, according to the bank — or to whoever typed it.
 *
 * The only figures on this screen that are not arithmetic on transactions. Everything
 * else here is derived; these are quoted, and the row says by whom. That distinction is
 * the whole design: a bank's figure corrects itself when the next message arrives, and a
 * typed one sits there looking equally certain while the real balance moves underneath
 * it. Marked as **you said**, and outranked automatically the moment the bank quotes a
 * newer number for that account.
 *
 * Accounts with no balance at all are still listed. Kerala Gramin quotes one on a credit
 * and omits it on a transfer, so the account is in the ledger and its figure is not —
 * showing it blank is what makes it obvious it can be filled in.
 */
@Composable
private fun WhatsLeft(
    balances: List<AccountBalance>,
    onSet: (AccountBalance) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { OursZone.today() }
    // Two blocks, never one total.
    //
    // A card balance is money **owed**. This panel's figure is what `Affordability`
    // spends against, so folding card debt into it would report more to spend than
    // exists — the opposite of the truth. They are different quantities and they get
    // different words: "left" is capacity, "owed" is a bill already run up.
    val (cards, accounts) = balances.partition { it.isCard }
    val usable = accounts.mapNotNull { it.usablePaise }.sum()
    val owed = cards.mapNotNull { it.balancePaise }.sum()
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TapeHeader("What is left", trailing = Money.whole(usable))
        accounts.forEach { account ->
            Row(
                Modifier.fillMaxWidth().clickable { onSet(account) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        account.bank ?: "Account",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ours.onSurface,
                    )
                    val age = account.asOf?.let { at ->
                        when (
                            val days = ChronoUnit.DAYS.between(OursZone.dateOf(at), today)
                        ) {
                            0L -> "today"
                            1L -> "yesterday"
                            else -> "$days days ago"
                        }
                    }
                    MicroLabel(
                        buildString {
                            account.accountTail?.let { append("···· $it") }
                            if (age != null) {
                                if (isNotEmpty()) append(" · ")
                                if (account.source == BalanceSource.HAND) append("you said, ")
                                append(age)
                            } else {
                                if (isNotEmpty()) append(" · ")
                                append("tap to set")
                            }
                        },
                        // Amber for a figure nobody's bank stands behind.
                        color = if (account.source == BalanceSource.HAND) Ours.warning
                        else Ours.onSurfaceMuted,
                    )
                    // The held floor, spelled out, because the figure on the right is
                    // the balance minus this and that difference is otherwise invisible.
                    if (account.minimumPaise > 0 && account.balancePaise != null) {
                        MicroLabel(
                            "of ${Money.whole(account.balancePaise)} · " +
                                "${Money.whole(account.minimumPaise)} must stay",
                        )
                    }
                }
                if (account.usablePaise != null) {
                    AmountColumn(account.usablePaise!!)
                } else {
                    MicroLabel("—")
                }
            }
        }

        if (cards.isNotEmpty()) {
            TapeHeader("Owed on cards", trailing = Money.whole(owed))
            cards.forEach { card ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSet(card) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            card.bank ?: "Card",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ours.onSurface,
                        )
                        MicroLabel(
                            buildString {
                                card.accountTail?.let { append("···· $it") }
                                if (card.balancePaise == null) {
                                    if (isNotEmpty()) append(" · ")
                                    append("tap to set")
                                }
                            },
                        )
                        // How much room is left, which is the figure a card is actually
                        // used against. Only when both halves are known — a limit with no
                        // outstanding, or the reverse, cannot say anything true.
                        val limit = card.limitPaise
                        val outstanding = card.balancePaise
                        if (limit != null && outstanding != null) {
                            MicroLabel(
                                "${Money.whole((limit - outstanding).coerceAtLeast(0L))} " +
                                    "of ${Money.whole(limit)} still free",
                            )
                        }
                    }
                    if (card.balancePaise != null) {
                        AmountColumn(card.balancePaise!!)
                    } else {
                        MicroLabel("—")
                    }
                }
            }
            Text(
                "Owed is not subtracted from what is left. It is money you have already " +
                    "spent and not yet paid.",
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceVariant,
            )
        }

        if (balances.isEmpty()) {
            Text(
                "Nothing here yet. An account appears on its own once a payment goes " +
                    "through it — or you can put one in now.",
                style = MaterialTheme.typography.bodySmall,
                color = Ours.onSurfaceVariant,
            )
        }

        // The way in for an account no payment has touched yet. Without it a person
        // with no rows of their own sees an empty panel and no means of filling it.
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onAdd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Add an account",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.primary,
            )
            Text("+", style = MaterialTheme.typography.headlineSmall, color = Ours.primary)
        }
    }
}

/**
 * Ranked bars, not a donut.
 *
 * A donut is only readable when its slices are of comparable size, which household
 * spending never is — one category at 95% is a circle with a notch in it. Ranked bars
 * stay readable at any split, and they put the name and the amount on the same line as
 * the length, which is also what keeps the colour from being load-bearing.
 */
@Composable
private fun WhereItWent(summary: MonthSummary, modifier: Modifier = Modifier) {
    // Every category, not the top six.
    //
    // It used to take(6), silently. On a month with seven categories that dropped
    // Groceries — ₹292 of a ₹28,663 month — with nothing on screen to say a row was
    // missing, so the bars added up to less than the Spent figure directly above them
    // and the section looked simply wrong. "Where it went" is a claim to account for
    // the money; a truncated list either has to say what it left out or not leave
    // anything out. There are sixteen categories and a month rarely touches half of
    // them, so showing all of them costs a few compact rows and makes the section
    // checkable against the total.
    //
    // Rows are whole rupees while the total is exact, so the sum can still sit a rupee
    // or two under the headline. That is rounding, not a missing row.
    val entries = summary.byCategory
    // The month total, not the biggest category. See [RankedBar].
    val total = summary.totalSpentPaise.takeIf { it > 0 } ?: 1L
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // A rule under it, as in the mockup: this is a section head like "Today" on
        // Home, not a caption over a value. Without the rule the ranked bars float
        // free of anything and the eye has no line to start from.
        TapeHeader("Where it went", trailing = Money.exact(summary.totalSpentPaise))
        entries.forEach { entry -> RankedBar(entry, total) }
    }
}

@Composable
private fun RankedBar(entry: CategoryTotal, totalSpentPaise: Long) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = entry.category.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            AmountColumn(entry.totalPaise)
        }
        // A share of the month, not a share of the biggest category.
        //
        // Scaling against the largest category made its bar full-width by definition,
        // so the top row always read "all of it" no matter what it actually was —
        // ₹3,233 of a ₹3,233 month and ₹8,412 of a ₹22,000 one drew the identical bar.
        // The question this section asks is "where did the month go", and only the
        // share of the total answers it. The mockup does the same: its four bars are
        // 38/27/16/10%, which is exactly each category over ₹21,979.
        Meter(
            fraction = entry.totalPaise.toFloat() / totalSpentPaise,
            color = colorForCategory(entry.category),
        )
    }
}

/**
 * Debits deliberately left out of the headline, shown rather than hidden.
 *
 * A card bill pays for purchases that are already in the list above, so counting both
 * would show them twice — on real data that alone inflated one month by ₹7,325. The
 * dashed border says "these are real, they are just not spending".
 */
@Composable
private fun NotCounted(summary: MonthSummary, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge)
            .dashedBorder(Ours.outlineVariant, 13.dp)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "Not counted",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ours.onSurface,
            )
            AmountColumn(summary.excludedPaise)
        }
        summary.excluded.forEach { entry ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OursIconView(
                    OursIcon.forCategory(entry.category),
                    contentDescription = null,
                    tint = Ours.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    entry.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AmountColumn(entry.totalPaise, dim = true)
            }
        }
        // Said about what is actually in the box.
        //
        // This was one hard-coded sentence about credit-card bills, written when card
        // bills were excluded. They count as spending now — a household whose card
        // purchases never reach the app has to count the bill or lose them entirely —
        // so the box holds savings and self-transfers, and the caption was explaining a
        // rule the app had stopped following.
        Text(
            buildString {
                if (summary.excluded.any { it.category.flow == MoneyFlow.SAVING }) {
                    append("Money put aside is still yours, so it is not spending. ")
                }
                if (summary.excluded.any { it.category.flow == MoneyFlow.NEUTRAL }) {
                    append("Money moved between our own accounts never left the household. ")
                }
                append("Both still come off your balance.")
            }.trim(),
            style = MaterialTheme.typography.bodySmall,
            color = Ours.onSurfaceVariant,
        )
    }
}

@Composable
private fun HairlineRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
}

/** Dashed outline. Compose has no dashed-border modifier, so it is drawn. */
private fun Modifier.dashedBorder(color: Color, radius: Dp) = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f),
        ),
    )
}

/**
 * Money already spoken for — the charges that will arrive again whether or not anyone
 * decides anything this month.
 *
 * It sits below the month's retrospective because it is the only forward-looking thing
 * on the screen: everything above answers "where did it go", this answers "what is
 * already committed". The header total reconciles cadences, so a quarterly charge and a
 * monthly one can be read against each other rather than added as written.
 *
 * Nothing here is declared by a bank — it is all inferred from repetition, so the count
 * of sightings is shown. Three is the fewest that can be told from a coincidence, and a
 * reader deserves to know when a claim rests on exactly three.
 */
@Composable
private fun Committed(
    charges: List<RecurringCharge>,
    monthlyTotalPaise: Long,
    modifier: Modifier = Modifier,
) {
    val dateFormat = OursZone.day

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // "Committed", not "Every month" — the rows carry mixed cadences, so a header
        // reading "every month" above a row reading "every week" makes the reader do
        // arithmetic to work out whether the total is monthly. The unit goes on the
        // total instead, where it is unambiguous.
        TapeHeader(
            "Committed",
            trailing = "${Money.whole(monthlyTotalPaise)} a month",
            modifier = Modifier.padding(horizontal = Space.edge),
        )
        charges.forEachIndexed { index, charge ->
            val next = remember(charge.nextExpectedAt) {
                Instant.ofEpochMilli(charge.nextExpectedAt)
                    .atZone(MonthlyAggregator.ZONE).toLocalDate().format(dateFormat)
            }
            StatementEntry(
                title = charge.merchant,
                caption = "${charge.cadence.label} · next $next · seen ${charge.occurrences}×",
                paise = charge.typicalPaise,
                category = charge.category,
                divider = index != charges.lastIndex,
                // The entry does not inset itself — the caller owns the edge, as on
                // Home. Without this the avatar sits on the screen border and the
                // amount is clipped.
                modifier = Modifier.padding(horizontal = Space.edge),
            )
        }
    }
}

/** The three questions this screen answers, which are not the same question. */
private enum class SummaryTab(val label: String) {
    Month("Month"),
    Accounts("Accounts"),
    Committed("Committed"),
}

/**
 * Primary tabs, the one place in this app that has them.
 *
 * A tab is right here and nowhere else: the three panes are peers over the same month, none is a
 * step in a job, and each is a screenful on its own. Anywhere else in this app that would be a
 * navigation destination.
 */
@Composable
private fun SummaryTabs(selected: SummaryTab, onSelect: (SummaryTab) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            SummaryTab.entries.forEach { entry ->
                val active = entry == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(entry) }
                        .height(Space.target),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (active) Ours.primary else Ours.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.s2),
                    )
                    // The indicator, drawn as a box rather than a border so the inactive tabs
                    // keep exactly the same height and nothing shifts as you switch.
                    Box(
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .background(if (active) Ours.primary else Color.Transparent)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
    }
}

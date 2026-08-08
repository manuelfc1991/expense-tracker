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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.time.LocalDate
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.Member
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.model.Category
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
    // The account's key, not the account.
    //
    // `AccountBalance` is a plain data class and cannot go in a Bundle, so holding one
    // here crashed the app the moment this state was saved — the same defect that took
    // the add sheet down. A key is a String, and it is the better thing to hold anyway:
    // the balance is a live figure, and a copy captured when the dialog opened would go
    // on showing the old number after a message from the bank corrected it underneath.
    var settingBalanceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var addingAccount by rememberSaveable { mutableStateOf(false) }
    var removingAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(SummaryTab.Month) }
    val context = LocalContext.current
    val summary = state.summary

    // Swipe sideways to change tab.
    //
    // A horizontal drag on a vertically scrolling list, rather than a pager: the month
    // header, the tab row and all three tabs' content are items in one LazyColumn, and
    // splitting that into three scrollable pages would mean three copies of the header or
    // a nested-scroll arrangement to keep one. The gesture is the part that was missing,
    // not the layout.
    //
    // Orientation.Horizontal does not fight the list's vertical scroll — Compose routes
    // each axis separately, so a diagonal drag still scrolls rather than jumping tabs.
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val swipeTabs = Modifier.draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta -> dragged += delta },
        onDragStarted = { dragged = 0f },
        onDragStopped = {
            val entries = SummaryTab.entries
            val here = entries.indexOf(tab)
            // Dragging left moves forward, the way a page turns. Clamped at both ends:
            // running off the last tab must do nothing rather than wrap, since wrapping
            // makes "which tab am I on" a question you have to look up.
            when {
                dragged <= -swipeThreshold && here < entries.lastIndex -> tab = entries[here + 1]
                dragged >= swipeThreshold && here > 0 -> tab = entries[here - 1]
            }
            dragged = 0f
        },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().then(swipeTabs),
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
                        selfUid = state.selfUid,
                        onSet = { settingBalanceKey = it.key },
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
            members = state.members,
            onDismiss = { addingAccount = false },
            onConfirm = { key, bank, balance, minimum, kind, limit, dueDay, owner ->
                // Always written, even with no figure: the entry is what records that
                // the account exists and who added it, which is what keeps it on their
                // own screen as "tap to set" rather than only on the owner's. A null
                // balance writes exactly that — the account, with nothing claimed about
                // what is in it.
                viewModel.setAccountBalance(key, balance, bank)
                minimum?.let { viewModel.setAccountMinimum(key, it) }
                when (kind) {
                    AccountKind.Card -> viewModel.setCard(key, limit, dueDay)
                    AccountKind.Savings -> viewModel.setSavings(key, true)
                    AccountKind.Bank -> Unit
                }
                owner?.let { viewModel.setAccountOwner(key, it.uid, it.displayName) }
                addingAccount = false
            },
        )
    }

    // Resolved fresh on every recomposition, so the dialog follows the live balance. A key
    // whose account has disappeared closes the dialog rather than showing a stale one.
    removingAccount?.let { key ->
        val going = state.balances.firstOrNull { it.key == key }
        AlertDialog(
            onDismissRequest = { removingAccount = null },
            containerColor = Ours.surfaceContainer,
            title = {
                Text(
                    "Remove ${going?.bank ?: "this account"}?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ours.onSurface,
                )
            },
            text = {
                Text(
                    "It goes from both phones, along with its balance, its minimum and " +
                        "anything recorded about whose it is. No transaction is deleted — " +
                        "this only removes an account nothing has been paid from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeAccount(key)
                        removingAccount = null
                        settingBalanceKey = null
                    },
                ) { Text("Remove", color = Ours.error) }
            },
            dismissButton = {
                TextButton(onClick = { removingAccount = null }) {
                    Text("Keep", color = Ours.onSurfaceVariant)
                }
            },
        )
    }

    settingBalanceKey?.let { key -> state.balances.firstOrNull { it.key == key } }?.let { account ->
        BalanceDialog(
            account = account,
            members = state.members,
            onDismiss = { settingBalanceKey = null },
            onRemove = { removingAccount = account.key },
            onConfirm = { edit, minimum, owner, dueDay, kind, limitPaise ->
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
                owner?.let { viewModel.setAccountOwner(account.key, it.uid, it.displayName) }
                // The limit is carried through untouched: setCard writes both halves of
                // the rule, so passing only the day would quietly erase the credit limit.
                // The card rule carries both halves, so this always writes both — and
                // clearing it is what moves the account back to "What is left".
                //
                // The minimum balance is deliberately left stored rather than wiped when
                // an account becomes a card. A card ignores it, so it does no harm there,
                // and switching back restores what the household had recorded instead of
                // silently losing it.
                //
                // Both rules are cleared on the way out, not just the one being left. The
                // kinds are three states of one thing, but they are stored as two
                // independent rules, so a card turned into money put aside would otherwise
                // still be a card as well — and the panel partitions on `isCard` first, so
                // the account would silently stay under "Owed on cards" while the household
                // had said the opposite.
                if (kind != AccountKind.Card && account.isCard) viewModel.removeCard(account.key)
                if (kind != AccountKind.Savings && account.isSavings) {
                    viewModel.setSavings(account.key, false)
                }
                when (kind) {
                    AccountKind.Card -> viewModel.setCard(account.key, limitPaise, dueDay)
                    AccountKind.Savings -> viewModel.setSavings(account.key, true)
                    AccountKind.Bank -> Unit
                }
                settingBalanceKey = null
            },
        )
    }
}

/**
 * What an account *is*, which decides which total its balance joins.
 *
 * One value rather than two booleans. `isCard` and `isSavings` as separate flags admit a
 * state where both are true, and nothing downstream would know which total to believe —
 * the panel partitions on one then the other, so it would silently pick by order.
 *
 * An enum, so `rememberSaveable` can hold it: enums are Serializable, which is the whole
 * difference between this and the sealed `PaidFrom` that crashed the add sheet.
 */
private enum class AccountKind { Bank, Card, Savings }

/** What kind the app currently has this account down as. */
private fun AccountBalance.kind(): AccountKind = when {
    isCard -> AccountKind.Card
    isSavings -> AccountKind.Savings
    else -> AccountKind.Bank
}

/** The three-way kind chooser, identical in the add and edit dialogs. */
@Composable
private fun KindChooser(kind: AccountKind, onPick: (AccountKind) -> Unit) {
    MicroLabel("What kind")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OursChip(
            label = "Bank account",
            selected = kind == AccountKind.Bank,
            icon = OursIcon.Bank,
            onClick = { onPick(AccountKind.Bank) },
        )
        OursChip(
            label = "Credit card",
            selected = kind == AccountKind.Card,
            icon = OursIcon.CreditCard,
            onClick = { onPick(AccountKind.Card) },
        )
        OursChip(
            label = "Put aside",
            selected = kind == AccountKind.Savings,
            icon = OursIcon.forCategory(Category.INVESTMENTS),
            onClick = { onPick(AccountKind.Savings) },
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
/**
 * A change of owner, as distinct from no change at all.
 *
 * `null` from the dialog means untouched; an `OwnerEdit` with a null [uid] means the
 * household has actively handed the account back to Shared. Collapsing the two onto a
 * bare `String?` would make "I did not look at this" indistinguishable from "this is
 * nobody's in particular", and only one of those should write a tombstone.
 */
private data class OwnerEdit(val uid: String?, val displayName: String?)

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
    members: List<Member>,
    onDismiss: () -> Unit,
    onConfirm: (
        key: String,
        bank: String,
        balance: Long?,
        minimum: Long?,
        kind: AccountKind,
        limit: Long?,
        dueDay: Int?,
        owner: Member?,
    ) -> Unit,
) {
    var bank by rememberSaveable { mutableStateOf("") }
    var tail by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    var minimum by rememberSaveable { mutableStateOf("") }
    // Asked first and in words, because it decides which total the account joins — money
    // available, money held or money owed — and that is not a detail a form field can
    // carry.
    var kind by rememberSaveable { mutableStateOf(AccountKind.Bank) }
    val isCard = kind == AccountKind.Card
    var limit by rememberSaveable { mutableStateOf("") }
    // The day of the month the bill falls due. Stored since cards existed and, until
    // now, impossible to fill in: every call site passed null, so the field was written,
    // synced and never populated. It is what lets the app warn about a card whose bank
    // sends no reminder message of its own.
    var dueDay by rememberSaveable { mutableStateOf("") }
    // Unclaimed until said otherwise. An account added by hand is the one case where the
    // app could most plausibly guess — you are holding the phone — and guessing here is
    // how a joint account ends up filed as one person's.
    var owner by rememberSaveable { mutableStateOf<String?>(null) }

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
                KindChooser(kind) { kind = it }
                MicroLabel(
                    when (kind) {
                        AccountKind.Card -> "Card"
                        AccountKind.Savings -> "Where it is"
                        AccountKind.Bank -> "Bank"
                    },
                )
                // Generic placeholders, not this household's own bank and account.
                //
                // These read as examples but they are hints in an empty field, and a hint
                // that names a real account is one keystroke from being accepted as an
                // answer. They also rot: the last-four hint here said 8842 while the card
                // it was describing is ···2020. A placeholder should say what the field
                // wants, not what somebody else put in it.
                PlainField(
                    bank,
                    when (kind) {
                        AccountKind.Card -> "Card name"
                        AccountKind.Savings -> "Bank or fund name"
                        AccountKind.Bank -> "Bank name"
                    },
                ) { bank = it }
                MicroLabel("Last four digits — optional")
                PlainField(tail, "Last 4 digits") {
                    tail = it.filter(Char::isDigit).take(4)
                }
                MicroLabel(
                    when (kind) {
                        AccountKind.Card -> "Owed now"
                        AccountKind.Savings -> "Amount put aside"
                        AccountKind.Bank -> "Balance now"
                    },
                )
                MoneyField(balance) { balance = it }
                when (kind) {
                    AccountKind.Card -> {
                        MicroLabel("Credit limit — optional")
                        MoneyField(limit) { limit = it }
                        MicroLabel("Bill due on — day of the month, optional")
                        PlainField(dueDay, "Day of the month") {
                            dueDay = it.filter(Char::isDigit).take(2)
                        }
                    }
                    // Money put aside has no floor to hold: the whole figure is untouchable
                    // by definition, so a minimum under it would be saying the same thing
                    // twice and inviting a second, contradictory number.
                    AccountKind.Savings -> Unit
                    AccountKind.Bank -> {
                        MicroLabel("Minimum balance")
                        MoneyField(minimum) { minimum = it }
                    }
                }
                OwnerPicker(members, owner) { owner = it?.uid }
                Text(
                    when (kind) {
                        AccountKind.Card ->
                            "Owed is kept apart from what is left — it is money already " +
                                "spent. If this card's purchases reach the app by SMS, " +
                                "paying its bill will not be counted as spending a second " +
                                "time."
                        AccountKind.Savings ->
                            "Money put aside is shown but kept out of what is left — you " +
                                "own it, and it is not there to spend this month."
                        AccountKind.Bank ->
                            "The balance is yours, not the bank's — a real one from a " +
                                "message replaces it once this account starts sending them."
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
                        // different thing and is stored on the card rule instead. Money put
                        // aside has none either — all of it is already held back.
                        if (kind == AccountKind.Bank) Money.parseToPaise(minimum) else null,
                        kind,
                        Money.parseToPaise(limit),
                        // Only a real day of the month. A typo like 45 is no answer, and
                        // storing it would schedule a reminder that never comes round.
                        dueDay.toIntOrNull()?.takeIf { it in 1..31 },
                        members.firstOrNull { it.uid == owner },
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

/**
 * "1st", "2nd", "23rd" — a day of the month as somebody would say it.
 *
 * The teens are the whole reason this is a function: 11, 12 and 13 take "th" despite
 * ending in 1, 2 and 3, and every naive version of this gets "11st" wrong.
 */
private fun ordinalDay(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

/**
 * Whose account this is — recorded, never inferred.
 *
 * Shared is a first-class answer and is offered as one, rather than being what you get
 * for skipping the question. A joint current account genuinely belongs to the household,
 * and an account that has simply not been sorted out yet is honestly described the same
 * way; both are better than the app filing it under whoever happened to pay last.
 *
 * Hidden entirely in a one-person household, where the question has no second answer.
 */
@Composable
private fun OwnerPicker(
    members: List<Member>,
    selected: String?,
    onSelect: (Member?) -> Unit,
) {
    if (members.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MicroLabel("Whose account")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            members.forEach { member ->
                OursChip(
                    label = member.displayName.ifBlank { "Unnamed" },
                    selected = selected == member.uid,
                    onClick = { onSelect(member) },
                )
            }
            OursChip(
                label = "Shared",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceDialog(
    account: AccountBalance,
    members: List<Member>,
    onDismiss: () -> Unit,
    onConfirm: (
        balance: BalanceEdit?,
        minimum: Long?,
        owner: OwnerEdit?,
        dueDay: Int?,
        kind: AccountKind,
        limitPaise: Long?,
    ) -> Unit,
    onRemove: () -> Unit,
) {
    // Only written when it actually changes. Re-saving the same owner on every visit
    // would bump the rule's `updatedAt` and let this phone win a last-write-wins race it
    // had nothing new to say in — quietly undoing a correction made on the other phone.
    val openingOwner = remember { account.ownerUid }
    var owner by remember { mutableStateOf(account.ownerUid) }
    // Editable here as well as at the point a card is added, so a card recorded before
    // due dates existed is not stuck without one. Every field on this screen that could
    // only be set at creation has eventually turned out to need changing.
    val openingDueDay = remember { account.dueDay?.toString().orEmpty() }
    var dueDay by remember { mutableStateOf(openingDueDay) }
    // What kind of thing this is, changeable after the fact.
    //
    // The chooser existed only when adding, so an account the bank messaged about first —
    // which is every account the parser discovers — was a bank account for good. That is
    // not a labelling detail: a card balance is money **owed**, and while it sits in "What
    // is left" the app counts a debt as spendable capacity, with the sign inverted.
    val openingKind = remember { account.kind() }
    var kind by remember { mutableStateOf(openingKind) }
    val isCard = kind == AccountKind.Card
    val openingLimit = remember { account.limitPaise?.let { (it / 100).toString() }.orEmpty() }
    var limitText by remember { mutableStateOf(openingLimit) }
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
                KindChooser(kind) { kind = it }
                MicroLabel(
                    when (kind) {
                        AccountKind.Card -> "Owed now"
                        AccountKind.Savings -> "Amount put aside"
                        AccountKind.Bank -> "Balance now"
                    },
                )
                MoneyField(text) { text = it }
                when (kind) {
                    AccountKind.Card -> {
                        MicroLabel("Credit limit — optional")
                        MoneyField(limitText) { limitText = it }
                        MicroLabel("Bill due on — day of the month")
                        PlainField(dueDay, "Day of the month") {
                            dueDay = it.filter(Char::isDigit).take(2)
                        }
                    }
                    AccountKind.Savings -> Unit
                    AccountKind.Bank -> {
                        MicroLabel("Minimum balance")
                        MoneyField(minText) { minText = it }
                    }
                }
                OwnerPicker(members, owner) { owner = it?.uid }
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
            val touched = text != opening || minPaise != null || owner != openingOwner ||
                dueDay != openingDueDay || kind != openingKind || limitText != openingLimit
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
                        if (owner == openingOwner) null else {
                            OwnerEdit(owner, members.firstOrNull { it.uid == owner }?.displayName)
                        },
                        dueDay.toIntOrNull()?.takeIf { it in 1..31 },
                        kind,
                        Money.parseToPaise(limitText),
                    )
                },
            ) { Text("Save", color = if (touched) Ours.primary else Ours.onSurfaceMuted) }
        },
        dismissButton = {
            // Removal sits with Cancel rather than beside Save, because it is the other
            // way of leaving this dialog without recording a figure.
            //
            // Offered only for an account the ledger does not reference. A payment out of
            // ···3062 is evidence the account exists, and `accountBalances()` rebuilds it
            // from the transactions on every read — so "removing" it would hide it for a
            // moment and leave money attributed to an account the screen denies.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!account.fromLedger) {
                    TextButton(onClick = onRemove) { Text("Remove", color = Ours.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.onSurfaceVariant) }
            }
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
    /** This phone's member, so their accounts head the list. */
    selfUid: String,
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
    val (cards, notCards) = balances.partition { it.isCard }
    // Three kinds, three totals, because they are three different quantities: money you
    // can spend, money you hold but cannot, and money you owe. Folding any pair together
    // gets a sign or an availability wrong.
    val (putAside, accounts) = notCards.partition { it.isSavings }
    val usable = accounts.mapNotNull { it.usablePaise }.sum()
    val owed = cards.mapNotNull { it.balancePaise }.sum()
    val aside = putAside.mapNotNull { it.balancePaise }.sum()

    // Whose money is where — one household total, then a sub-total per person.
    //
    // The grouping is presentation and nothing more. `usable` above is still summed over
    // every account, because the budget is one cap over one household: a per-person
    // sub-total that started behaving like a per-person budget would break the agreement
    // Home, Budgets, the widget and BudgetAlerter all have to keep.
    //
    // Grouped on the owner the household **recorded**, never on who last paid from the
    // account. Anything unclaimed goes to Shared rather than to a guess, for the same
    // reason an unknown balance is never counted as zero.
    val groups = accounts
        .groupBy { it.ownerUid }
        .entries
        .sortedWith(
            // Yours first — it is the list you are looking for — then everyone else by
            // name, then Shared, which is a leftover and reads as one at the bottom.
            compareBy(
                { it.key == null },
                { if (it.key == selfUid) 0 else 1 },
                { it.value.firstOrNull()?.ownerName.orEmpty().lowercase() },
            )
        )
    // Until somebody claims an account there is only one group, and heading it "Shared"
    // would add a word that explains nothing. Headings appear when they mean something.
    val grouped = groups.size > 1 || groups.any { it.key != null }

    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TapeHeader("What is left", trailing = Money.whole(usable))
        groups.forEach { (ownerUid, group) ->
        if (grouped) {
            TapeHeader(
                label = when {
                    ownerUid == null -> "Shared"
                    ownerUid == selfUid -> group.firstOrNull()?.ownerName?.takeIf(String::isNotBlank) ?: "You"
                    else -> group.firstOrNull()?.ownerName?.takeIf(String::isNotBlank) ?: "Someone else"
                },
                trailing = Money.whole(group.mapNotNull { it.usablePaise }.sum()),
                rule = false,
            )
        }
        group.forEach { account ->
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
                    // Said out loud, because the figure on the right is no longer the
                    // figure anybody typed. Kerala Gramin quotes a balance on some
                    // messages and omits it on a UPI transfer, so a hand-typed figure can
                    // sit there for days while payments the app *did* see go unapplied.
                    if (account.movedSincePaise != 0L) {
                        val out = account.movedSincePaise < 0
                        MicroLabel(
                            (if (out) "less " else "plus ") +
                                Money.whole(kotlin.math.abs(account.movedSincePaise)) +
                                " seen since",
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
        }

        if (putAside.isNotEmpty()) {
            TapeHeader("Put aside", trailing = Money.whole(aside))
            putAside.forEach { account ->
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
                            account.bank ?: "Savings",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Ours.onSurface,
                        )
                        MicroLabel(
                            buildString {
                                account.accountTail?.let { append("···· $it") }
                                if (isNotEmpty()) append(" · ")
                                // Said plainly, because the whole point of this block is
                                // that the figure is real and is not part of the total
                                // above it.
                                append("not counted as spendable")
                            },
                        )
                    }
                    if (account.balancePaise != null) {
                        AmountColumn(account.balancePaise!!)
                    } else {
                        MicroLabel("—")
                    }
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
                                // The date the app will warn about, said where the card
                                // is. A due day nobody can see is a due day nobody
                                // trusts is set.
                                card.dueDay?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("due ").append(ordinalDay(it))
                                }
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

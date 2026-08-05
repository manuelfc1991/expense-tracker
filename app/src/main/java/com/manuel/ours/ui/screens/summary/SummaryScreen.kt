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
import com.manuel.ours.core.Money
import com.manuel.ours.ui.theme.SheetAmountStyle
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
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.model.CategoryTotal
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.export.ExportManager
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.Meter
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.StatementEntry
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.theme.MonthTitleStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.colorForCategory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EDGE = 15.dp

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
    var settingBalance by remember { mutableStateOf<AccountBalance?>(null) }
    val context = LocalContext.current
    val summary = state.summary

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BiIconView(
                    BiIcon.PreviousMonth,
                    contentDescription = "Previous month",
                    tint = Ours.textSecondary,
                    modifier = Modifier.size(18.dp).clickable { viewModel.previousMonth() },
                )
                Text(
                    text = state.yearMonth
                        .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                        .uppercase(),
                    style = MonthTitleStyle,
                    color = Ours.text,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
                BiIconView(
                    BiIcon.NextMonth,
                    contentDescription = "Next month",
                    tint = Ours.textSecondary,
                    modifier = Modifier.size(18.dp).clickable { viewModel.nextMonth() },
                )
            }
        }

        if (state.loading || summary == null) {
            item { QuietEmpty("Working out the month", modifier = Modifier.padding(top = 32.dp)) }
        } else if (summary.totalSpentPaise == 0L && summary.totalReceivedPaise == 0L) {
            item { QuietEmpty("Nothing yet this month", modifier = Modifier.padding(top = 32.dp)) }
        } else {
            item { NetHeadline(summary) }
            item { EarnedSpentSaved(summary) }
            item { LeftAccounts(state.leftAccountsPaise, summary) }

            if (summary.byCategory.isNotEmpty()) {
                item { WhereItWent(summary) }
            }

            if (summary.excluded.isNotEmpty()) {
                item { NotCounted(summary) }
            }

            if (state.balances.isNotEmpty()) {
                item { WhatsLeft(state.balances, onSet = { settingBalance = it }) }
            }

            if (state.recurring.isNotEmpty()) {
                item { Committed(state.recurring, state.committedMonthlyPaise) }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
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

    settingBalance?.let { account ->
        BalanceDialog(
            account = account,
            onDismiss = { settingBalance = null },
            onConfirm = { paise, minimum ->
                paise?.let { viewModel.setAccountBalance(account.key, it, account.bank) }
                minimum?.let { viewModel.setAccountMinimum(account.key, it) }
                settingBalance = null
            },
        )
    }
}

@Composable
private fun NetHeadline(summary: MonthSummary, modifier: Modifier = Modifier) {
    val net = summary.netPaise
    // Green only when you actually kept something. A negative month rendered in the
    // positive colour would be the app telling you a comfortable lie.
    val color = if (net >= 0) Ours.positive else Ours.negative
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        MicroLabel("Net this month")
        Text(
            // Whole rupees. Paise on a headline this size is noise.
            text = (if (net > 0) "+" else "") + Money.whole(net),
            style = MaterialTheme.typography.displayMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun EarnedSpentSaved(summary: MonthSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = EDGE)) {
        HairlineRule()
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelOverValue("Earned", Money.whole(summary.totalReceivedPaise))
            LabelOverValue(
                label = "Spent",
                value = Money.whole(summary.totalSpentPaise),
                alignment = Alignment.CenterHorizontally,
            )
            LabelOverValue(
                label = "Saved",
                value = Money.whole(summary.totalSavedPaise),
                valueColor = if (summary.totalSavedPaise > 0) Ours.positive else Ours.text,
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
        modifier.fillMaxWidth().padding(horizontal = EDGE),
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
            color = Ours.textSecondary,
        )
    }
}

/**
 * Type in what an account holds, for the banks that never say.
 *
 * Deliberately spare: one figure, no name field. The account already has a name — the
 * bank's — and the only thing missing is the number.
 */
/** One rupee field, sized for a dialog rather than a hero. */
@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("₹", style = MaterialTheme.typography.headlineSmall, color = Ours.textLabel)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(12)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current
                .merge(MaterialTheme.typography.headlineSmall)
                .copy(color = Ours.text),
            cursorBrush = SolidColor(Ours.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceDialog(
    account: AccountBalance,
    onDismiss: () -> Unit,
    onConfirm: (balance: Long?, minimum: Long?) -> Unit,
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
        containerColor = Ours.surface,
        title = {
            Text(
                account.bank ?: "This account",
                style = MaterialTheme.typography.titleMedium,
                color = Ours.text,
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
                        "message replaces it, and clearing the field hands it back. The " +
                        "minimum is what must stay in the account, and comes off the " +
                        "figure on the summary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.textSecondary,
                )
            }
        },
        confirmButton = {
            // Either field alone is a complete answer: a bank-quoted balance needs only
            // a floor, and a zero-balance account needs only a figure.
            val touched = text != opening || minPaise != null
            TextButton(
                enabled = touched,
                // null balance means "leave it alone"; blank means "forget what I said
                // and go back to the bank's figure".
                onClick = {
                    onConfirm(
                        if (text == opening) null else if (text.isBlank()) 0L else paise,
                        minPaise,
                    )
                },
            ) { Text("Save", color = if (touched) Ours.accent else Ours.textLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ours.textSecondary) }
        },
    )
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
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val usable = balances.mapNotNull { it.usablePaise }.sum()
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TapeHeader("What is left", trailing = Money.whole(usable))
        balances.forEach { account ->
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
                        color = Ours.text,
                    )
                    val age = account.asOf?.let { at ->
                        when (
                            val days = ChronoUnit.DAYS.between(
                                Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                                    .toLocalDate(),
                                today,
                            )
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
                        else Ours.textLabel,
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
    val top = summary.byCategory.take(6)
    // The month total, not the biggest category. See [RankedBar].
    val total = summary.totalSpentPaise.takeIf { it > 0 } ?: 1L
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // A rule under it, as in the mockup: this is a section head like "Today" on
        // Home, not a caption over a value. Without the rule the ranked bars float
        // free of anything and the eye has no line to start from.
        TapeHeader("Where it went")
        top.forEach { entry -> RankedBar(entry, total) }
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
                color = Ours.text,
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
            .padding(horizontal = EDGE)
            .dashedBorder(Ours.hairline, 13.dp)
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
                color = Ours.text,
            )
            AmountColumn(summary.excludedPaise)
        }
        summary.excluded.forEach { entry ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                BiIconView(
                    BiIcon.forCategory(entry.category),
                    contentDescription = null,
                    tint = Ours.textSecondary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    entry.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ours.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                AmountColumn(entry.totalPaise, dim = true)
            }
        }
        Text(
            "A card bill pays for purchases already in the list above. " +
                "Counting both would show them twice.",
            style = MaterialTheme.typography.bodySmall,
            color = Ours.textSecondary,
        )
    }
}

@Composable
private fun HairlineRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
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
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }

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
            modifier = Modifier.padding(horizontal = EDGE),
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
                modifier = Modifier.padding(horizontal = EDGE),
            )
        }
    }
}

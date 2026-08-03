package com.manuel.ours.ui.screens.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.DayTotal
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MemberTotal
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.AnimatedAmount
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.PillTone
import com.manuel.ours.ui.components.PrimaryAction
import com.manuel.ours.ui.components.QuietEmpty
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.components.StatePill
import com.manuel.ours.ui.components.StatementEntry
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.AxisLabelStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.WordmarkStyle
import com.manuel.ours.ui.theme.colorForCategory
import com.manuel.ours.work.SmsBackfillWorker
import com.manuel.ours.work.SyncWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val EDGE = 15.dp

/**
 * Home, assembled entirely from the elements in `ui/components/Statement.kt`.
 *
 * The order is deliberate and is the argument the screen makes: what you spent, how
 * that sits against the budget, when it happened, who spent it, and only then the one
 * thing worth doing about it. The list of individual transactions comes last because
 * it is the least useful thing on the screen — it is what every other expense app
 * leads with.
 */
@Composable
fun HomeScreen(
    onTransactionClick: (String) -> Unit,
    onSeeAll: () -> Unit,
    onSort: () -> Unit,
    onSetBudget: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasSmsPermission = result[Manifest.permission.READ_SMS] == true
        if (hasSmsPermission) SmsBackfillWorker.rescan(context)
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("OURS", style = WordmarkStyle, color = Ours.text)
                    // Only meaningful once there is somewhere for changes to go. With
                    // no partner and no folder, "171 pending" reads as a failure when
                    // in fact nothing is wrong or waiting.
                    if (state.syncConfigured) {
                        SyncStatePill(
                            lastSyncAt = state.lastSyncAt,
                            pending = state.pendingSyncCount,
                            onClick = { SyncWorker.syncNow(context) },
                        )
                    }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    MicroLabel("Spent this month")
                    AnimatedAmount(
                        paise = state.spentThisMonth,
                        style = MaterialTheme.typography.displayLarge,
                        color = Ours.text,
                    )
                }
            }

            // Always present, with or without a budget. The ruler is what gives the
            // figure above it a scale; without it the hero is a number with nothing to
            // measure against, and the screen loses the shape the mockup gives it.
            item {
                BudgetRuler(
                    spent = state.spentThisMonth,
                    budget = state.budgetPaise,
                    onSetBudget = onSetBudget,
                )
            }

            if (state.days.any { it.totalPaise > 0 }) {
                item { DailyColumns(days = state.days) }
            }

            if (state.memberTotals.size > 1) {
                item { HouseholdSplit(members = state.memberTotals, selfUid = state.selfUid) }
            }

            if (state.hasPartner) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = EDGE),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // One chip per person who actually has rows, not a fixed
                        // Both/Me/Partner triple — a household can be three.
                        OursChip(
                            label = "Everyone",
                            selected = state.filter == MemberFilter.Everyone,
                            onClick = { viewModel.setFilter(MemberFilter.Everyone) },
                        )
                        state.people.forEach { person ->
                            OursChip(
                                label = person.chipLabel,
                                selected = (state.filter as? MemberFilter.Person)?.uid == person.uid,
                                onClick = { viewModel.setFilter(MemberFilter.Person(person.uid)) },
                            )
                        }
                    }
                }
            }

            // The one primary action. Its caption leads with the group count because
            // that is the number that makes the job sound finishable.
            if (state.untaggedCount > 0) {
                item {
                    PrimaryAction(
                        title = "Sort ${state.untaggedCount} " +
                            if (state.untaggedCount == 1) "expense" else "expenses",
                        caption = "${state.untaggedGroups} " +
                            (if (state.untaggedGroups == 1) "group" else "groups") +
                            " · about ${state.untaggedGroups} taps",
                        onClick = onSort,
                        modifier = Modifier.padding(horizontal = EDGE),
                    )
                }
            }

            // Skipping the permission during onboarding used to leave the app
            // permanently, silently empty with no way back. This is the way back.
            if (!hasSmsPermission) {
                item {
                    Notice(
                        tone = Ours.negative,
                        title = "Nothing is being tracked",
                        body = "Ours can't read your bank messages, so no expenses are being recorded.",
                        actionLabel = "Turn on SMS access",
                        onAction = {
                            smsPermissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                            )
                        },
                    )
                }
            }

            if (state.upcomingBills.isNotEmpty()) {
                item {
                    UpcomingBills(
                        bills = state.upcomingBills,
                        onDismiss = viewModel::dismissBill,
                    )
                }
            }

            val tape = state.todayEntries.ifEmpty { state.recent }
            val isToday = state.todayEntries.isNotEmpty()

            item {
                TapeHeader(
                    label = if (isToday) "Today" else "Recent",
                    trailing = if (isToday) Money.whole(state.spentToday) else "See all",
                    trailingColor = if (isToday) Ours.textSecondary else Ours.accent,
                    modifier = Modifier
                        .padding(horizontal = EDGE)
                        .then(if (isToday) Modifier else Modifier.clickable(onClick = onSeeAll)),
                )
            }

            if (state.loading) {
                item { TapeSkeleton() }
            } else if (tape.isEmpty()) {
                item { QuietEmpty("Nothing yet this month", modifier = Modifier.padding(horizontal = EDGE)) }
            } else {
                items(tape, key = { it.id }) { txn ->
                    TransactionTapeRow(
                        txn = txn,
                        last = txn.id == tape.last().id,
                        onClick = { onTransactionClick(txn.id) },
                    )
                }
            }

            // In the flow, right-aligned, rather than floating over the list. A
            // floating button permanently covers the last row of a statement — which
            // is exactly the row you just added.
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Ours.accent)
                            .clickable { showAddSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        BiIconView(
                            BiIcon.Add,
                            contentDescription = "Add expense",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddExpenseSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { amount, merchant, category, split ->
                viewModel.addQuickExpense(amount, merchant, category, split)
                showAddSheet = false
            },
        )
    }
}

/**
 * Budget as a ruler with its three figures underneath.
 *
 * "Left" is given as much weight as "used" because it is the number that changes a
 * decision — nobody has ever declined to buy something because they were at 68%.
 */
@Composable
private fun BudgetRuler(
    spent: Long,
    budget: Long?,
    onSetBudget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (budget == null || budget <= 0) {
        Column(
            modifier
                .fillMaxWidth()
                .clickable(onClick = onSetBudget)
                .padding(horizontal = EDGE),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // An empty scale, not a filled one. Drawing a bar with nothing behind it
            // would invent a budget the household never set; an unmarked ruler says
            // plainly that there is a scale here and nothing is measured on it yet.
            Ruler(fraction = 0f)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabelOverValue(
                    label = "Budget",
                    value = "Not set",
                    valueColor = Ours.textSecondary,
                )
                MicroLabel(
                    "Set one",
                    color = Ours.accent,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        return
    }

    val fraction = spent.toFloat() / budget
    val over = spent > budget
    val left = budget - spent
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Ruler(fraction = fraction, over = over)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelOverValue("Budget", Money.formatCompact(budget))
            LabelOverValue(
                label = "Used",
                value = "${(fraction * 100).toInt()}%",
                valueColor = if (over) Ours.negative else Ours.positive,
                alignment = Alignment.CenterHorizontally,
            )
            LabelOverValue(
                label = if (over) "Over" else "Left",
                value = Money.whole(kotlin.math.abs(left)),
                valueColor = if (over) Ours.negative else Ours.positive,
                alignment = Alignment.End,
            )
        }
    }
}

/**
 * One column per day of the month.
 *
 * Only the peak is labelled. A number over every column is noise — the shape tells you
 * the rhythm, and the single label tells you the one thing the shape can't: that
 * Saturday was a third of the month.
 */
@Composable
private fun DailyColumns(days: List<DayTotal>, modifier: Modifier = Modifier) {
    val peak = days.maxByOrNull { it.totalPaise } ?: return
    if (peak.totalPaise <= 0) return
    val month = LocalDate.now().month.getDisplayName(
        java.time.format.TextStyle.FULL, Locale.getDefault()
    )

    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicroLabel("Daily · $month")
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                val ratio = (day.totalPaise.toFloat() / peak.totalPaise).coerceIn(0f, 1f)
                val isPeak = day.dayOfMonth == peak.dayOfMonth
                Box(
                    Modifier
                        .weight(1f)
                        // A zero-spend day still gets a visible sliver, so the axis
                        // reads as a full month rather than as missing days.
                        .fillMaxHeight(ratio.coerceAtLeast(0.035f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (isPeak) Ours.accent else Ours.hairline)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = AxisLabelStyle, color = Ours.textLabel)
            Text(
                // Whole rupees on the axis too — paise on a chart label is precision
                // nobody asked a chart for.
                "${peak.dayOfMonth} · ${Money.whole(peak.totalPaise)}",
                style = AxisLabelStyle,
                color = Ours.textLabel,
            )
            Text("${days.size}", style = AxisLabelStyle, color = Ours.textLabel)
        }
    }
}

/** Who spent what, as one bar. Two people, two segments, no chart. */
@Composable
private fun HouseholdSplit(
    members: List<MemberTotal>,
    selfUid: String,
    modifier: Modifier = Modifier,
) {
    val total = members.sumOf { it.totalPaise }.coerceAtLeast(1)
    // A colour for every member, not two. The bar used to draw the first two and take
    // the rest with it — a third person's spending vanished from both the bar and the
    // legend, while still counting toward the total the widths were computed from, so
    // the bar quietly failed to fill.
    val palette = listOf(
        Ours.accent,
        colorForCategory(Category.SHOPPING),
        colorForCategory(Category.GROCERIES),
        colorForCategory(Category.FOOD),
        colorForCategory(Category.TRANSPORT),
        colorForCategory(Category.HEALTH),
    )
    val colorFor = { index: Int -> palette[index % palette.size] }

    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MicroLabel("Household")
        Row(
            Modifier.fillMaxWidth().height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            members.forEachIndexed { index, member ->
                Box(
                    Modifier
                        .weight((member.totalPaise.toFloat() / total).coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(colorFor(index))
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            members.forEachIndexed { index, member ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colorFor(index))
                    )
                    Text(
                        text = if (member.uid == selfUid) "You"
                        else member.displayName.split(" ").first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.textSecondary,
                    )
                    Text(
                        text = Money.bare(member.totalPaise),
                        style = com.manuel.ours.ui.theme.ValueTextStyle.copy(fontSize = 12.sp),
                        color = Ours.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionTapeRow(txn: Transaction, last: Boolean, onClick: () -> Unit) {
    TransactionEntry(
        txn = txn,
        divider = !last,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = EDGE),
    )
}

@Composable
private fun TapeSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = EDGE),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(4) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Ours.surfaceHigh))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.45f).height(11.dp).clip(RoundedCornerShape(5.dp)).background(Ours.surfaceHigh))
                    Box(Modifier.fillMaxWidth(0.25f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(Ours.surfaceHigh))
                }
                Box(Modifier.width(48.dp).height(12.dp).clip(RoundedCornerShape(5.dp)).background(Ours.surfaceHigh))
            }
        }
    }
}

/**
 * Bills you owe, kept apart from money you've spent.
 *
 * Parsed from "due by" messages the app deliberately refuses to record as expenses —
 * counting an unpaid bill as spending would double-count it the moment you pay it.
 */
@Composable
private fun UpcomingBills(
    bills: List<UpcomingBill>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, Ours.warning.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        MicroLabel(
            if (bills.size == 1) "Bill due soon" else "${bills.size} bills due soon",
            color = Ours.warning,
        )
        bills.forEach { bill ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        bill.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ours.text,
                    )
                    MicroLabel(
                        when (bill.daysAway) {
                            0 -> "Due today"
                            1 -> "Due tomorrow"
                            else -> "Due in ${bill.daysAway} days"
                        }
                    )
                }
                bill.amountPaise?.let { AmountColumn(it) }
                BiIconView(
                    icon = BiIcon.Dismiss,
                    contentDescription = "Dismiss",
                    tint = Ours.textSecondary,
                    modifier = Modifier.size(13.dp).clickable { onDismiss(bill.id) },
                )
            }
        }
    }
}

/** A bordered notice with one action. Colour is the only thing that varies. */
@Composable
private fun Notice(
    tone: Color,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ours.text)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Ours.textSecondary)
        GhostButton(actionLabel, onClick = onAction)
    }
}

@Composable
private fun SyncStatePill(lastSyncAt: Long, pending: Int, onClick: () -> Unit) {
    val (text, tone, icon) = when {
        pending > 0 -> Triple("$pending waiting", PillTone.Warn, null)
        lastSyncAt == 0L -> Triple("Off", PillTone.Neutral, null)
        else -> Triple(relativeTime(lastSyncAt), PillTone.Ok, BiIcon.Done)
    }
    StatePill(
        text = text,
        tone = tone,
        icon = icon,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "Now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        else -> "${days}d"
    }
}

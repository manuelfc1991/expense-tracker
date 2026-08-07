package com.manuel.ours.ui.screens.home

import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
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
import com.manuel.ours.domain.Pacing
import com.manuel.ours.ui.theme.ValueTextStyle
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.DayTotal
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MemberTotal
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.components.EmptyState
import com.manuel.ours.ui.components.StatementSkeleton
import com.manuel.ours.ui.components.NoticeTone
import com.manuel.ours.ui.components.Notice
import com.manuel.ours.ui.components.StaleRibbon
import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.CaptureSheet
import com.manuel.ours.ui.components.AmountColumn
import com.manuel.ours.ui.components.AnimatedAmount
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.components.GhostButton
import com.manuel.ours.ui.components.LabelOverValue
import com.manuel.ours.ui.components.MicroLabel
import com.manuel.ours.ui.components.OursChip
import com.manuel.ours.ui.components.PillTone
import com.manuel.ours.ui.components.PrimaryAction
import com.manuel.ours.ui.components.Ruler
import com.manuel.ours.ui.components.StatePill
import com.manuel.ours.ui.components.TapeHeader
import com.manuel.ours.ui.components.TransactionEntry
import com.manuel.ours.ui.theme.AxisLabelStyle
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import com.manuel.ours.ui.theme.colorForCategory
import com.manuel.ours.work.SmsBackfillWorker
import com.manuel.ours.work.SyncWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit


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
    onOpenDeleteRequests: () -> Unit = {},
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

    // The payment that landed while you were looking. Nothing is drawn for history.
    val arrived by viewModel.justArrived.collectAsStateWithLifecycle()
    arrived?.let { capture ->
        val txn = capture.txn
        CaptureSheet(
            txn = txn,
            suggestions = capture.suggestions,
            onDismiss = { viewModel.dismissCapture(txn.id) },
            onCategorize = { viewModel.categorize(txn.id, it) },
            onRename = { name, keep ->
                viewModel.renameFromCapture(txn.id, name, txn.counterpartyTail, keep)
            },
            onNote = { viewModel.noteFromCapture(txn.id, it) },
        )
    }

      // No Scaffold here. The nav host is already inside one — `Navigation.kt` applies
      // that Scaffold's innerPadding to the NavHost, which reserves the tab bar and the
      // system navigation bar. A second Scaffold nested in that region worked the inset
      // out again from where it now sat, and Home applied it twice: the statement
      // stopped ~49dp above the tab bar and left a dead band under the last row, which
      // read as the add button pushing the list up.
      //
      // Floating bottom-right, and out of the way when it would be in it.
      //
      // The strip this replaces cost 64dp to guarantee the button never covered an
      // amount, because the amount column is the whole layout. Floating brings that
      // risk back, so two things hold it off: the button disappears the moment the
      // list moves, and the list carries enough bottom padding that its end always
      // clears the button's corner. Between them, any row the button sits on can be
      // scrolled out from under it — which is what bottom padding alone could not do,
      // since Home rests at the top and the covered row was an ordinary one.
      val listState = rememberLazyListState()
      // derivedStateOf, not a plain read: isScrollInProgress changes on every frame of
      // a fling, and recomposing the whole statement that often to move one button is
      // how a scroll starts dropping frames.
      val addVisible by remember { derivedStateOf { !listState.isScrollInProgress } }
      Box(Modifier.fillMaxSize().background(Ours.surface)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // What is stale, and what is not.
            //
            // This phone's own bank messages are read locally and are certainly current; what
            // may be missing is anything the other person paid since the last sync. So the
            // figures are never hidden or greyed — they are correct, just possibly incomplete,
            // and the ribbon says which part. A ribbon rather than a dialog: nothing here blocks.
            if (state.syncConfigured && state.staleFor != null) {
                item {
                    StaleRibbon(
                        label = "Offline · last synced ${state.staleFor}",
                        onRetry = { SyncWorker.syncNow(context) },
                    )
                }
            }

            state.syncError?.let { reason ->
                item {
                    Notice(
                        tone = NoticeTone.Error,
                        title = "Sync failed",
                        body = reason,
                        modifier = Modifier.padding(horizontal = Space.edge),
                        action = {
                            GhostButton("Try again", onClick = { SyncWorker.syncNow(context) })
                        },
                    )
                }
            }

            item {
                OursTopBar(title = "Ours") {
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
                    // Search reaches the whole ledger from the screen people open first. It used
                    // to exist only on Activity, so finding a payee meant changing tab.
                    OursIconButton(
                        icon = OursIcon.Search,
                        contentDescription = "Search expenses",
                        onClick = onSeeAll,
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.edge),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    MicroLabel("Spent this month")
                    AnimatedAmount(
                        paise = state.spentThisMonth,
                        style = MaterialTheme.typography.displayLarge,
                        color = Ours.onSurface,
                    )
                }
            }

            // Always present, with or without a budget. The ruler is what gives the
            // figure above it a scale; without it the hero is a number with nothing to
            // measure against, and the screen loses the shape the mockup gives it.
            item {
                BudgetRuler(
                    // The household's spending, not the chip's. One cap over one
                    // household: measuring a single member against it made "Left" grow
                    // every time the view was narrowed, which is the one direction a
                    // budget figure must never move for free.
                    spent = state.householdSpentThisMonth,
                    budget = state.budgetPaise,
                    affordability = state.affordability,
                    pacing = state.pacing,
                    filtered = state.filter != MemberFilter.Everyone,
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
                            .padding(horizontal = Space.edge),
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
                        modifier = Modifier.padding(horizontal = Space.edge),
                    )
                }
            }

            // Skipping the permission during onboarding used to leave the app
            // permanently, silently empty with no way back. This is the way back.
            if (!hasSmsPermission) {
                item {
                    Notice(
                        tone = Ours.error,
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

            // Above the bills: somebody is waiting on an answer, and until it comes their
            // delete looks to them like a button that does nothing. A pill buried in
            // Settings was not enough — nobody opens Settings to check for a question
            // they have not been told was asked.
            if (state.isHouseholdOwner && state.pendingDeleteRequests > 0) {
                item {
                    Notice(
                        tone = Ours.warning,
                        title = if (state.pendingDeleteRequests == 1) {
                            "A delete is waiting for you"
                        } else {
                            "${state.pendingDeleteRequests} deletes are waiting for you"
                        },
                        body = if (state.pendingDeleteRequests == 1) {
                            "Someone in the household asked to remove a transaction. " +
                                "It still counts until you decide."
                        } else {
                            "Someone in the household asked to remove " +
                                "${state.pendingDeleteRequests} transactions. They still " +
                                "count until you decide."
                        },
                        actionLabel = "Review",
                        onAction = onOpenDeleteRequests,
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
                    trailing = if (isToday) Money.exact(state.spentToday) else "See all",
                    trailingColor = if (isToday) Ours.onSurfaceVariant else Ours.primary,
                    modifier = Modifier
                        .padding(horizontal = Space.edge)
                        .then(if (isToday) Modifier else Modifier.clickable(onClick = onSeeAll)),
                )
            }

            if (state.loading) {
                item { StatementSkeleton(Modifier.padding(horizontal = Space.edge)) }
            } else if (tape.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing yet this month",
                        body = "Expenses appear here as your bank messages arrive.",
                        icon = OursIcon.Activity,
                    )
                }
            } else {
                items(tape, key = { it.id }) { txn ->
                    TransactionTapeRow(
                        txn = txn,
                        last = txn.id == tape.last().id,
                        onClick = { onTransactionClick(txn.id) },
                    )
                }
            }

        }

        // Bottom-right, over the statement, and gone while the list is moving.
        AnimatedVisibility(
            visible = addVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(
                Modifier
                    .padding(end = Space.edge, bottom = 14.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Ours.primaryFixed)
                    .clickable { showAddSheet = true },
                contentAlignment = Alignment.Center,
            ) {
                OursIconView(
                    OursIcon.Add,
                    contentDescription = "Add expense",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
      }

    if (showAddSheet) {
        val accounts by viewModel.accounts.collectAsStateWithLifecycle()
        AddExpenseSheet(
            onDismiss = { showAddSheet = false },
            accounts = accounts,
            onConfirm = { amount, merchant, category, split, note, occurredAt, tail, bank ->
                viewModel.addQuickExpense(
                    amount, merchant, category, split, note, occurredAt, tail, bank,
                )
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
    affordability: Affordability?,
    pacing: Pacing.Result?,
    filtered: Boolean,
    onSetBudget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (budget == null || budget <= 0) {
        Column(
            modifier
                .fillMaxWidth()
                .clickable(onClick = onSetBudget)
                .padding(horizontal = Space.edge),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // An empty scale, not a filled one. Drawing a bar with nothing behind it
            // would invent a budget the household never set; an unmarked ruler says
            // plainly that there is a scale here and nothing is measured on it yet.
            Ruler(fraction = 0f)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabelOverValue(
                    label = "Budget",
                    value = "Not set",
                    valueColor = Ours.onSurfaceVariant,
                )
                MicroLabel(
                    "Set one",
                    color = Ours.primary,
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
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Ruler(fraction = fraction, over = over)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelOverValue("Budget", Money.formatCompact(budget))
            LabelOverValue(
                label = "Used",
                value = "${(fraction * 100).toInt()}%",
                valueColor = if (over) Ours.error else Ours.success,
                alignment = Alignment.CenterHorizontally,
            )
            LabelOverValue(
                label = if (over) "Over" else "Left",
                value = Money.whole(kotlin.math.abs(left)),
                valueColor = if (over) Ours.error else Ours.success,
                alignment = Alignment.End,
            )
        }

        // What the budget still allows, per day, for the days that are left.
        //
        // The screen's central number was date-blind: 74% of a month's budget gone on the 6th
        // was reported in green with nothing said about it, because `spent / budget` cannot
        // know what day it is. This is the line that answers "can I spend today", which is the
        // question a household actually asks a budget.
        pacing?.let { pace ->
            when (pace.state) {
                Pacing.State.Short -> {
                    // No daily figure: dividing into a negative produces a number that reads
                    // like an allowance. The shortfall is the honest thing to print.
                    HairlineRow()
                    Text(
                        text = "${Money.whole(pace.committedPaise)} is still committed this " +
                            "month and only ${Money.whole(pace.budgetLeftPaise.coerceAtLeast(0))} " +
                            "is left — ${Money.whole(pace.shortfallPaise)} short before anyone " +
                            "spends anything else.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ours.error,
                    )
                }
                else -> {
                    val tight = pace.state == Pacing.State.Tight
                    val tone = if (tight) Ours.warning else Ours.success
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                        MicroLabel("A day from here", color = if (tight) Ours.warning else Ours.onSurfaceMuted)
                        Text(
                            text = Money.whole(pace.perDayPaise ?: 0L),
                            style = ValueTextStyle,
                            color = tone,
                            maxLines = 1,
                        )
                        Text(
                            text = buildString {
                                append(pace.daysRemaining)
                                append(if (pace.daysRemaining == 1) " day left" else " days left")
                                if (pace.committedPaise > 0) {
                                    append(", after ")
                                    append(Money.whole(pace.committedPaise))
                                    append(" still committed")
                                }
                                append(".")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tight) Ours.warning else Ours.onSurfaceMuted,
                        )
                    }
                }
            }
        }

        // The reality check the budget cannot perform on itself.
        //
        // "Left" here means only *permitted*, and permission is not money. With ₹18,000
        // of budget still to run and ₹10,149 actually in the accounts, the honest figure
        // is the smaller one — and until this line existed the screen showed the larger
        // one with nothing to qualify it.
        //
        // Said only when the accounts are the tighter of the two. When the budget binds,
        // the ruler above is already the whole truth and a second sentence repeating it
        // would be noise on the screen that has to stay glanceable.
        if (affordability != null && affordability.budgetOutrunsMoney) {
            Text(
                text = buildString {
                    append("Your accounts hold ")
                    append(Money.whole(affordability.usablePaise ?: 0L))
                    if (affordability.partialView) append(" of yours")
                    append(" — less than the budget still allows. ")
                    append("Safe to spend: ")
                    append(Money.whole(affordability.safeToSpendPaise ?: 0L))
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ours.warning,
            )
        }

        // The ruler counts everybody, so a narrowed view must say so rather than let
        // the hero figure above and the ruler below look like the same measurement.
        if (filtered) {
            MicroLabel("Budget counts the whole household")
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
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
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
                        .background(if (isPeak) Ours.primaryFixed else Ours.outlineVariant)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = AxisLabelStyle, color = Ours.onSurfaceMuted)
            Text(
                // Whole rupees on the axis too — paise on a chart label is precision
                // nobody asked a chart for.
                "${peak.dayOfMonth} · ${Money.exact(peak.totalPaise)}",
                style = AxisLabelStyle,
                color = Ours.onSurfaceMuted,
            )
            Text("${days.size}", style = AxisLabelStyle, color = Ours.onSurfaceMuted)
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
        Ours.primaryFixed,
        colorForCategory(Category.SHOPPING),
        colorForCategory(Category.GROCERIES),
        colorForCategory(Category.FOOD),
        colorForCategory(Category.TRANSPORT),
        colorForCategory(Category.HEALTH),
    )
    val colorFor = { index: Int -> palette[index % palette.size] }

    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.edge),
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
                        color = Ours.onSurfaceVariant,
                    )
                    Text(
                        text = Money.bare(member.totalPaise, withDecimals = true),
                        style = com.manuel.ours.ui.theme.ValueTextStyle.copy(fontSize = 12.sp),
                        color = Ours.onSurface,
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
        modifier = Modifier.padding(horizontal = Space.edge),
    )
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
            .padding(horizontal = Space.edge)
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
                        color = Ours.onSurface,
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
                OursIconButton(
                    icon = OursIcon.Dismiss,
                    contentDescription = "Dismiss this bill",
                    onClick = { onDismiss(bill.id) },
                    tint = Ours.onSurfaceVariant,
                    glyph = 14.dp,
                    // 40dp: it sits directly beside the amount column, so a full 48 would push
                    // the figure off its shared right edge.
                    size = Space.targetTight,
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
            .padding(horizontal = Space.edge)
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ours.onSurface)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Ours.onSurfaceVariant)
        GhostButton(actionLabel, onClick = onAction)
    }
}

@Composable
private fun SyncStatePill(lastSyncAt: Long, pending: Int, onClick: () -> Unit) {
    val (text, tone, icon) = when {
        pending > 0 -> Triple("$pending waiting", PillTone.Warn, null)
        lastSyncAt == 0L -> Triple("Off", PillTone.Neutral, null)
        else -> Triple(relativeTime(lastSyncAt), PillTone.Ok, OursIcon.Done)
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

@Composable
private fun HairlineRow() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
}

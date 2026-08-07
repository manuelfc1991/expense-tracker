package com.manuel.ours.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.BudgetRepository
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.Pacing
import com.manuel.ours.domain.RecurringDetector
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryTotal
import com.manuel.ours.domain.model.DayTotal
import com.manuel.ours.domain.model.HouseholdMember
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MemberTotal
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val filter: MemberFilter = MemberFilter.Everyone,
    val spentThisMonth: Long = 0,
    /**
     * Spending this month across the whole household, ignoring the Both/Me/Partner chip.
     *
     * The budget is one cap over one household, so this is the only figure that can
     * honestly be measured against it. [spentThisMonth] follows the chip and is what the
     * rest of the screen is about; the ruler used it too, which meant tapping "Me" made
     * the household look further from its limit than it was — the more you narrowed the
     * view, the more budget the app claimed you had left. It also disagreed with the
     * widget and the over-budget alert, both of which have always counted everyone.
     */
    val householdSpentThisMonth: Long = 0,
    val budgetPaise: Long? = null,
    /**
     * What the accounts actually hold, set against what the budget still permits.
     *
     * Home shows only the collision — the full reckoning, commitments included, is on
     * Summary where the recurring charges are already worked out.
     */
    val affordability: Affordability? = null,
    /**
     * What can be spent per day for the rest of the month without missing the cap.
     *
     * The highest-priority finding in `docs/REVIEW.md`: nothing in the budget path took the date
     * as an input, so 74% of a month's budget gone on the 6th was reported in green with no
     * comment. See [Pacing] for why this paces the discretionary money rather than the total.
     */
    val pacing: Pacing.Result? = null,
    /**
     * Why the last sync failed, or null if it did not.
     *
     * The app had no error surface at all — a failure was a red line inside a Settings
     * disclosure, which is not where anyone looks. Shown on Home, above the ledger, with the
     * data still readable beneath it.
     */
    val syncError: String? = null,
    /**
     * How long the other phone may have been out of step, as a phrase — "3h", "2d" — or null
     * when the last sync is recent enough not to be worth mentioning.
     *
     * Derived here rather than in the composable so the threshold is one decision in one place
     * and can be tested.
     */
    val staleFor: String? = null,
    val vsLastMonthPercent: Float? = null,
    val spentToday: Long = 0,
    val spentThisWeek: Long = 0,
    val categories: List<CategoryTotal> = emptyList(),
    val recent: List<Transaction> = emptyList(),
    /** Every day of the month, zero-spend days included, for the daily columns. */
    val days: List<DayTotal> = emptyList(),
    /** Who spent what, for the household split bar. Empty until someone else exists. */
    val memberTotals: List<MemberTotal> = emptyList(),
    /** Everyone with a row this month, self first — one filter chip each. */
    val people: List<HouseholdMember> = emptyList(),
    val selfUid: String = "",
    /** Today's transactions, newest first — the tape at the bottom of Home. */
    val todayEntries: List<Transaction> = emptyList(),
    /**
     * Rows still to be sorted, and how many merchants they collapse into.
     *
     * Both numbers are shown together because the second is the reassuring one: 94
     * unsorted expenses sounds like an evening's work, six groups sounds like six taps.
     */
    val untaggedCount: Int = 0,
    val untaggedGroups: Int = 0,
    val needsReviewCount: Int = 0,
    /**
     * Rows a member has asked the owner to remove, waiting on an answer.
     *
     * Surfaced on Home because it was previously reachable only by opening Settings and
     * noticing a pill — so a request sat unanswered while the person who made it saw
     * nothing happen and assumed the app was broken.
     */
    val pendingDeleteRequests: Int = 0,
    /** Only the owner can act on a delete request, so only the owner is told about one. */
    val isHouseholdOwner: Boolean = false,
    val pendingSyncCount: Int = 0,
    val lastSyncAt: Long = 0,
    val lastSyncTransport: String? = null,
    val partnerName: String? = null,
    /**
     * True only once the other phone's data has actually arrived. Derived from the
     * transactions themselves rather than from "a household exists", because creating
     * a household is a one-tap action you might do before your partner ever installs
     * the app — and a Both/Me/Partner toggle with nobody on the other side is just
     * clutter that makes two of the three options do nothing.
     */
    val hasPartner: Boolean = false,
    /** True once a partner exists or a sync folder is chosen. */
    val syncConfigured: Boolean = false,
    val upcomingBills: List<UpcomingBill> = emptyList(),
)

data class UpcomingBill(
    val id: String,
    val label: String,
    val amountPaise: Long?,
    val dueAt: Long,
    val daysAway: Int,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val prefs: AppPrefs,
    private val reminderDao: com.manuel.ours.data.db.ReminderDao,
    private val pendingSenders: com.manuel.ours.data.repo.PendingSenderRepository,
    syncEventDao: SyncEventDao,
) : ViewModel() {

    private val filter = MutableStateFlow<MemberFilter>(MemberFilter.Everyone)

    /**
     * When this screen started watching.
     *
     * Anything recorded before it is history and is left alone; only a payment that
     * lands while the app is open earns a prompt. Without this the sheet would fire on
     * every launch for whatever happened to be untagged, which is nagging rather than
     * capture.
     */
    private val watchingSince = System.currentTimeMillis()

    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** Puts the sheet away without deciding anything; the row stays in Sort. */
    fun dismissCapture(txnId: String) { dismissed.value = dismissed.value + txnId }

    fun categorize(txnId: String, category: Category) {
        viewModelScope.launch { transactionRepository.recategorize(txnId, category, learn = true) }
    }

    fun renameFromCapture(txnId: String, name: String, tail: String?, remember: Boolean) {
        viewModelScope.launch {
            if (remember && !tail.isNullOrBlank()) transactionRepository.nameAccount(tail, name)
            else transactionRepository.rename(txnId, name)
        }
    }

    fun noteFromCapture(txnId: String, note: String) {
        viewModelScope.launch { transactionRepository.setNote(txnId, note) }
    }

    /** A payment that landed while the app was open, with its three guesses ready. */
    data class Capture(val txn: Transaction, val suggestions: List<Category>)

    val justArrived: StateFlow<Capture?> = combine(
        transactionRepository.observeNeedsReview(),
        dismissed,
    ) { pending, seen ->
        pending.firstOrNull { it.id !in seen && it.occurredAt >= watchingSince }
    }.mapLatest { txn ->
        // Guesses resolved here rather than in the composable: a sheet that opened
        // empty and filled in a frame later would offer "All" as the only option at
        // exactly the moment a thumb is arriving.
        txn?.let {
            Capture(
                txn = it,
                suggestions = transactionRepository.predictCategories(
                    it.merchant, it.amountPaise, it.type,
                ).filter { c -> c != Category.OTHER },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)




    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = combine(
        combine(prefs.selfUid, prefs.selfName, prefs.householdOwner) { uid, name, owner ->
            Triple(uid.orEmpty(), name.orEmpty(), owner)
        },
        filter,
        budgetRepository.observeOverall(),
        syncEventDao.observeUnpushedCount(),
        // Paired rather than passed separately: `combine` only has typed overloads up to five
        // flows, and the sixth silently drops to the vararg `Array<T>` form, which loses every
        // parameter type. Nesting keeps the lambda typed.
        combine(prefs.lastSyncAt, prefs.lastSyncError) { at, error -> at to error },
    ) { self, memberFilter, budget, pending, sync ->
        Params(
            self.first, self.second, self.third, memberFilter, budget, pending,
            sync.first, sync.second,
        )
    }.flatMapLatest { params ->
        val today = LocalDate.now(MonthlyAggregator.ZONE)
        val thisMonth = MonthlyAggregator.monthRange(today.year, today.monthValue)
        val previous = today.minusMonths(1)
        val lastMonth = MonthlyAggregator.monthRange(previous.year, previous.monthValue)
        // A pattern needs history to be visible at all, so commitments are detected over the
        // same window Summary uses — the same constant, the same detector, the same filter.
        //
        // Home used to leave commitments out entirely, on the argument that it would need this
        // whole window for one line of caption and that a figure disagreeing with Summary's
        // would be worse than no figure. The first half was a cost worth paying once the
        // pacing line made it the most useful line on the screen; the second half is answered
        // by construction rather than by care, because both screens now call the same function
        // over the same range.
        val lookback = MonthlyAggregator.monthRange(
            today.minusMonths(RecurringDetector.LOOKBACK_MONTHS).year,
            today.minusMonths(RecurringDetector.LOOKBACK_MONTHS).monthValue,
        )

        combine(
            transactionRepository.observeBetween(lookback.first, thisMonth.last + 1),
            transactionRepository.observeNeedsReviewCount(),
            reminderDao.observeUpcoming(System.currentTimeMillis() - DAY_MS),
            transactionRepository.observeBalances(params.selfUid, params.owner),
            transactionRepository.observeDeleteRequestCount(),
        ) { allTxns, reviewCount, reminders, balances, deleteRequests ->
            val current = MonthlyAggregator.applyFilter(
                allTxns.filter { it.occurredAt in thisMonth },
                params.filter,
                params.selfUid,
            )
            val prior = MonthlyAggregator.applyFilter(
                allTxns.filter { it.occurredAt in lastMonth },
                params.filter,
                params.selfUid,
            )

            val spent = MonthlyAggregator.totalSpent(current)
            val priorSpent = MonthlyAggregator.totalSpent(prior)

            // Unfiltered on purpose: one cap over one household. See [householdSpent-
            // ThisMonth]. This is also what the widget and the over-budget alert have
            // always counted, so all three now say the same thing.
            val householdSpent = MonthlyAggregator.totalSpent(
                allTxns.filter { it.occurredAt in thisMonth }
            )

            // Look across both months, not just the current one: a partner who
            // spent nothing so far this month is still a partner.
            // The placeholder owner is *me before I had an id*, never a second
            // person. Treating it as a partner is what produced "Both · Me · Me".
            // Everyone who owns a row across both months — a member who spent nothing
            // this month is still a member, so the chip must not vanish mid-month.
            val people = MonthlyAggregator.peopleIn(allTxns, params.selfUid, PLACEHOLDER_OWNER)

            // "Unsorted" is anything the parser could not confidently place: rows it
            // flagged, plus rows it dropped into Other. Both need the same one tap.
            val unsorted = current.filter { it.needsReview || it.category == Category.OTHER }

            val startOfToday = today.atStartOfDay(MonthlyAggregator.ZONE).toInstant().toEpochMilli()
            val startOfWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
                .atStartOfDay(MonthlyAggregator.ZONE).toInstant().toEpochMilli()

            HomeUiState(
                loading = false,
                filter = params.filter,
                spentThisMonth = spent,
                householdSpentThisMonth = householdSpent,
                budgetPaise = params.budget,
                // Commitments are left out here and counted on Summary, where the
                // recurring charges are already detected over two years of history.
                // Home would need that whole window for a single line of caption, and a
                // figure that disagreed with Summary's would be worse than no figure.
                affordability = affordability(
                    budgetPaise = params.budget,
                    householdSpentPaise = householdSpent,
                    balances = balances,
                    partialView = !params.owner,
                ),
                syncError = params.syncError,
                // Stale once the last sync is older than the periodic interval, so the ribbon
                // only appears when a sync has actually been missed rather than merely not
                // happened in the last minute.
                staleFor = params.lastSync
                    .takeIf { it > 0L && System.currentTimeMillis() - it > STALE_AFTER_MS }
                    ?.let { relativeAge(System.currentTimeMillis() - it) },
                pacing = run {
                    // Household-wide and unfiltered, exactly like the ruler it sits under: the
                    // budget is one cap over one household, so narrowing the view must not
                    // change what the household is allowed to spend today.
                    val recurring = RecurringDetector.detect(allTxns)
                    Pacing.of(
                        spentPaise = householdSpent,
                        budgetPaise = params.budget,
                        monthlyCommittedPaise = recurring.sumOf { it.monthlyEquivalentPaise },
                        committedRemainingPaise = MonthlyAggregator.committedRemaining(recurring),
                    )
                },
                vsLastMonthPercent = if (priorSpent > 0) {
                    (spent - priorSpent) * 100f / priorSpent
                } else null,
                spentToday = MonthlyAggregator.totalSpent(
                    current.filter { it.occurredAt >= startOfToday }
                ),
                spentThisWeek = MonthlyAggregator.totalSpent(
                    current.filter { it.occurredAt >= startOfWeek }
                ),
                categories = MonthlyAggregator.byCategory(current, prior),
                recent = current.sortedByDescending { it.occurredAt }.take(8),
                days = MonthlyAggregator.byDay(current, today.year, today.monthValue),
                // Only meaningful with two people in it; one member is not a split.
                memberTotals = MonthlyAggregator.byMember(current).takeIf { it.size > 1 }
                    ?: emptyList(),
                selfUid = params.selfUid,
                todayEntries = current
                    .filter { it.occurredAt >= startOfToday }
                    .sortedByDescending { it.occurredAt },
                untaggedCount = unsorted.size,
                untaggedGroups = unsorted.distinctBy { it.merchant.lowercase() }.size,
                needsReviewCount = reviewCount,
                pendingDeleteRequests = deleteRequests,
                isHouseholdOwner = params.owner,
                upcomingBills = reminders
                    .filter { it.dueAt - System.currentTimeMillis() < 14 * DAY_MS }
                    .map { reminder ->
                        UpcomingBill(
                            id = reminder.id,
                            label = reminder.bank ?: "Bill",
                            amountPaise = reminder.amountPaise,
                            dueAt = reminder.dueAt,
                            daysAway = ((reminder.dueAt - System.currentTimeMillis()) / DAY_MS)
                                .toInt().coerceAtLeast(0),
                        )
                    },
                pendingSyncCount = params.pending,
                lastSyncAt = params.lastSync,
                // Guard against showing your own name in the Partner slot. Rows
                // imported before onboarding carried a placeholder owner, so they
                // read as "someone else" and the toggle rendered "Both · Me · Me".
                people = people,
                partnerName = people.firstOrNull { !it.isSelf }?.displayName,
                hasPartner = people.count { !it.isSelf } > 0,
                syncConfigured = people.count { !it.isSelf } > 0,
            )
        }
    }
        // Aggregation runs off the main thread. combine/map inside stateIn execute on
        // the collector's dispatcher, which for viewModelScope is Dispatchers.Main —
        // so summing, grouping and sorting every transaction was happening on the UI
        // thread on every single database write.
        .flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setFilter(value: MemberFilter) {
        filter.value = value
    }

    /**
     * The accounts the add sheet offers under "Paid from".
     *
     * Its own flow rather than a field on [HomeUiState]: the state is assembled by a
     * `combine` that is already at its typed arity, and this is read by one sheet that is
     * usually not on screen.
     */
    val accounts: StateFlow<List<com.manuel.ours.domain.model.AccountBalance>> =
        combine(prefs.selfUid, prefs.householdOwner) { uid, owner -> uid.orEmpty() to owner }
            .flatMapLatest { (uid, owner) -> transactionRepository.observeBalances(uid, owner) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * How many senders are waiting to be answered. Its own flow for the same reason
     * [accounts] is: the state above is assembled by a `combine` already at its arity.
     */
    val possiblePayments: StateFlow<Int> = pendingSenders.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun addQuickExpense(
        amountPaise: Long,
        merchant: String,
        category: Category,
        splitType: SplitType,
        note: String,
        /** When the payment actually happened, which is not always when it was typed in. */
        occurredAt: Long = System.currentTimeMillis(),
        /** Which account it came out of. Both null means nobody said, which is a real answer. */
        accountTail: String? = null,
        bank: String? = null,
    ) {
        viewModelScope.launch {
            transactionRepository.addManual(
                amountPaise = amountPaise,
                type = TxnType.DEBIT,
                merchant = merchant,
                category = category,
                occurredAt = occurredAt,
                // Blank stays null, so an untouched field does not store an
                // empty string that later reads as a note nobody wrote.
                note = note.trim().takeIf { it.isNotEmpty() },
                splitType = splitType,
                accountTail = accountTail,
                bank = bank,
            )
        }
    }

    fun dismissBill(id: String) {
        viewModelScope.launch { reminderDao.dismiss(id) }
    }

    private data class Params(
        val selfUid: String,
        val selfName: String,
        val owner: Boolean,
        val filter: MemberFilter,
        val budget: Long?,
        val pending: Int,
        val lastSync: Long,
        val syncError: String?,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /**
         * How out of date the other phone has to be before it is worth saying so.
         *
         * Two hours, not two minutes: sync runs periodically and Bluetooth only works when the
         * two are in the same room, so a short gap is the normal condition rather than a fault.
         * A ribbon that appeared every time the phones were briefly apart would be ignored.
         */
        const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

        fun relativeAge(delta: Long): String {
            val minutes = delta / 60_000
            val hours = minutes / 60
            val days = hours / 24
            return when {
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                else -> "${days}d ago"
            }
        }
        const val PLACEHOLDER_OWNER = "local"
    }
}

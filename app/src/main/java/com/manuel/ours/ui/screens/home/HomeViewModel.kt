package com.manuel.ours.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.BudgetRepository
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryTotal
import com.manuel.ours.domain.model.DayTotal
import com.manuel.ours.domain.model.MemberTotal
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val filter: MemberFilter = MemberFilter.BOTH,
    val spentThisMonth: Long = 0,
    val budgetPaise: Long? = null,
    val vsLastMonthPercent: Float? = null,
    val spentToday: Long = 0,
    val spentThisWeek: Long = 0,
    val categories: List<CategoryTotal> = emptyList(),
    val recent: List<Transaction> = emptyList(),
    /** Every day of the month, zero-spend days included, for the daily columns. */
    val days: List<DayTotal> = emptyList(),
    /** Who spent what, for the household split bar. Empty until a partner exists. */
    val memberTotals: List<MemberTotal> = emptyList(),
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
    syncEventDao: SyncEventDao,
) : ViewModel() {

    private val filter = MutableStateFlow(MemberFilter.BOTH)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = combine(
        combine(prefs.selfUid, prefs.selfName, prefs.syncFolderUri) { uid, name, folder ->
            Triple(uid.orEmpty(), name.orEmpty(), folder)
        },
        filter,
        budgetRepository.observeOverall(),
        syncEventDao.observeUnpushedCount(),
        prefs.lastSyncAt,
    ) { self, memberFilter, budget, pending, lastSync ->
        Params(self.first, self.second, memberFilter, budget, pending, lastSync, self.third)
    }.flatMapLatest { params ->
        val today = LocalDate.now(MonthlyAggregator.ZONE)
        val thisMonth = MonthlyAggregator.monthRange(today.year, today.monthValue)
        val previous = today.minusMonths(1)
        val lastMonth = MonthlyAggregator.monthRange(previous.year, previous.monthValue)

        combine(
            transactionRepository.observeBetween(lastMonth.first, thisMonth.last + 1),
            transactionRepository.observeNeedsReviewCount(),
            reminderDao.observeUpcoming(System.currentTimeMillis() - DAY_MS),
        ) { allTxns, reviewCount, reminders ->
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

            // Look across both months, not just the current one: a partner who
            // spent nothing so far this month is still a partner.
            // The placeholder owner is *me before I had an id*, never a second
            // person. Treating it as a partner is what produced "Both · Me · Me".
            val partnerRow = allTxns.firstOrNull {
                it.ownerUid != params.selfUid &&
                    it.ownerUid != PLACEHOLDER_OWNER &&
                    it.ownerName != params.selfName
            }

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
                budgetPaise = params.budget,
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
                partnerName = partnerRow?.ownerName,
                hasPartner = partnerRow != null,
                syncConfigured = partnerRow != null || params.syncFolder != null,
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

    fun addQuickExpense(
        amountPaise: Long,
        merchant: String,
        category: Category,
        splitType: SplitType,
    ) {
        viewModelScope.launch {
            transactionRepository.addManual(
                amountPaise = amountPaise,
                type = TxnType.DEBIT,
                merchant = merchant,
                category = category,
                occurredAt = System.currentTimeMillis(),
                note = null,
                splitType = splitType,
            )
        }
    }

    fun dismissBill(id: String) {
        viewModelScope.launch { reminderDao.dismiss(id) }
    }

    private data class Params(
        val selfUid: String,
        val selfName: String,
        val filter: MemberFilter,
        val budget: Long?,
        val pending: Int,
        val lastSync: Long,
        val syncFolder: String? = null,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val PLACEHOLDER_OWNER = "local"
    }
}

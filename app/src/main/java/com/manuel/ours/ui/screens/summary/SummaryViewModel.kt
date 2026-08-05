package com.manuel.ours.ui.screens.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.RecurringDetector
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SummaryUiState(
    val loading: Boolean = true,
    val yearMonth: YearMonth = YearMonth.now(MonthlyAggregator.ZONE),
    val filter: MemberFilter = MemberFilter.Everyone,
    val summary: MonthSummary? = null,
    val transactions: List<Transaction> = emptyList(),
    /** Charges that repeat, inferred from history rather than declared anywhere. */
    val recurring: List<RecurringCharge> = emptyList(),
    /**
     * What each account was last known to hold. Drawn from every transaction the
     * household has, not from the month on screen — an account nobody touched in
     * August still holds whatever July left in it.
     */
    val balances: List<AccountBalance> = emptyList(),
    /**
     * Every rupee that left the household's accounts this month: spending, money put
     * aside, and money moved between our own accounts.
     *
     * Deliberately not the same figure as spending, and deliberately shown next to it.
     * Spending answers "what did we consume"; this answers "what left the account",
     * and the difference between them is the savings and the transfers — money that is
     * gone from the account and still ours.
     */
    val leftAccountsPaise: Long = 0L,
) {
    /** What the recurring charges add up to per month, cadences reconciled. */
    val committedMonthlyPaise: Long get() = recurring.sumOf { it.monthlyEquivalentPaise }
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val yearMonth = MutableStateFlow(YearMonth.now(MonthlyAggregator.ZONE))
    private val filter = MutableStateFlow<MemberFilter>(MemberFilter.Everyone)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SummaryUiState> =
        combine(yearMonth, filter, prefs.selfUid) { ym, memberFilter, selfUid ->
            Triple(ym, memberFilter, selfUid.orEmpty())
        }.flatMapLatest { (ym, memberFilter, selfUid) ->
            val current = MonthlyAggregator.monthRange(ym.year, ym.monthValue)
            val prior = ym.minusMonths(1)
            val priorRange = MonthlyAggregator.monthRange(prior.year, prior.monthValue)

            // A pattern needs history to be visible at all: three monthly sightings is
            // four months of data, and a yearly charge needs two years before it can be
            // told apart from a coincidence. The window is anchored to the month being
            // viewed rather than to today, so stepping back a year shows what was
            // recurring *then* instead of what recurs now.
            val lookback = ym.minusMonths(RECURRING_LOOKBACK_MONTHS)
            val lookbackStart =
                MonthlyAggregator.monthRange(lookback.year, lookback.monthValue).first

            repository.observeBetween(
                minOf(lookbackStart, priorRange.first), current.last + 1,
            ).map { all ->
                val currentTxns = MonthlyAggregator.applyFilter(
                    all.filter { txn -> txn.occurredAt in current }, memberFilter, selfUid,
                )
                val priorTxns = MonthlyAggregator.applyFilter(
                    all.filter { txn -> txn.occurredAt in priorRange }, memberFilter, selfUid,
                )
                SummaryUiState(
                    loading = false,
                    yearMonth = ym,
                    filter = memberFilter,
                    summary = MonthlyAggregator.summarize(
                        ym.year, ym.monthValue, currentTxns, priorTxns,
                    ),
                    transactions = currentTxns,
                    // Detected over the whole window, but through the same member
                    // filter — "Aarav's commitments" has to mean Aarav's.
                    recurring = RecurringDetector.detect(
                        MonthlyAggregator.applyFilter(all, memberFilter, selfUid)
                    ),
                    balances = MonthlyAggregator.accountBalances(
                        MonthlyAggregator.applyFilter(all, memberFilter, selfUid)
                    ),
                    leftAccountsPaise = MonthlyAggregator.totalDebited(currentTxns),
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
            initialValue = SummaryUiState(),
        )

    fun setMonth(value: YearMonth) {
        // Never let the pager run into the future — an empty "next month" chart
        // looks like data loss.
        val now = YearMonth.from(LocalDate.now(MonthlyAggregator.ZONE))
        yearMonth.value = if (value.isAfter(now)) now else value
    }

    private companion object {
        /**
         * Two years. Enough for a yearly charge to be seen three times, which is the
         * fewest that can be told apart from a coincidence.
         */
        const val RECURRING_LOOKBACK_MONTHS = 24L
    }

    fun previousMonth() = setMonth(yearMonth.value.minusMonths(1))
    fun nextMonth() = setMonth(yearMonth.value.plusMonths(1))
    fun setFilter(value: MemberFilter) { filter.value = value }
}

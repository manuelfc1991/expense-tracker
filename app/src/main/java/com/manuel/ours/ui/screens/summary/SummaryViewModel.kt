package com.manuel.ours.ui.screens.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class SummaryUiState(
    val loading: Boolean = true,
    val yearMonth: YearMonth = YearMonth.now(MonthlyAggregator.ZONE),
    val filter: MemberFilter = MemberFilter.BOTH,
    val summary: MonthSummary? = null,
    val transactions: List<Transaction> = emptyList(),
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val yearMonth = MutableStateFlow(YearMonth.now(MonthlyAggregator.ZONE))
    private val filter = MutableStateFlow(MemberFilter.BOTH)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SummaryUiState> =
        combine(yearMonth, filter, prefs.selfUid) { ym, memberFilter, selfUid ->
            Triple(ym, memberFilter, selfUid.orEmpty())
        }.flatMapLatest { (ym, memberFilter, selfUid) ->
            val current = MonthlyAggregator.monthRange(ym.year, ym.monthValue)
            val prior = ym.minusMonths(1)
            val priorRange = MonthlyAggregator.monthRange(prior.year, prior.monthValue)

            repository.observeBetween(priorRange.first, current.last + 1).map { all ->
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

    fun previousMonth() = setMonth(yearMonth.value.minusMonths(1))
    fun nextMonth() = setMonth(yearMonth.value.plusMonths(1))
    fun setFilter(value: MemberFilter) { filter.value = value }
}

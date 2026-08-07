package com.manuel.ours.ui.screens.sort

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * One merchant's worth of unsorted expenses.
 *
 * The group is the unit of work, not the transaction. Twelve trips to the same shop is
 * one decision a person can actually make; twelve identical questions is data entry.
 */
data class SortGroup(
    val merchant: String,
    val txnIds: List<String>,
    val totalPaise: Long,
    val count: Int,
    /**
     * True when the bank named no payee at all.
     *
     * These are held apart because the honest answer is usually "this wasn't spending" —
     * a transfer between your own accounts — and the app must not learn a category rule
     * from a placeholder name that every future unnamed debit would then match.
     */
    val unknownPayee: Boolean,
    /** Up to three one-tap guesses, most likely first. */
    val suggestions: List<Category>,
)

data class SortUiState(
    val loading: Boolean = true,
    val groups: List<SortGroup> = emptyList(),
    /** Merchant -> the category chosen for it in this sitting. */
    val doneMerchants: Map<String, Category> = emptyMap(),
    val totalRemaining: Int = 0,
    val startingTotal: Int = 0,
)

@HiltViewModel
class SortViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    private val done = MutableStateFlow<Map<String, Category>>(emptyMap())

    /**
     * The number of rows when this screen was opened, kept fixed for the session.
     *
     * The progress ruler measures against it rather than against the live count, so
     * sorting a group moves the ruler forward instead of leaving it stuck at "all
     * remaining" — the denominator shrinking in step with the numerator would make
     * finishing look like no progress at all.
     */
    private val startingTotal = MutableStateFlow(0)

    val uiState: StateFlow<SortUiState> = combine(
        repository.observeBetween(monthStart(), monthEnd()),
        done,
        startingTotal,
    ) { txns, doneSet, started ->
        val unsorted = txns.filter { it.needsReview || it.category == Category.OTHER }
        Triple(unsorted, doneSet, started)
    }.map { (unsorted, doneSet, started) ->
        if (started == 0 && unsorted.isNotEmpty()) startingTotal.value = unsorted.size

        val groups = unsorted
            .groupBy { it.merchant.lowercase().trim() }
            .map { (_, rows) -> toGroup(rows) }
            // Biggest first: the group worth the most money is the one where a tap
            // changes the summary most, and it is also the easiest to remember.
            .sortedByDescending { it.totalPaise }

        SortUiState(
            loading = false,
            groups = groups,
            doneMerchants = doneSet,
            totalRemaining = unsorted.size,
            startingTotal = maxOf(started, unsorted.size),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SortUiState(),
        )

    private suspend fun toGroup(rows: List<Transaction>): SortGroup {
        val first = rows.first()
        val unknown = first.merchant.equals(UNKNOWN_PAYEE, ignoreCase = true)
        return SortGroup(
            merchant = first.merchant,
            txnIds = rows.map { it.id },
            totalPaise = rows.sumOf { it.amountPaise },
            count = rows.size,
            unknownPayee = unknown,
            suggestions = if (unknown) emptyList() else {
                repository.predictCategories(
                    merchant = first.merchant,
                    amountPaise = first.amountPaise,
                    type = first.type,
                    limit = 2,
                ).filter { it != Category.OTHER }
            },
        )
    }

    /**
     * Apply a category to every row in the group, and remember it for next time.
     *
     * The rule is learned once from the first row — [TransactionRepository.recategorize]
     * already refuses to learn from an unknown payee, which is what stops "Transfers"
     * becoming the default for every future unnamed debit.
     */
    fun assign(group: SortGroup, category: Category) {
        viewModelScope.launch {
            group.txnIds.forEachIndexed { index, id ->
                repository.recategorize(id, category, learn = index == 0)
            }
            done.value = done.value + (group.merchant to category)
        }
    }

    private companion object {
        const val UNKNOWN_PAYEE = "Unknown payee"

        fun monthStart(): Long {
            val today = LocalDate.now(MonthlyAggregator.ZONE)
            return MonthlyAggregator.monthRange(today.year, today.monthValue).first
        }

        fun monthEnd(): Long {
            val today = LocalDate.now(MonthlyAggregator.ZONE)
            return MonthlyAggregator.monthRange(today.year, today.monthValue).last + 1
        }
    }
}

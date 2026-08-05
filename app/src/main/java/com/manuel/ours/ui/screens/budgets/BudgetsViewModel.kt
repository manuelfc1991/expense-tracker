package com.manuel.ours.ui.screens.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.BudgetRepository
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MemberFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class CategoryBudgetProgress(
    val category: Category,
    val spentPaise: Long,
    val limitPaise: Long?,
)

data class BudgetsUiState(
    val overallLimit: Long? = null,
    val spentThisMonth: Long = 0,
    val categoryProgress: List<CategoryBudgetProgress> = emptyList(),
)

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val transactionRepository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val month = LocalDate.now(MonthlyAggregator.ZONE)
    private val range = MonthlyAggregator.monthRange(month.year, month.monthValue)

    val uiState: StateFlow<BudgetsUiState> = combine(
        transactionRepository.observeBetween(range.first, range.last + 1),
        budgetRepository.observeBudgets(),
        prefs.selfUid,
    ) { transactions, budgets, selfUid ->
        // Everything, personal rows included.
        //
        // This ran through the member filter, which keeps my own personal spending and
        // drops everybody else's — so a partner's personal payments left the household's
        // accounts without ever appearing against the household's cap. The over-budget
        // alert has always counted them, which meant the warning could fire quoting a
        // figure larger than the one this screen was showing, with no way to reconcile
        // the two. One cap over one household: everything that leaves counts.
        val visible = transactions
        val overall = budgets.firstOrNull { it.category == null }?.limitPaise
        val limits = budgets.filter { it.category != null }
            .associate { it.category!! to it.limitPaise }

        val spentByCategory = MonthlyAggregator.byCategory(visible)
            .associate { it.category to it.totalPaise }

        BudgetsUiState(
            overallLimit = overall,
            spentThisMonth = MonthlyAggregator.totalSpent(visible),
            // Show every category that either has a limit or saw spend — listing all
            // 15 with zeroes turns a useful screen into a wall.
            categoryProgress = Category.entries
                .filter { it != Category.INCOME }
                .map {
                    CategoryBudgetProgress(
                        category = it,
                        spentPaise = spentByCategory[it] ?: 0L,
                        limitPaise = limits[it],
                    )
                }
                .filter { it.spentPaise > 0 || it.limitPaise != null }
                .sortedByDescending { it.spentPaise },
        )
    }
        // Aggregation runs off the main thread. combine/map inside stateIn execute on
        // the collector's dispatcher, which for viewModelScope is Dispatchers.Main —
        // so summing, grouping and sorting every transaction was happening on the UI
        // thread on every single database write.
        .flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetsUiState(),
    )

    fun setOverall(limitPaise: Long) {
        viewModelScope.launch { budgetRepository.setOverall(limitPaise) }
    }

    fun setCategoryBudget(category: Category, limitPaise: Long) {
        viewModelScope.launch { budgetRepository.setCategoryBudget(category, limitPaise) }
    }

    fun clearBudget(category: Category?) {
        viewModelScope.launch { budgetRepository.clear(category) }
    }

    /**
     * Every cap the household has set, dropped in one go.
     *
     * Only what is actually set is cleared. Looping over [Category.entries] instead would
     * write a tombstone for all fifteen, and every one of those travels to the other
     * phone as a shared rule — fourteen rows saying nothing changed.
     */
    fun resetAllBudgets() {
        viewModelScope.launch {
            val state = uiState.value
            if (state.overallLimit != null) budgetRepository.clear(null)
            state.categoryProgress
                .filter { it.limitPaise != null }
                .forEach { budgetRepository.clear(it.category) }
        }
    }
}

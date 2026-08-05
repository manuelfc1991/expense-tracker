package com.manuel.ours.ui.screens.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One auto-assign rule: any payee containing [pattern] is filed under its category. */
data class Rule(
    val id: Long,
    val pattern: String,
    /** Seeded rules ship with the app; user rules outrank them and can be removed. */
    val userDefined: Boolean,
)

data class CategoryRules(
    val category: Category,
    val rules: List<Rule>,
)

data class RulesUiState(
    val loading: Boolean = true,
    val groups: List<CategoryRules> = emptyList(),
) {
    val total: Int get() = groups.sumOf { it.rules.size }
}

/**
 * Auto-assign rules, grouped by the category they file into.
 *
 * The list is grouped this way round — category first, patterns underneath — because
 * the question a person actually arrives with is "what counts as Food?", not "what
 * happens to the word zomato".
 */
@HiltViewModel
class RulesViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<RulesUiState> = repository.observeMerchantRules()
        .map { entities ->
            val byCategory = entities.groupBy { Category.fromNameOrOther(it.category) }
            RulesUiState(
                loading = false,
                // Every spending category is listed, including the empty ones: an
                // absent section reads as "this cannot be automated", when in fact it
                // just has no rules yet and is the very place you would add one.
                groups = Category.EVERY
                    .map { category ->
                        CategoryRules(
                            category = category,
                            rules = byCategory[category]
                                .orEmpty()
                                .map { Rule(it.id, it.pattern, it.userDefined) }
                                // Yours first, then alphabetical — the ones you wrote
                                // are the ones you came here to check.
                                .sortedWith(
                                    compareByDescending<Rule> { it.userDefined }
                                        .thenBy { it.pattern }
                                ),
                        )
                    },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RulesUiState(),
        )

    fun add(pattern: String, category: Category) {
        viewModelScope.launch { repository.setMerchantRule(pattern, category) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.deleteMerchantRule(id) }
    }
}

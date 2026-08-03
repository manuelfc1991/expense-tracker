package com.manuel.ours.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    /**
     * Queries the single row by id.
     *
     * This used to load every transaction and scan the list for one match, which
     * meant decrypting and mapping the whole table each time the detail screen
     * opened — and again on every write anywhere in the app.
     */
    fun observe(txnId: String): Flow<Transaction?> = repository.observeById(txnId)

    fun recategorize(txnId: String, category: Category) {
        // learn = true writes a merchant rule, so this merchant lands correctly
        // next month without asking again.
        viewModelScope.launch { repository.recategorize(txnId, category, learn = true) }
    }

    fun setSplitType(txnId: String, splitType: SplitType) {
        viewModelScope.launch { repository.setSplitType(txnId, splitType) }
    }

    fun delete(txnId: String) {
        viewModelScope.launch { repository.delete(txnId) }
    }

    /** Marks the row for review so it surfaces in the needs-review inbox. */
    fun flagWrongParse(txnId: String) {
        viewModelScope.launch {
            repository.getById(txnId)?.let {
                repository.updateTransaction(it.copy(needsReview = true))
            }
        }
    }
}

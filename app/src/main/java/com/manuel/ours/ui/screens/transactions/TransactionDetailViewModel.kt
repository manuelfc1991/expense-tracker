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
    private val prefs: com.manuel.ours.data.prefs.AppPrefs,
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

    /** True only for a household owner with developer mode on. */
    val canEditAmount: kotlinx.coroutines.flow.Flow<Boolean> =
        kotlinx.coroutines.flow.combine(
            prefs.householdOwner, prefs.developerMode,
        ) { owner, dev -> owner && dev }

    fun editAmount(txnId: String, amountPaise: Long) {
        viewModelScope.launch { repository.editAmount(txnId, amountPaise) }
    }

    /**
     * Renames the payee, and optionally teaches the name to the account behind it.
     *
     * @param remember when true the name is attached to the destination account, so
     *   every past and future payment to it carries the name. That is the only way a
     *   correction survives: the bank never names these payees, so the next payment
     *   arrives as a placeholder again with nothing to match on.
     */
    fun rename(txnId: String, merchant: String, tail: String?, remember: Boolean) {
        viewModelScope.launch {
            if (remember && !tail.isNullOrBlank()) repository.nameAccount(tail, merchant)
            else repository.rename(txnId, merchant)
        }
    }

    fun setNote(txnId: String, note: String) {
        viewModelScope.launch { repository.setNote(txnId, note) }
    }

    fun setSplitType(txnId: String, splitType: SplitType) {
        viewModelScope.launch { repository.setSplitType(txnId, splitType) }
    }

    fun delete(txnId: String) {
        viewModelScope.launch { repository.deleteOrRequest(txnId) }
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

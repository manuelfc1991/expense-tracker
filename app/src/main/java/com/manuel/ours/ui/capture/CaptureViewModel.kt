package com.manuel.ours.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The payment being captured, with its three guesses already resolved. */
data class CaptureState(val txn: Transaction, val suggestions: List<Category>)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    /**
     * Emits null when the row is gone, which the popup treats as "close yourself".
     *
     * That is not a defensive nicety: the same payment can be categorised from the
     * notification's buttons while the popup is on screen, and a delete can arrive from
     * the other phone at any moment. A popup sitting over another app showing a row that
     * no longer exists is worse than no popup.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(txnId: String): Flow<CaptureState?> =
        repository.observeById(txnId).mapLatest { txn ->
            txn?.let {
                CaptureState(
                    txn = it,
                    suggestions = repository.predictCategories(
                        it.merchant, it.amountPaise, it.type,
                    ).filter { c -> c != Category.OTHER },
                )
            }
        }

    fun categorize(txnId: String, category: Category) {
        viewModelScope.launch { repository.recategorize(txnId, category, learn = true) }
    }

    fun rename(txnId: String, name: String, tail: String?, remember: Boolean) {
        viewModelScope.launch {
            if (remember && !tail.isNullOrBlank()) repository.nameAccount(tail, name)
            else repository.rename(txnId, name)
        }
    }

    fun setNote(txnId: String, note: String) {
        viewModelScope.launch { repository.setNote(txnId, note) }
    }
}

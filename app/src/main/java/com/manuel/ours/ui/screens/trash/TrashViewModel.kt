package com.manuel.ours.ui.screens.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val items: StateFlow<List<Transaction>> = repository.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Read once, when the screen opens, and used for every "days left" on it.
     *
     * Calling the clock per row would let two entries deleted in the same second be
     * captioned differently if the list happened to be composed across a midnight, and
     * would make the whole screen re-read time on every recomposition for no gain.
     */
    val now: Long = System.currentTimeMillis()

    private val _restored = MutableStateFlow<String?>(null)
    val restored: StateFlow<String?> = _restored

    fun restore(txn: Transaction) {
        viewModelScope.launch {
            repository.restore(txn.id)
            _restored.value = txn.merchant
        }
    }

    fun clearRestored() { _restored.value = null }
}

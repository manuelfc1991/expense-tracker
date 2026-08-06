package com.manuel.ours.ui.screens.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    data class UiState(
        val items: List<Transaction> = emptyList(),
        val selected: Set<String> = emptySet(),
    ) {
        val selectionMode: Boolean get() = selected.isNotEmpty()
    }

    private val selection = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<UiState> =
        combine(repository.observeTrash(), selection) { items, chosen ->
            // Anything that has left the bin leaves the selection with it, or Put back
            // would be aimed at ids that are no longer on screen.
            UiState(items, chosen intersect items.map { it.id }.toSet())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /**
     * Read once, when the screen opens, and used for every "days left" on it.
     *
     * Calling the clock per row would let two entries deleted in the same second caption
     * themselves differently if the list were composed either side of a midnight, and
     * would make the whole screen re-read time on every recomposition for no gain.
     */
    val now: Long = System.currentTimeMillis()

    private val _restored = MutableStateFlow<String?>(null)
    val restored: StateFlow<String?> = _restored

    fun toggle(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { selection.value = emptySet() }

    fun restoreSelected() {
        val ids = uiState.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val names = uiState.value.items.filter { it.id in ids }.map { it.merchant }
            ids.forEach { repository.restore(it) }
            selection.value = emptySet()
            _restored.value = when (ids.size) {
                1 -> "${names.firstOrNull() ?: "That entry"} is back"
                else -> "${ids.size} entries are back"
            }
        }
    }

    fun clearRestored() { _restored.value = null }
}

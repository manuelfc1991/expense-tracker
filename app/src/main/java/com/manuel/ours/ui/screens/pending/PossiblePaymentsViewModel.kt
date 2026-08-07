package com.manuel.ours.ui.screens.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.db.PendingSenderEntity
import com.manuel.ours.data.repo.PendingSenderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PossiblePaymentsUiState(
    val loading: Boolean = true,
    val senders: List<PendingSenderEntity> = emptyList(),
)

@HiltViewModel
class PossiblePaymentsViewModel @Inject constructor(
    private val repository: PendingSenderRepository,
) : ViewModel() {

    val uiState: StateFlow<PossiblePaymentsUiState> = repository.observeAll()
        .map { PossiblePaymentsUiState(loading = false, senders = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PossiblePaymentsUiState(),
        )

    /** Yes, my bank. The header is the name until a message tells us a better one. */
    fun confirm(header: String) {
        viewModelScope.launch { repository.confirm(header, header) }
    }

    fun dismiss(header: String) {
        viewModelScope.launch { repository.dismiss(header) }
    }
}

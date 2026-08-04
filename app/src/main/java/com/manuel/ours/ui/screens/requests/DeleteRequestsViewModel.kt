package com.manuel.ours.ui.screens.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeleteRequest(
    val transaction: Transaction,
    /** Who asked, by display name — a uid tells the owner nothing. */
    val askedBy: String,
)

data class DeleteRequestsUiState(
    val requests: List<DeleteRequest> = emptyList(),
    val isOwner: Boolean = false,
)

@HiltViewModel
class DeleteRequestsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    prefs: AppPrefs,
) : ViewModel() {

    val uiState: StateFlow<DeleteRequestsUiState> = combine(
        repository.observeDeleteRequests(),
        repository.observeAll(),
        prefs.householdOwner,
    ) { pending, all, isOwner ->
        // The requester's name comes from the rows they own, because nothing else in
        // the app maps a uid to a person — and "asked by 4f80fa86" helps nobody.
        val names = MonthlyAggregator.peopleIn(all, selfUid = "")
            .associate { it.uid to it.displayName }

        DeleteRequestsUiState(
            requests = pending.map {
                DeleteRequest(
                    transaction = it,
                    askedBy = names[it.deleteRequestedBy] ?: "someone in the household",
                )
            },
            isOwner = isOwner,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeleteRequestsUiState(),
        )

    fun approve(txnId: String) {
        viewModelScope.launch { repository.approveDelete(txnId) }
    }

    fun reject(txnId: String) {
        viewModelScope.launch { repository.rejectDelete(txnId) }
    }
}

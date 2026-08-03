package com.manuel.ours.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TransactionGroup(
    val label: String,
    val totalPaise: Long,
    val transactions: List<Transaction>,
)

data class TransactionsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val memberFilter: MemberFilter = MemberFilter.BOTH,
    val categoryFilter: Category? = null,
    val groups: List<TransactionGroup> = emptyList(),
    /** Set briefly after a swipe-delete so the UI can offer Undo. */
    val lastDeletedId: String? = null,
    val hasPartner: Boolean = false,
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val memberFilter = MutableStateFlow(MemberFilter.BOTH)
    private val categoryFilter = MutableStateFlow<Category?>(null)
    private val lastDeleted = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TransactionsUiState> = combine(
        repository.observeAll(),
        query,
        memberFilter,
        categoryFilter,
        combine(prefs.selfUid, lastDeleted) { uid, deleted -> uid.orEmpty() to deleted },
    ) { all, searchText, member, category, selfAndDeleted ->
        val selfUid = selfAndDeleted.first
        val deletedId = selfAndDeleted.second
        val filtered = MonthlyAggregator
            .applyFilter(all, member, selfUid)
            .filter { category == null || it.category == category }
            .filter { txn ->
                searchText.isBlank() ||
                    txn.merchant.contains(searchText, ignoreCase = true) ||
                    txn.category.label.contains(searchText, ignoreCase = true) ||
                    txn.note?.contains(searchText, ignoreCase = true) == true
            }

        val hasPartner = all.any {
            selfUid.isNotEmpty() && it.ownerUid != selfUid && it.ownerUid != "local"
        }

        TransactionsUiState(
            loading = false,
            hasPartner = hasPartner,
            query = searchText,
            memberFilter = member,
            categoryFilter = category,
            groups = groupByDay(filtered),
            lastDeletedId = deletedId,
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
        initialValue = TransactionsUiState(),
    )

    private fun groupByDay(transactions: List<Transaction>): List<TransactionGroup> {
        val today = LocalDate.now(MonthlyAggregator.ZONE)
        val yesterday = today.minusDays(1)

        return transactions
            .sortedByDescending { it.occurredAt }
            .groupBy {
                Instant.ofEpochMilli(it.occurredAt).atZone(MonthlyAggregator.ZONE).toLocalDate()
            }
            .map { (date, list) ->
                TransactionGroup(
                    label = when (date) {
                        today -> "Today"
                        yesterday -> "Yesterday"
                        else -> date.format(dayFormatter)
                    },
                    totalPaise = list.filter { it.type == TxnType.DEBIT }.sumOf { it.amountPaise },
                    transactions = list,
                )
            }
    }

    fun setQuery(value: String) { query.value = value }
    fun setMemberFilter(value: MemberFilter) { memberFilter.value = value }
    fun setCategoryFilter(value: Category?) { categoryFilter.value = value }

    fun recategorize(txnId: String, category: Category) {
        viewModelScope.launch { repository.recategorize(txnId, category) }
    }

    fun setSplitType(txnId: String, splitType: SplitType) {
        viewModelScope.launch { repository.setSplitType(txnId, splitType) }
    }

    fun delete(txnId: String) {
        viewModelScope.launch { repository.delete(txnId) }
    }

    /**
     * Deletes and arms the Undo snackbar.
     *
     * The delete is real, not deferred — a tombstone is written and syncs immediately.
     * Undo restores by writing a *new* event with a higher Lamport value, which is
     * what makes it survive the round trip to the other phone: a delete that has
     * already replicated cannot be un-sent, only superseded.
     */
    fun deleteWithUndo(txnId: String) {
        viewModelScope.launch {
            repository.delete(txnId)
            lastDeleted.value = txnId
        }
    }

    fun undoDelete(txnId: String) {
        viewModelScope.launch {
            repository.restore(txnId)
            lastDeleted.value = null
        }
    }

    fun clearUndo() { lastDeleted.value = null }

    companion object {
        private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
    }
}

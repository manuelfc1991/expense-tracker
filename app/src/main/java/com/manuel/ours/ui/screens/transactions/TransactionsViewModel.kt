package com.manuel.ours.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.HouseholdMember
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
    val memberFilter: MemberFilter = MemberFilter.Everyone,
    val categoryFilter: Category? = null,
    val groups: List<TransactionGroup> = emptyList(),
    /** Set briefly after a swipe-delete so the UI can offer Undo. */
    val lastDeletedId: String? = null,
    val hasPartner: Boolean = false,
    /** Everyone with a row, self first — one filter chip each. */
    val people: List<HouseholdMember> = emptyList(),
    /** Ids picked for a bulk action. Empty means the list is in its normal state. */
    val selected: Set<String> = emptySet(),
    /** Set briefly after a bulk delete so the UI can offer one Undo for all of them. */
    val lastBulkDeleted: List<String> = emptyList(),
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val memberFilter = MutableStateFlow<MemberFilter>(MemberFilter.Everyone)
    private val categoryFilter = MutableStateFlow<Category?>(null)
    private val lastDeleted = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val lastBulkDeleted = MutableStateFlow<List<String>>(emptyList())

    /** Everything that is not a filter, bundled so the outer combine stays within five. */
    private data class Aux(
        val selfUid: String,
        val deletedId: String?,
        val selected: Set<String>,
        val bulkDeleted: List<String>,
    )

    val uiState: StateFlow<TransactionsUiState> = combine(
        repository.observeAll(),
        query,
        memberFilter,
        categoryFilter,
        combine(
            prefs.selfUid, lastDeleted, selection, lastBulkDeleted,
        ) { uid, deleted, selected, bulkDeleted ->
            Aux(uid.orEmpty(), deleted, selected, bulkDeleted)
        },
    ) { all, searchText, member, category, aux ->
        val selfUid = aux.selfUid
        val deletedId = aux.deletedId
        val filtered = MonthlyAggregator
            .applyFilter(all, member, selfUid)
            .filter { category == null || it.category == category }
            .filter { txn ->
                searchText.isBlank() ||
                    txn.merchant.contains(searchText, ignoreCase = true) ||
                    txn.category.label.contains(searchText, ignoreCase = true) ||
                    txn.note?.contains(searchText, ignoreCase = true) == true
            }

        val people = MonthlyAggregator.peopleIn(all, selfUid)
        val hasPartner = people.count { !it.isSelf } > 0

        TransactionsUiState(
            loading = false,
            hasPartner = hasPartner,
            people = people,
            query = searchText,
            memberFilter = member,
            categoryFilter = category,
            groups = groupByDay(filtered),
            lastDeletedId = deletedId,
            // Narrowed to what is actually on screen. Selecting three rows, then
            // typing a search that hides two of them, must not leave a bulk delete
            // armed against rows the reader can no longer see — "3 selected" has to
            // mean three visible rows.
            selected = aux.selected intersect filtered.map { it.id }.toSet(),
            lastBulkDeleted = aux.bulkDeleted,
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

    // ─── Selection ──────────────────────────────────────────────────────────

    fun toggleSelected(txnId: String) {
        val current = selection.value
        selection.value =
            if (txnId in current) current - txnId else current + txnId
    }

    fun clearSelection() { selection.value = emptySet() }

    /** Every row currently on screen — after search and filters, not the whole ledger. */
    fun selectAllVisible() {
        selection.value = uiState.value.groups.flatMap { group -> group.transactions }
            .map { it.id }.toSet()
    }

    /**
     * Apply one category to everything selected, without learning a rule.
     *
     * [Sort][com.manuel.ours.ui.screens.sort.SortViewModel] learns, because there the
     * group *is* one merchant and the choice genuinely says "this shop is Groceries".
     * A hand-made selection can span a dozen unrelated payees, so learning from it would
     * teach the app that whichever merchant happened to be first now explains all of
     * them — and that rule would then file every future payment from that shop wrongly.
     */
    fun recategorizeSelected(category: Category) {
        // uiState, not `selection` — the state holds the set narrowed to visible rows,
        // and acting on anything wider would touch rows the reader cannot see.
        val ids = uiState.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> repository.recategorize(id, category, learn = false) }
            selection.value = emptySet()
        }
    }

    fun deleteSelectedWithUndo() {
        val ids = uiState.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> repository.delete(id) }
            selection.value = emptySet()
            lastBulkDeleted.value = ids
        }
    }

    fun undoBulkDelete() {
        val ids = lastBulkDeleted.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> repository.restore(id) }
            lastBulkDeleted.value = emptyList()
        }
    }

    fun clearBulkUndo() { lastBulkDeleted.value = emptyList() }

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

package com.manuel.ours.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.core.OursZone
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryFilter
import com.manuel.ours.domain.model.HouseholdMember
import com.manuel.ours.domain.model.isUntagged
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val categoryFilter: CategoryFilter = CategoryFilter.All,
    val groups: List<TransactionGroup> = emptyList(),
    /**
     * How many rows each category holds *before* the category filter is applied — so the
     * chips keep saying what the month contains even while one of them is chosen.
     *
     * Only non-empty categories appear. Together with [untaggedCount] these sum exactly
     * to [baseCount], which is what lets the row be read as a breakdown rather than as a
     * menu that happens to have numbers on it.
     */
    val counts: Map<Category, Int> = emptyMap(),
    val untaggedCount: Int = 0,
    /** Rows matching the member filter and the search, ignoring the category filter. */
    val baseCount: Int = 0,
    /** Total of what is on screen now — shown beside an active filter. */
    val filteredTotalPaise: Long = 0L,
    val hasPartner: Boolean = false,
    /** Everyone with a row, self first — one filter chip each. */
    val people: List<HouseholdMember> = emptyList(),
    /** Ids picked for a bulk action. Empty means the list is in its normal state. */
    val selected: Set<String> = emptySet(),
    /** Set briefly after a bulk delete so the UI can offer one Undo for all of them. */
    val lastBulkDeleted: List<String> = emptyList(),
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()

    val shownCount: Int get() = groups.sumOf { it.transactions.size }

    val filtering: Boolean get() = categoryFilter != CategoryFilter.All

    /**
     * The chips, in the order they are drawn: untagged first, then by size.
     *
     * Most-used first rather than enum order, because the one you want is nearly always
     * the one with the most rows, and enum order put Transfers thirteenth. Ties break on
     * the label so the row does not reshuffle itself between two equal categories every
     * time a transaction arrives.
     */
    val chips: List<Pair<CategoryFilter, Int>>
        get() = buildList {
            if (untaggedCount > 0) add(CategoryFilter.Untagged to untaggedCount)
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<Category, Int>> { it.value }
                    .thenBy { it.key.label })
                .forEach { (category, count) -> add(CategoryFilter.Of(category) to count) }
        }
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val memberFilter = MutableStateFlow<MemberFilter>(MemberFilter.Everyone)
    private val categoryFilter = MutableStateFlow<CategoryFilter>(CategoryFilter.All)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val lastBulkDeleted = MutableStateFlow<List<String>>(emptyList())

    /**
     * How many deletes became requests instead of removals, waiting to be told about.
     *
     * A member's delete deliberately leaves the row on screen until the owner agrees.
     * Without a word saying so that is indistinguishable from a tap that did nothing —
     * which is exactly how it read on the partner's phone: press delete, nothing moves,
     * no message, and no way to know the owner had been asked.
     *
     * Kept outside [uiState] because that combine is already at its five-flow limit.
     */
    private val requestedDeletes = MutableStateFlow(0)
    val deleteRequestNotice: StateFlow<Int> = requestedDeletes.asStateFlow()

    /**
     * Whether this phone's deletes go through or turn into requests.
     *
     * The confirmation asks a different question of each, and it has to ask it before the
     * tap — after it, [deleteRequestNotice] is already saying the same thing too late.
     * Kept out of [uiState] because that combine is at its five-flow limit.
     */
    val isOwner: StateFlow<Boolean> = prefs.householdOwner
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Everything that is not a filter, bundled so the outer combine stays within five. */
    private data class Aux(
        val selfUid: String,
        val selected: Set<String>,
        val bulkDeleted: List<String>,
    )

    val uiState: StateFlow<TransactionsUiState> = combine(
        repository.observeAll(),
        query,
        memberFilter,
        categoryFilter,
        combine(
            prefs.selfUid, selection, lastBulkDeleted,
        ) { uid, selected, bulkDeleted ->
            Aux(uid.orEmpty(), selected, bulkDeleted)
        },
    ) { all, searchText, member, category, aux ->
        val selfUid = aux.selfUid

        // Everything the search and the member filter allow, before the category filter.
        // The chips are counted against *this*, not against the visible rows — a chip row
        // recomputed from its own output would collapse to a single chip the moment you
        // tapped one, and then there would be no way back to the others.
        val base = MonthlyAggregator
            .applyFilter(all, member, selfUid)
            .filter { txn ->
                searchText.isBlank() ||
                    txn.merchant.contains(searchText, ignoreCase = true) ||
                    txn.category.label.contains(searchText, ignoreCase = true) ||
                    txn.note?.contains(searchText, ignoreCase = true) == true
            }

        val (untagged, tagged) = base.partition { it.isUntagged }
        val counts = tagged.groupingBy { it.category }.eachCount()

        val filtered = when (category) {
            CategoryFilter.All -> base
            CategoryFilter.Untagged -> untagged
            is CategoryFilter.Of -> tagged.filter { it.category == category.category }
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
            counts = counts,
            untaggedCount = untagged.size,
            baseCount = base.size,
            // Every row, not only debits: filtered to Income, a debit-only total would
            // read ₹0 beside three visible entries.
            filteredTotalPaise = filtered.sumOf { it.amountPaise },
            groups = groupByDay(filtered),
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
    fun setCategoryFilter(value: CategoryFilter) { categoryFilter.value = value }

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
            val removed = ids.filter { repository.deleteOrRequest(it) }
            selection.value = emptySet()
            // Only what actually went is undoable; the rest are now requests.
            lastBulkDeleted.value = removed
            val asked = ids.size - removed.size
            if (asked > 0) requestedDeletes.value += asked
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
        viewModelScope.launch {
            if (!repository.deleteOrRequest(txnId)) requestedDeletes.value += 1
        }
    }

    /** Acknowledged — the notice has been shown and should not fire again on recompose. */
    fun clearDeleteRequestNotice() { requestedDeletes.value = 0 }

    companion object {
        private val dayFormatter = OursZone.dayRule
    }
}

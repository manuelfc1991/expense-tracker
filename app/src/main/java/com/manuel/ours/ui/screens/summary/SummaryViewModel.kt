package com.manuel.ours.ui.screens.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.BudgetRepository
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.RecurringDetector
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.data.repo.HouseholdRepository
import com.manuel.ours.domain.model.Member
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SummaryUiState(
    val loading: Boolean = true,
    val yearMonth: YearMonth = YearMonth.now(MonthlyAggregator.ZONE),
    val filter: MemberFilter = MemberFilter.Everyone,
    val summary: MonthSummary? = null,
    val transactions: List<Transaction> = emptyList(),
    /** Charges that repeat, inferred from history rather than declared anywhere. */
    val recurring: List<RecurringCharge> = emptyList(),
    /**
     * What each account was last known to hold. Drawn from every transaction the
     * household has, not from the month on screen — an account nobody touched in
     * August still holds whatever July left in it.
     */
    val balances: List<AccountBalance> = emptyList(),
    /**
     * Every rupee that left the household's accounts this month: spending, money put
     * aside, and money moved between our own accounts.
     *
     * Deliberately not the same figure as spending, and deliberately shown next to it.
     * Spending answers "what did we consume"; this answers "what left the account",
     * and the difference between them is the savings and the transfers — money that is
     * gone from the account and still ours.
     */
    val leftAccountsPaise: Long = 0L,
    /**
     * Whether this phone claims to own the household, which is what gates the balances.
     *
     * A curtain, not a lock — the flag is self-declared in Settings and the figures still
     * cross the sync either way. It keeps account balances off a screen the rest of the
     * household reads over your shoulder; it does not keep them from someone who goes
     * looking.
     */
    val isHouseholdOwner: Boolean = false,
    /**
     * The budget and the balances in one figure, for the month being lived through.
     *
     * Null for any month but the current one: balances are what the banks hold today, and
     * setting today's money against a budget that ran out in June answers nothing.
     */
    val affordability: Affordability? = null,
    /**
     * The household, so an account can be handed to a person by name.
     *
     * Needed on this screen only to fill the owner picker; the grouping itself reads the
     * owner recorded on each account and never has to look a member up.
     */
    val members: List<Member> = emptyList(),
    /** Which of [members] is this phone, so their group sorts to the top. */
    val selfUid: String = "",
) {
    /** What the recurring charges add up to per month, cadences reconciled. */
    val committedMonthlyPaise: Long get() = recurring.sumOf { it.monthlyEquivalentPaise }
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val householdRepository: HouseholdRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    /** What the whole screen is scoped to: a month, a member, a viewer. */
    private data class Scope(
        val yearMonth: YearMonth,
        val filter: MemberFilter,
        val selfUid: String,
        val owner: Boolean,
    )

    private val yearMonth = MutableStateFlow(YearMonth.now(MonthlyAggregator.ZONE))
    private val filter = MutableStateFlow<MemberFilter>(MemberFilter.Everyone)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SummaryUiState> =
        combine(
            yearMonth, filter, prefs.selfUid, prefs.householdOwner,
        ) { ym, memberFilter, selfUid, owner ->
            Scope(ym, memberFilter, selfUid.orEmpty(), owner)
        }.flatMapLatest { (ym, memberFilter, selfUid, owner) ->
            val current = MonthlyAggregator.monthRange(ym.year, ym.monthValue)
            val prior = ym.minusMonths(1)
            val priorRange = MonthlyAggregator.monthRange(prior.year, prior.monthValue)

            // A pattern needs history to be visible at all: three monthly sightings is
            // four months of data, and a yearly charge needs two years before it can be
            // told apart from a coincidence. The window is anchored to the month being
            // viewed rather than to today, so stepping back a year shows what was
            // recurring *then* instead of what recurs now.
            val lookback = ym.minusMonths(RecurringDetector.LOOKBACK_MONTHS)
            val lookbackStart =
                MonthlyAggregator.monthRange(lookback.year, lookback.monthValue).first

            kotlinx.coroutines.flow.combine(
                repository.observeBetween(
                    minOf(lookbackStart, priorRange.first), current.last + 1,
                ),
                repository.observeBalances(selfUid, owner),
                budgetRepository.observeOverall(),
                householdRepository.observeMembers(),
            ) { all, balances, budget, members ->
                val currentTxns = MonthlyAggregator.applyFilter(
                    all.filter { txn -> txn.occurredAt in current }, memberFilter, selfUid,
                )
                val priorTxns = MonthlyAggregator.applyFilter(
                    all.filter { txn -> txn.occurredAt in priorRange }, memberFilter, selfUid,
                )
                // Detected over the whole window, but through the same member filter —
                // "Aarav's commitments" has to mean Aarav's. This drives the Committed
                // *list*, which is a per-member view and should follow the chip.
                val recurring = RecurringDetector.detect(
                    MonthlyAggregator.applyFilter(all, memberFilter, selfUid)
                )

                // Unfiltered, for the affordability figure only.
                //
                // That figure is measured against household-wide spend and a household-wide
                // cap, so feeding it per-member commitments made a household number move
                // when you tapped a member chip: selecting "Me" dropped a partner's ₹15,000
                // commitment and raised what the household could apparently afford by
                // ₹15,000. The budget is one cap over one household — narrowing the view
                // must never change it.
                val householdRecurring = RecurringDetector.detect(all)

                // Household-wide and for the month on screen, matching the budget it is
                // measured against. Stepping back to July asks what was affordable in
                // July, so the spend has to come from July too.
                val householdSpent = MonthlyAggregator.totalSpent(
                    all.filter { txn -> txn.occurredAt in current }
                )

                // Only a live month can have money still to come out of it. Looking back
                // at a finished month, everything that was going to happen has, and
                // charging the household again for a subscription it already paid would
                // make every past month look poorer than it was.
                val viewingNow = ym == YearMonth.now(MonthlyAggregator.ZONE)
                val committed = if (viewingNow) {
                    MonthlyAggregator.committedRemaining(householdRecurring)
                } else 0L

                SummaryUiState(
                    loading = false,
                    yearMonth = ym,
                    filter = memberFilter,
                    summary = MonthlyAggregator.summarize(
                        ym.year, ym.monthValue, currentTxns, priorTxns,
                    ),
                    transactions = currentTxns,
                    recurring = recurring,
                    balances = balances,
                    leftAccountsPaise = MonthlyAggregator.totalDebited(currentTxns),
                    isHouseholdOwner = owner,
                    // Balances are what the accounts hold *now*, so this only means
                    // anything about the month actually being lived through.
                    affordability = if (viewingNow) {
                        affordability(
                            budgetPaise = budget,
                            householdSpentPaise = householdSpent,
                            balances = balances,
                            committedRemainingPaise = committed,
                            partialView = !owner,
                        )
                    } else null,
                    members = members,
                    selfUid = selfUid,
                )
            }
        }
        // Aggregation runs off the main thread. combine/map inside stateIn execute on
        // the collector's dispatcher, which for viewModelScope is Dispatchers.Main —
        // so summing, grouping and sorting every transaction was happening on the UI
        // thread on every single database write.
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SummaryUiState(),
        )

    /** Records what somebody says is in an account the bank never quotes a balance for. */
    /** [paise] null forgets the typed figure and hands the account back to the bank's. */
    fun setAccountBalance(key: String, paise: Long?, bank: String?) {
        viewModelScope.launch { repository.setAccountBalance(key, paise, bank) }
    }

    /** Records whose an account is. A null [uid] hands it back to Shared. */
    fun setAccountOwner(key: String, uid: String?, displayName: String?) {
        viewModelScope.launch { repository.setAccountOwner(key, uid, displayName) }
    }

    fun setAccountMinimum(key: String, paise: Long) {
        viewModelScope.launch { repository.setAccountMinimum(key, paise) }
    }

    /** Declares an account to be a credit card — its balance is owed, not held. */
    fun setCard(key: String, limitPaise: Long?, dueDay: Int?) {
        viewModelScope.launch { repository.setCard(key, limitPaise, dueDay) }
    }

    fun setMonth(value: YearMonth) {
        // Never let the pager run into the future — an empty "next month" chart
        // looks like data loss.
        val now = YearMonth.from(LocalDate.now(MonthlyAggregator.ZONE))
        yearMonth.value = if (value.isAfter(now)) now else value
    }

    private companion object {
        /**
         * Two years. Enough for a yearly charge to be seen three times, which is the
         * fewest that can be told apart from a coincidence.
         */
    }

    fun previousMonth() = setMonth(yearMonth.value.minusMonths(1))
    fun nextMonth() = setMonth(yearMonth.value.plusMonths(1))
    fun setFilter(value: MemberFilter) { filter.value = value }
}

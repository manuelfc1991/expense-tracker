package com.manuel.ours.domain

import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.CategoryTotal
import com.manuel.ours.domain.model.DayTotal
import com.manuel.ours.domain.model.Insight
import com.manuel.ours.domain.model.HouseholdMember
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.MemberTotal
import com.manuel.ours.domain.model.MerchantTotal
import com.manuel.ours.domain.model.MoneyFlow
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure functions over a transaction list — no Android, no IO, trivially testable. */
object MonthlyAggregator {

    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    fun monthRange(year: Int, month: Int): LongRange {
        val ym = YearMonth.of(year, month)
        val start = ym.atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        return start until end
    }

    fun dayOfMonth(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE).dayOfMonth

    /** Applies the Both / Me / Partner toggle and drops PERSONAL rows that aren't mine. */
    fun applyFilter(
        transactions: List<Transaction>,
        filter: MemberFilter,
        selfUid: String,
    ): List<Transaction> = transactions.filter { txn ->
        when (filter) {
            // Everyone's shared spending counts toward the household, whether it came
            // from a partner or a child. Someone else's PERSONAL rows stay theirs — but
            // I still see my own, because hiding my own spending from me is absurd.
            MemberFilter.Everyone ->
                txn.splitType == SplitType.SHARED || txn.ownerUid == selfUid
            // One person means everything of theirs, personal included. Picking a name
            // is asking "what did they spend", not "what did they contribute".
            is MemberFilter.Person -> txn.ownerUid == filter.uid
        }
    }

    /**
     * Money actually spent. Excludes [Category.NON_SPEND] — chiefly credit-card bill
     * payments, which settle purchases already counted individually. Use
     * [totalDebited] when you want the raw sum of every debit.
     */
    fun totalSpent(transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .sumOf { it.amountPaise }

    /** Every debit, including transfers and card bill payments. */
    fun totalDebited(transactions: List<Transaction>): Long =
        transactions.filter { it.type == TxnType.DEBIT }.sumOf { it.amountPaise }

    /**
     * Debits that count as spending. Every chart funnels through this so the donut,
     * the daily bars, the member split and the headline can never disagree — a chart
     * that adds up to a different number than the hero card destroys trust in both.
     */
    fun spendable(transactions: List<Transaction>): List<Transaction> =
        transactions.filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }

    /**
     * Money you put aside — FDs, RDs, SIPs. Reported next to spending rather than
     * inside it, so a month where you saved hard doesn't read as a month where you
     * overspent.
     */
    fun totalSaved(transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TxnType.DEBIT && it.category.flow == MoneyFlow.SAVING }
            .sumOf { it.amountPaise }

    /** What [totalSpent] left out, so the difference can be shown rather than hidden. */
    fun excludedFromSpend(transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TxnType.DEBIT && !it.category.countsAsSpending }
            .sumOf { it.amountPaise }

    /**
     * Real income. Excludes credits that are only money coming back — an FD maturing
     * or a mutual fund redemption isn't earnings, and counting it would show a
     * spectacular "income" month every time a deposit matures.
     */
    fun totalReceived(transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TxnType.CREDIT && it.category.flow == MoneyFlow.INCOMING }
            .sumOf { it.amountPaise }

    /**
     * What each account was last known to hold, straight from the bank.
     *
     * Not a running total. The app never sees deposits it was not told about — cash
     * paid in at a branch, interest credited silently — so a balance computed by adding
     * up transactions would drift from the truth and never come back. This takes the
     * most recent message that quoted a balance for each account and reports that,
     * along with when it was said, so a stale figure is visibly stale rather than
     * quietly wrong.
     *
     * Fed from every transaction the household has, not from the month being viewed:
     * an account nobody touched in August still has whatever July left in it.
     */
    fun accountBalances(transactions: List<Transaction>): List<AccountBalance> =
        transactions
            // A balance with no account number is still a balance. Some banks name the
            // account in every message and some name it in none — Kerala Gramin quotes
            // one on a credit and omits it on a transfer — so an account known only by
            // its bank is grouped under that, rather than dropped for lacking an id.
            .filter { it.balancePaise != null }
            .groupBy { it.accountTail?.takeIf(String::isNotBlank) ?: it.bank ?: "Account" }
            .map { (_, rows) ->
                val latest = rows.maxBy { it.occurredAt }
                AccountBalance(
                    accountTail = latest.accountTail?.takeIf(String::isNotBlank),
                    bank = latest.bank,
                    balancePaise = latest.balancePaise!!,
                    asOf = latest.occurredAt,
                    ownerName = latest.ownerName,
                )
            }
            .sortedByDescending { it.balancePaise }

    fun byCategory(
        current: List<Transaction>,
        previous: List<Transaction> = emptyList(),
    ): List<CategoryTotal> {
        val previousByCategory = previous
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }

        return current
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .groupBy { it.category }
            .map { (category, list) ->
                CategoryTotal(
                    category = category,
                    totalPaise = list.sumOf { it.amountPaise },
                    txnCount = list.size,
                    previousPaise = previousByCategory[category] ?: 0L,
                )
            }
            .sortedByDescending { it.totalPaise }
    }

    /**
     * Everyone who owns a row here, self first and the rest alphabetical.
     *
     * Derived from the transactions rather than the member table because that is what
     * the filter chips need to be true about: a member with nothing recorded has
     * nothing to filter to, and a person whose rows arrived by sync should appear even
     * if they were never added locally.
     */
    fun peopleIn(
        transactions: List<Transaction>,
        selfUid: String,
        placeholderUid: String = "local",
    ): List<HouseholdMember> = transactions
        .asSequence()
        .filter { it.ownerUid.isNotBlank() }
        // The placeholder is *me before I had an id*, never a second person. Treating
        // it as one is what used to render "Both · Me · Me".
        .filter { it.ownerUid != placeholderUid || it.ownerUid == selfUid }
        .map { it.ownerUid to it.ownerName }
        .distinctBy { it.first }
        .map { (uid, name) -> HouseholdMember(uid, name, isSelf = uid == selfUid) }
        .sortedWith(compareByDescending<HouseholdMember> { it.isSelf }.thenBy { it.displayName })
        .toList()

    fun byMember(transactions: List<Transaction>): List<MemberTotal> =
        spendable(transactions)
            .groupBy { it.ownerUid }
            .map { (uid, list) ->
                MemberTotal(
                    uid = uid,
                    displayName = list.first().ownerName,
                    totalPaise = list.sumOf { it.amountPaise },
                )
            }
            .sortedByDescending { it.totalPaise }

    fun byDay(transactions: List<Transaction>, year: Int, month: Int): List<DayTotal> {
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        val sums = spendable(transactions)
            .groupBy { dayOfMonth(it.occurredAt) }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }

        // Every day is present, including zero-spend days — a bar chart with gaps
        // misreads as missing data rather than a day you didn't spend.
        return (1..daysInMonth).map { day -> DayTotal(day, sums[day] ?: 0L) }
    }

    fun topMerchants(transactions: List<Transaction>, limit: Int = 5): List<MerchantTotal> =
        spendable(transactions)
            .groupBy { it.merchant.lowercase() }
            .map { (_, list) ->
                MerchantTotal(
                    merchant = list.first().merchant,
                    totalPaise = list.sumOf { it.amountPaise },
                    txnCount = list.size,
                )
            }
            .sortedByDescending { it.totalPaise }
            .take(limit)

    fun biggestExpense(transactions: List<Transaction>): Transaction? =
        spendable(transactions).maxByOrNull { it.amountPaise }

    fun summarize(
        year: Int,
        month: Int,
        current: List<Transaction>,
        previous: List<Transaction>,
    ): MonthSummary {
        val categories = byCategory(current, previous)
        return MonthSummary(
            year = year,
            month = month,
            totalSpentPaise = totalSpent(current),
            totalReceivedPaise = totalReceived(current),
            previousMonthSpentPaise = totalSpent(previous),
            byCategory = categories,
            byMember = byMember(current),
            byDay = byDay(current, year, month),
            topMerchants = topMerchants(current),
            biggestExpense = biggestExpense(current),
            insights = buildInsights(categories, totalSpent(current), totalSpent(previous)),
            excluded = excludedBreakdown(current),
            totalSavedPaise = totalSaved(current),
        )
    }

    /**
     * Plain-language observations. Only surfaces changes big enough to act on —
     * "you spent ₹12 more on tea" is noise, and noisy insights get ignored wholesale.
     */
    fun buildInsights(
        categories: List<CategoryTotal>,
        totalNow: Long,
        totalPrevious: Long,
    ): List<Insight> = buildList {
        if (totalPrevious > 0) {
            val delta = totalNow - totalPrevious
            val percent = (delta * 100f / totalPrevious).roundToInt()
            if (abs(percent) >= 10) {
                add(
                    Insight(
                        text = if (delta > 0) {
                            "You spent ${Money.format(delta)} more than last month (+$percent%)"
                        } else {
                            "You spent ${Money.format(abs(delta))} less than last month ($percent%)"
                        },
                        tone = if (delta > 0) Insight.Tone.NEGATIVE else Insight.Tone.POSITIVE,
                    )
                )
            }
        }

        categories
            .filter { it.previousPaise > 0 && abs(it.deltaPaise) >= SIGNIFICANT_DELTA_PAISE }
            .sortedByDescending { abs(it.deltaPaise) }
            .take(3)
            .forEach { cat ->
                val up = cat.deltaPaise > 0
                add(
                    Insight(
                        text = "${cat.category.label}: ${Money.format(abs(cat.deltaPaise))} " +
                            if (up) "more than last month" else "less than last month",
                        tone = if (up) Insight.Tone.NEGATIVE else Insight.Tone.POSITIVE,
                    )
                )
            }

        categories.firstOrNull()?.let { top ->
            if (totalNow > 0) {
                val share = (top.totalPaise * 100f / totalNow).roundToInt()
                if (share >= 35) {
                    add(
                        Insight(
                            text = "$share% of your spending went to ${top.category.label}",
                            tone = Insight.Tone.NEUTRAL,
                        )
                    )
                }
            }
        }
    }

    private const val SIGNIFICANT_DELTA_PAISE = 50_000L // ₹500

    /** Spend for a given category this month, used for budget progress. */
    fun spentInCategory(transactions: List<Transaction>, category: Category): Long =
        transactions
            .filter { it.type == TxnType.DEBIT && it.category == category }
            .sumOf { it.amountPaise }

    /** Debits deliberately kept out of the headline, so the UI can show the gap. */
    fun excludedBreakdown(transactions: List<Transaction>): List<CategoryTotal> =
        transactions
            .filter { it.type == TxnType.DEBIT && !it.category.countsAsSpending }
            .groupBy { it.category }
            .map { (cat, list) ->
                CategoryTotal(cat, list.sumOf { it.amountPaise }, list.size)
            }
            .sortedByDescending { it.totalPaise }
}

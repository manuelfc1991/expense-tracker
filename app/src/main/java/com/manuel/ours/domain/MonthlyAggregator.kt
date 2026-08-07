package com.manuel.ours.domain

import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.model.CardInfo
import com.manuel.ours.domain.model.ManualBalance
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

    /**
     * The household's zone, from the one place that owns it.
     *
     * Kept as a name here because a lot of call sites read `MonthlyAggregator.ZONE` and that
     * reads well where the subject is a month boundary. It is the same object as
     * [com.manuel.ours.core.OursZone.ID] — the interface used to format dates in
     * `ZoneId.systemDefault()` instead, so a day heading and the month total above it could
     * disagree the moment a phone left IST.
     */
    val ZONE: ZoneId = com.manuel.ours.core.OursZone.ID

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
     * What a debit actually cost, after anything refunded is taken back off it.
     *
     * One function, because every figure on every screen has to agree about this. A purchase
     * that was returned is not spending — the ledger holds both halves, so counting the debit in
     * full would overstate the month by the refund and charge the budget for something that was
     * undone. Near the cap that tells the household to stop when it need not.
     *
     * Partial refunds are the common case for a multi-item order, so this subtracts rather than
     * excluding: the purchase keeps whatever the refund did not cancel.
     */
    fun netSpent(txn: Transaction): Long =
        (txn.amountPaise - txn.refundedPaise).coerceAtLeast(0)

    /**
     * Money actually spent, net of refunds. Excludes the categories that do not count as
     * spending — chiefly card-bill payments, which settle purchases already counted one by one.
     * Use [totalDebited] for the raw sum of every debit.
     */
    fun totalSpent(transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .sumOf { netSpent(it) }

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
     * Money arriving that the household actually earned.
     *
     * Excludes credits that are only money coming back — an FD maturing or a fund redemption is
     * not earnings, and counting it would show a spectacular "income" month every time a deposit
     * matures.
     *
     * A linked refund is excluded from both sides: `linkRefund` moves the credit to
     * SELF_TRANSFER, whose flow is NEUTRAL, so it already falls out here — and the
     * `refundsTxnId` check is belt and braces for a row whose category was changed by hand
     * afterwards. Money coming back is not income; it is a purchase being undone.
     */
    fun totalReceived(transactions: List<Transaction>): Long =
        transactions
            .filter {
                it.type == TxnType.CREDIT &&
                    it.category.flow == MoneyFlow.INCOMING &&
                    it.refundsTxnId == null
            }
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
    /**
     * Every account the household transacts through, and what it was last known to hold.
     *
     * Listed whether or not a balance is known. An account the app has seen payments
     * from but never a balance for still belongs on this screen — Kerala Gramin quotes
     * one on a credit and omits it on a transfer, so the account exists in the ledger
     * and its figure does not. Showing it blank is what makes it obvious you can fill
     * it in; dropping it would make the app look like it had never heard of the account.
     *
     * Never a running total. The app never sees a cash deposit or a silent interest
     * credit, so a balance computed by adding up transactions would drift from the truth
     * and never find its way back. Both sources here are quoted, not derived: one by the
     * bank, one by a person.
     *
     * @param manual balances typed in by the household, keyed the same way, each with
     *   the moment it was entered. A hand-typed figure is used only while it is *newer*
     *   than anything the bank has said — the moment a real balance arrives for that
     *   account, the bank wins and the marker goes away by itself.
     * @param viewerUid whose screen this is.
     * @param isOwner when false, only the viewer's own accounts are returned.
     *
     * Everyone sees their own accounts; the household owner sees all of them. The rows
     * for a partner's spending sync to both phones and carry *their* account number, so
     * without this filter each person's screen would list the other's accounts. Hiding
     * the panel outright was the first attempt and was worse: it left the non-owner
     * unable to record their own balances at all.
     */
    fun accountBalances(
        transactions: List<Transaction>,
        manual: Map<String, ManualBalance> = emptyMap(),
        minimums: Map<String, Long> = emptyMap(),
        /** Accounts the household has declared to be credit cards, keyed the same way. */
        cards: Map<String, CardInfo> = emptyMap(),
        viewerUid: String = "",
        isOwner: Boolean = true,
    ): List<AccountBalance> {
        val own = transactions.filter { !it.accountTail.isNullOrBlank() || it.bank != null }
        val byAccount = own.groupBy { it.accountTail?.takeIf(String::isNotBlank) ?: it.bank!! }

        val visible: (String) -> Boolean = { key ->
            isOwner ||
                byAccount[key].orEmpty().any { it.ownerUid == viewerUid } ||
                manual[key]?.ownerUid == viewerUid
        }
        val keys = (byAccount.keys + manual.keys + minimums.keys + cards.keys).filter(visible)
        return keys.map { key ->
            val rows = byAccount[key].orEmpty()
            val quoted = rows.filter { it.balancePaise != null }.maxByOrNull { it.occurredAt }
            val typed = manual[key]
            // A null figure marks the account without claiming a balance for it. Zero is
            // a claim like any other — a zero-balance account is empty, not unknown.
            val typedPaise = typed?.paise
            val useTyped = typedPaise != null &&
                (quoted == null || typed.setAt > quoted.occurredAt)
            val latest = rows.maxByOrNull { it.occurredAt }
            AccountBalance(
                key = key,
                accountTail = latest?.accountTail?.takeIf(String::isNotBlank)
                    ?: key.takeIf { it.all(Char::isDigit) },
                bank = latest?.bank ?: typed?.bank,
                balancePaise = if (useTyped) typedPaise else quoted?.balancePaise,
                asOf = if (useTyped) typed!!.setAt else quoted?.occurredAt,
                source = when {
                    useTyped -> BalanceSource.HAND
                    quoted != null -> BalanceSource.BANK
                    else -> null
                },
                ownerName = latest?.ownerName.orEmpty(),
                minimumPaise = minimums[key] ?: 0L,
                isCard = cards.containsKey(key),
                limitPaise = cards[key]?.limitPaise,
            )
        }.sortedWith(
            compareByDescending<AccountBalance> { it.balancePaise != null }
                .thenByDescending { it.usablePaise ?: 0L }
        )
    }

    /**
     * Recurring charges still expected before this month is out.
     *
     * Money that is in the account and is not available: the rent standing order due on
     * the 30th is spent in every sense but the technical one. Counted against *capacity*
     * rather than against the budget — the budget will count it when it is actually
     * paid, and subtracting it from both would charge the household twice.
     *
     * Only what is genuinely still to come. A charge whose expected date has already
     * passed this month has almost certainly gone through and been counted as spending,
     * so counting it again here would quietly shrink the household's money every time a
     * subscription renewed.
     */
    fun committedRemaining(
        recurring: List<RecurringCharge>,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        val monthEnd = YearMonth.from(today).plusMonths(1)
            .atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        return recurring
            .filter { it.nextExpectedAt in now until monthEnd }
            .sumOf { it.typicalPaise }
    }

    fun byCategory(
        current: List<Transaction>,
        previous: List<Transaction> = emptyList(),
    ): List<CategoryTotal> {
        val previousByCategory = previous
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { netSpent(it) } }

        return current
            .filter { it.type == TxnType.DEBIT && it.category.countsAsSpending }
            .groupBy { it.category }
            .map { (category, list) ->
                CategoryTotal(
                    category = category,
                    totalPaise = list.sumOf { netSpent(it) },
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
                    totalPaise = list.sumOf { netSpent(it) },
                )
            }
            .sortedByDescending { it.totalPaise }

    fun byDay(transactions: List<Transaction>, year: Int, month: Int): List<DayTotal> {
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        val sums = spendable(transactions)
            .groupBy { dayOfMonth(it.occurredAt) }
            .mapValues { (_, list) -> list.sumOf { netSpent(it) } }

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
                    totalPaise = list.sumOf { netSpent(it) },
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
            .sumOf { netSpent(it) }

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

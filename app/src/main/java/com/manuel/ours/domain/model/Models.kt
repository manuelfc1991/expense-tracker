package com.manuel.ours.domain.model

enum class TxnType { DEBIT, CREDIT }

/** Whether a transaction counts toward the shared household total. */
enum class SplitType { SHARED, PERSONAL }

/** Where the transaction came from. */
enum class TxnSource { SMS, NOTIFICATION, MANUAL }

/**
 * What a debit actually does to your money. Spending is the only kind you consume.
 *
 * Without this distinction an FD booking reads as a ₹1,00,000 shopping spree, and the
 * household looks like it blew the budget in the same month it saved the most.
 */
enum class MoneyFlow {
    /** Consumed. Gone. This is what the budget is about. */
    SPENDING,

    /** Still yours, just somewhere else — FD, RD, SIP, PPF. */
    SAVING,

    /** Moved without changing what you own — transfers, card bill payments. */
    NEUTRAL,

    /** Money arriving. */
    INCOMING,
}

/**
 * No emoji field. Icons come from [com.manuel.ours.ui.components.BiIcon.forCategory],
 * so the domain model stays free of presentation and there is no second, stale set of
 * glyphs for someone to render by accident.
 */
enum class Category(val label: String, val flow: MoneyFlow = MoneyFlow.SPENDING) {
    FOOD("Food & Dining"),
    GROCERIES("Groceries"),
    TRANSPORT("Transport & Fuel"),
    SHOPPING("Shopping"),
    BILLS("Bills & Utilities"),
    RENT("Rent"),
    HEALTH("Health"),
    EDUCATION("Education"),
    ENTERTAINMENT("Entertainment"),
    TRAVEL("Travel"),
    // Savings and investments: an FD, RD or SIP moves money, it doesn't spend it.
    INVESTMENTS("Savings & Investments", MoneyFlow.SAVING),
    // An EMI genuinely leaves your hands, so it stays spending.
    EMI("EMI & Loans"),
    TRANSFERS("Transfers", MoneyFlow.NEUTRAL),
    CARD_PAYMENT("Card bill payment", MoneyFlow.NEUTRAL),
    INCOME("Income", MoneyFlow.INCOMING),
    OTHER("Other");

    companion object {
        fun fromNameOrOther(name: String?): Category =
            entries.firstOrNull { it.name == name } ?: OTHER

        /**
         * Debits kept out of the spending headline.
         *
         * - [CARD_PAYMENT]: a card bill settles purchases already recorded one by one,
         *   so counting the bill too double-counts every transaction inside it.
         *   On real data this alone inflated one month by ₹7,325.
         * - [TRANSFERS]: large round-number debits whose message names no payee are
         *   far more often money moved between accounts than a purchase.
         * - [INVESTMENTS]: an FD, RD or SIP is saving, not spending.
         *
         * Nothing here is hidden — the summary shows each excluded total, and one tap
         * reclassifies anything that really was spending.
         */
        val NON_SPEND: Set<Category> =
            entries.filter { it.flow != MoneyFlow.SPENDING }.toSet()
    }

    val countsAsSpending: Boolean get() = flow == MoneyFlow.SPENDING
}

data class Transaction(
    val id: String,
    val amountPaise: Long,
    val type: TxnType,
    val merchant: String,
    val category: Category,
    val occurredAt: Long,
    val accountTail: String? = null,
    val refNo: String? = null,
    val bank: String? = null,
    val note: String? = null,
    val splitType: SplitType = SplitType.SHARED,
    val source: TxnSource = TxnSource.SMS,
    val ownerUid: String,
    val ownerName: String,
    val needsReview: Boolean = false,
    val rawSms: String? = null,
    val deleted: Boolean = false,
) {
    /** Signed value for arithmetic: debits reduce, credits add. */
    val signedPaise: Long get() = if (type == TxnType.DEBIT) -amountPaise else amountPaise
}

data class Member(
    val uid: String,
    val displayName: String,
    val email: String,
    val isSelf: Boolean,
)

data class Household(
    val id: String,
    val inviteSecret: String,
    val createdByUid: String,
    val members: List<Member>,
)

data class Budget(
    val category: Category?, // null = overall monthly budget
    val limitPaise: Long,
)

data class CategoryTotal(
    val category: Category,
    val totalPaise: Long,
    val txnCount: Int,
    val previousPaise: Long = 0L,
) {
    val deltaPaise: Long get() = totalPaise - previousPaise
    val deltaPercent: Float?
        get() = if (previousPaise == 0L) null
        else (totalPaise - previousPaise) * 100f / previousPaise
}

data class MemberTotal(
    val uid: String,
    val displayName: String,
    val totalPaise: Long,
)

data class MerchantTotal(
    val merchant: String,
    val totalPaise: Long,
    val txnCount: Int,
)

data class DayTotal(
    val dayOfMonth: Int,
    val totalPaise: Long,
)

data class MonthSummary(
    val year: Int,
    val month: Int, // 1-12
    val totalSpentPaise: Long,
    val totalReceivedPaise: Long,
    val previousMonthSpentPaise: Long,
    val byCategory: List<CategoryTotal>,
    val byMember: List<MemberTotal>,
    val byDay: List<DayTotal>,
    val topMerchants: List<MerchantTotal>,
    val biggestExpense: Transaction?,
    val insights: List<Insight>,
    /** Debits kept out of [totalSpentPaise] — card bills, transfers, savings. */
    val excluded: List<CategoryTotal> = emptyList(),
    /** Money moved into deposits and investments this month. */
    val totalSavedPaise: Long = 0L,
) {
    val excludedPaise: Long get() = excluded.sumOf { it.totalPaise }
    val netPaise: Long get() = totalReceivedPaise - totalSpentPaise
    val vsLastMonthPercent: Float?
        get() = if (previousMonthSpentPaise == 0L) null
        else (totalSpentPaise - previousMonthSpentPaise) * 100f / previousMonthSpentPaise
}

data class Insight(
    val text: String,
    val tone: Tone,
) {
    enum class Tone { POSITIVE, NEGATIVE, NEUTRAL }
}

/** Home-screen filter: whose spending to show. */
enum class MemberFilter { BOTH, ME, PARTNER }

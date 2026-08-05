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
 *
 * **One name each, and only one.** There used to be a second `shortLabel` — the label
 * with its "& something" tail removed, plus a hand-written override where that produced
 * nonsense. It was meant for grid cells too narrow for "Savings & Investments", and it
 * worked, but it left every category with two names: Rules, Budgets and Summary's list
 * said one, and the pickers, chips, captions and notification buttons said the other.
 * Three of them were not even abbreviations — Entertainment/Fun, Between our
 * accounts/Ours, Card bill payment/Card bill — so the same category read as two.
 *
 * The qualifiers are gone instead. "Food" says what "Food & Dining" said, and it fits a
 * third of a phone's width, which "Food & Dining" never did. With one property there is
 * nowhere for a second name to come from.
 */
enum class Category(
    val label: String,
    val flow: MoneyFlow = MoneyFlow.SPENDING,
) {
    FOOD("Food"),
    GROCERIES("Groceries"),
    TRANSPORT("Transport"),
    SHOPPING("Shopping"),
    BILLS("Bills"),
    RENT("Rent"),
    HEALTH("Health"),
    EDUCATION("Education"),
    ENTERTAINMENT("Fun"),
    TRAVEL("Travel"),
    // Savings and investments: an FD, RD or SIP moves money, it doesn't spend it.
    INVESTMENTS("Savings", MoneyFlow.SAVING),
    // An EMI genuinely leaves your hands, so it stays spending.
    EMI("EMI"),
    // Both of these were NEUTRAL — money moved without being spent — and both were
    // wrong for a household whose card purchases never reach the app.
    //
    // A transfer with no named payee is overwhelmingly money sent to somebody else's
    // account, not shuffled between your own: on the first real ledger, 83 of 85 were
    // unnamed payees and the remaining two were an IMPS charge and an ATM fee.
    //
    // A card bill is excluded elsewhere on the grounds that it settles purchases the
    // app already counted one by one. That holds only if those purchases arrive as
    // messages — and on this ledger not one of 460 rows was an individual card
    // purchase, so the bill is the *only* record. Excluding it hid the money entirely
    // rather than avoiding a double count. If a card whose purchases do arrive by SMS
    // is ever added, this is the line to revisit.
    TRANSFERS("Transfers"),
    /**
     * Money moved between two accounts the household owns.
     *
     * Distinct from [TRANSFERS], which counts, because the two are not the same event.
     * A transfer with no named payee is overwhelmingly money sent to somebody else and
     * is spending. Money that leaves one of your accounts and lands in another of your
     * accounts minutes later is a wash: nothing was earned and nothing was spent, and
     * counting either leg overstates both sides of the month.
     *
     * Identified by the pair, never by a single message — see
     * `TransactionRepository.markSelfTransfers`.
     */
    SELF_TRANSFER("Ours", MoneyFlow.NEUTRAL),
    CARD_PAYMENT("Card bill"),
    INCOME("Income", MoneyFlow.INCOMING),
    OTHER("Other");

    companion object {
        /**
         * What the category grid offers, in the order the design draws it.
         *
         * [INCOME] is absent because the grid only ever appears on a debit, and
         * [OTHER] because it is where a row lands when nothing has been decided —
         * offering it as a choice invites filing things there deliberately, which is
         * the opposite of what the grid is for.
         */
        val PICKABLE: List<Category> = listOf(
            FOOD, GROCERIES, TRANSPORT,
            SHOPPING, BILLS, RENT,
            HEALTH, EDUCATION, ENTERTAINMENT,
            TRAVEL, INVESTMENTS, EMI,
            TRANSFERS, CARD_PAYMENT, SELF_TRANSFER,
        )

        /**
         * What a person can be *entering by hand*: the twelve kinds of spending.
         *
         * [TRANSFERS], [CARD_PAYMENT] and [SELF_TRANSFER] are deliberately absent. All
         * three describe money moving between accounts, which is something a bank always
         * messages about — you never sit down and type in a card bill you just paid.
         * Offering them here would only invite a hand-typed row that duplicates the
         * parsed one an hour later, and a duplicate is worse than a missing category.
         * They stay available on the detail screen, where the parsed row already exists
         * and the job is to correct it.
         */
        val SPENDING: List<Category> = PICKABLE.filter {
            it != TRANSFERS && it != CARD_PAYMENT && it != SELF_TRANSFER
        }

        /**
         * Everything a category can be set *to*, in one order.
         *
         * Rules, Sort's full picker and the Activity filter each built their own list —
         * two of them as `entries` minus [OTHER], one as [PICKABLE] plus [INCOME]. Those
         * are the same sixteen categories, but in two different orders, so the same
         * screen-full appeared twice with Card bill and Ours swapped.
         *
         * [INCOME] belongs here and not in [PICKABLE]: a rule may well file a merchant as
         * income — a salary, an FD maturing — but the grids that use [PICKABLE] only ever
         * open on a debit, where offering Income is offering a wrong answer.
         */
        val EVERY: List<Category> = PICKABLE + INCOME

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
    /** Set when a member has asked the household owner to remove this row. */
    val deleteRequestedBy: String? = null,
    /** When the amount was last changed by hand; null means it is still the bank's. */
    val amountEditedAt: Long? = null,
    /** Last digits of the account paid, when the bank named one. */
    val counterpartyTail: String? = null,
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

/**
 * Whose spending to show.
 *
 * Was `BOTH / ME / PARTNER`, which quietly asserted a household of exactly two: with a
 * wife *and* a child, "Partner" meant "everyone who is not me" and there was no way to
 * look at either of them alone. Carrying the uid instead lets the chips be generated
 * from whoever actually exists.
 */
/**
 * A row nobody has confirmed the category of.
 *
 * Two different states mean the same thing to a reader — the parser was unsure
 * ([Transaction.needsReview]) or it landed in [Category.OTHER] having matched no rule —
 * and the entry row has always drawn both the same way, in amber, with its amount dimmed
 * because it is not in the month's total yet. The filter has to agree with the caption,
 * so the test lives here rather than being written out twice.
 */
val Transaction.isUntagged: Boolean
    get() = needsReview || category == Category.OTHER

/**
 * What the Activity screen is narrowed to.
 *
 * A nullable [Category] could not express the group people most want, which is the
 * untagged rows — in August that was six of nineteen, the largest group on the screen,
 * and there was no way to ask for it.
 */
sealed interface CategoryFilter {
    data object All : CategoryFilter

    /** Everything [isUntagged] — the parser's unsure pile and [Category.OTHER] together. */
    data object Untagged : CategoryFilter

    /**
     * One category, counting only rows that are *not* untagged.
     *
     * A needs-review row can still carry a guessed category, so without that exclusion a
     * single row would appear under both Food and Untagged and the chip counts would add
     * up to more than the screen holds. Partitioning them means the chips sum exactly to
     * All, which is what makes the counts trustworthy.
     */
    data class Of(val category: Category) : CategoryFilter
}

sealed interface MemberFilter {
    /** The household total. */
    data object Everyone : MemberFilter

    /** One person, self included — "Me" is just this with your own uid. */
    data class Person(val uid: String) : MemberFilter
}

/** A household member, as derived from the rows they own. */
data class HouseholdMember(
    val uid: String,
    val displayName: String,
    val isSelf: Boolean,
) {
    /** "You" for yourself, first names for everyone else — chips are narrow. */
    val chipLabel: String
        get() = if (isSelf) "Me" else displayName.trim().split(" ").firstOrNull().orEmpty()
}

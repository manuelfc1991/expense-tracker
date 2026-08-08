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
 * No emoji field. Icons come from [com.manuel.ours.ui.components.OursIcon.forCategory],
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
    //
    // **That card has now been added.** The Utkarsh SuperCard is a RuPay credit card
    // whose every purchase arrives from UTKSPR, read since 1 August 2026 — and the
    // household pays its bill from Kerala Gramin, which is the row the parser labels
    // "Rupay Card". So for that one card both halves are now recorded: each purchase,
    // and the bill that settles them.
    //
    // Nothing is double-counted yet only because no SuperCard purchase has fallen after
    // the 1 August floor. The first month it does, this category starts overstating the
    // total by the size of the bill. The fix is *not* to exclude card bills globally:
    // the ICICI card's purchases still never arrive, so its bill remains the only record
    // of that money, and excluding it would hide the spending outright. Whatever replaces
    // this has to be decided per card, not per category.
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
         * Every category you can put a row into, in one order, on every screen.
         *
         * There used to be three of these. The grids offered [PICKABLE] — fifteen,
         * without Income, on the reasoning that they only ever open on a debit and
         * offering Income there is offering a wrong answer. The add sheet offered a
         * narrower twelve still, dropping Transfers, Card bill and Ours on the reasoning
         * that the bank always messages about those, so hand-typing one only invites a
         * duplicate. Rules and the filter offered all sixteen.
         *
         * Both arguments were about what someone *ought* to want, and neither survived
         * contact with wanting it: the reason a debit is sitting on the detail screen at
         * all is usually that the app got it wrong, and "this credit was misread as a
         * payment" is exactly the correction the grid was refusing to allow. Guessing
         * which of sixteen answers a person is not allowed to give costs more than the
         * occasional odd choice, and every one of them is one tap to undo.
         *
         * Marking a debit as Income is safe: every total gates on [Transaction.type]
         * before it looks at the category, so such a row simply drops out of spending
         * the way Transfers and Card bill already do. It is never added to income.
         *
         * [OTHER] stays out. It is not a choice — it is the absence of one, which is why
         * the filter offers it as "Untagged" instead.
         */
        val EVERY: List<Category> = listOf(
            FOOD, GROCERIES, TRANSPORT,
            SHOPPING, BILLS, RENT,
            HEALTH, EDUCATION, ENTERTAINMENT,
            TRAVEL, INVESTMENTS, EMI,
            TRANSFERS, CARD_PAYMENT, SELF_TRANSFER,
            INCOME,
        )

        fun fromNameOrOther(name: String?): Category =
            entries.firstOrNull { it.name == name } ?: OTHER

        /**
         * Debits kept out of the spending headline — which is **three** categories, not
         * the six this comment used to claim.
         *
         * - [INVESTMENTS] ("Savings"): an FD, RD or SIP is saving, not spending.
         * - [SELF_TRANSFER] ("Ours"): out of one of your accounts, into another.
         * - [INCOME]: never a debit in the first place.
         *
         * [TRANSFERS] and [CARD_PAYMENT] are **not** excluded, whatever their names
         * suggest. Both carry the default [MoneyFlow.SPENDING], and both do so
         * deliberately — see the notes on each. This list is derived from `flow`, so it
         * has always behaved that way; only the comment said otherwise, and a comment
         * that contradicts the one number the household trusts is worse than none.
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
    /** When it was deleted, for the Trash window. Null on live rows and on old tombstones. */
    val deletedAt: Long? = null,
    /** Set when a member has asked the household owner to remove this row. */
    val deleteRequestedBy: String? = null,
    /** When the amount was last changed by hand; null means it is still the bank's. */
    val amountEditedAt: Long? = null,
    /** Last digits of the account paid, when the bank named one. */
    val counterpartyTail: String? = null,
    /**
     * The bank's own id for the message this came from. Identity for dedup, never shown.
     * See `TransactionEntity.bankMessageId`.
     */
    val bankMessageId: String? = null,
    /** On a credit: the purchase this refund cancels. See TransactionEntity.refundsTxnId. */
    val refundsTxnId: String? = null,
    /** On a debit: how much of it has been refunded. */
    val refundedPaise: Long = 0,
    /**
     * What the bank said was left in [accountTail] just after this payment.
     *
     * Read straight off the message — "Bal Rs 3065.35" — and stored rather than
     * discarded, which is what used to happen: the parser has always pulled this out
     * and thrown it away. It is the only figure in the app that is not derived from
     * other figures; every total elsewhere is arithmetic on transactions, and this is
     * the bank's own answer.
     *
     * Null for a message that carried none, and for every hand-entered row. Only ever
     * a *balance* — the clause matches "avl bal", "a/c bal", "clr bal" and nothing
     * else, so a card's "avl limit" or "total amt due" can never land here.
     */
    val balancePaise: Long? = null,
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

/**
 * What one account was last known to hold, and when.
 *
 * [asOf] is not decoration. This figure is only as fresh as the last message that
 * quoted it, and an account nobody has used for three weeks will report a three-week-old
 * number — which is fine as long as the screen says so. A balance presented without its
 * date is the app claiming to know something it does not.
 */
/**
 * The wallet, as an answer to "paid from".
 *
 * Not an account any bank knows, and deliberately stored in `bank` rather than left blank:
 * `accountBalances()` discards rows with neither a tail nor a bank, so "blank" would mean the
 * payment vanished from the Accounts tab — which is the whole defect this answers.
 */
const val CASH_ACCOUNT = "Cash"

/**
 * Which account a hand-added payment came out of.
 *
 * Three answers, and [Unknown] is as real as the other two — a person who cannot remember
 * should not be made to guess, and a guess stored as fact is worse than a blank.
 */
sealed interface PaidFrom {
    val accountTail: String?
    val bank: String?

    object Cash : PaidFrom {
        override val accountTail: String? = null
        override val bank: String = CASH_ACCOUNT
    }

    /** Nobody said. Stored as nothing, and reported as unknown rather than summed as zero. */
    object Unknown : PaidFrom {
        override val accountTail: String? = null
        override val bank: String? = null
    }

    data class Account(
        override val accountTail: String?,
        override val bank: String?,
    ) : PaidFrom
}

/**
 * What the household told us about a credit card.
 *
 * @param limitPaise the credit limit, when they know it. Buys one thing — "still free" —
 *   and the card works without it.
 * @param dueDay day of the month the bill falls due, or null.
 */
data class CardInfo(
    val limitPaise: Long? = null,
    val dueDay: Int? = null,
)

/**
 * Whose an account is, as the household has recorded it.
 *
 * The name travels with the uid so a heading can be drawn from this alone; see
 * `RulesRepository.TYPE_OWNER` for why it is not looked up from the member list.
 */
data class AccountOwner(
    val uid: String,
    val displayName: String,
)

data class AccountBalance(
    /** Stable identity: the account number when the bank gives one, else its name. */
    val key: String,
    /** Null when the bank never named the account — then [bank] is the whole identity. */
    val accountTail: String?,
    val bank: String?,
    /** Null for an account the app knows exists but has never been told the balance of. */
    val balancePaise: Long?,
    val asOf: Long?,
    val source: BalanceSource?,
    /**
     * Whose account the household has **said** this is, and their name for the heading.
     *
     * Null is "nobody has claimed it", which is a real answer and not a missing one — it
     * groups under Shared rather than under a guess.
     *
     * This used to be the owner of the most recent payment out of the account, which is
     * a different question with a different answer: a joint account filed itself under
     * whoever used it last and flipped as soon as the other person paid for something,
     * and an account added by hand had never been paid from at all, so it grouped under
     * a blank name. An owner has to be recorded to be worth showing — see [ownerUid].
     */
    val ownerUid: String? = null,
    val ownerName: String = "",
    /**
     * A credit card, whose balance is money **owed** rather than money held.
     *
     * Never summed with the others. The Accounts tab totals "what is left" and
     * `Affordability` spends against it, so folding ₹4,200 of card debt into that total
     * would report ₹4,200 more to spend than exists — the opposite of the truth.
     */
    val isCard: Boolean = false,
    /**
     * Money the household owns and cannot spend — a fixed deposit, an RD, a PPF.
     *
     * A third kind because it is a third answer. [isCard] is money owed and an ordinary
     * balance is money available; this is money held. Counting it in "what is left" tells
     * somebody they can spend a deposit that is locked up, and leaving it out of the
     * screen entirely denies that they own it.
     *
     * Like [isCard] it must be excluded in `Affordability` and not merely on the screen —
     * the card version of exactly this bug counted a debt as capacity for a whole release,
     * because only the panel honoured the partition.
     */
    val isSavings: Boolean = false,
    val limitPaise: Long? = null,
    /**
     * Day of the month this card's bill falls due, when the household has said.
     *
     * Carried here so the Accounts panel can show it and the edit dialog can change it.
     * It lived only on `CardInfo` and so never reached a screen, which is half of why it
     * sat unset — the other half being that the add dialog hard-coded null.
     */
    val dueDay: Int? = null,
    /**
     * What the bank makes you keep in the account, which is not yours to spend.
     *
     * Zero for a zero-balance account. Breaching it costs a penalty, so money below
     * this line is the bank's hostage rather than the household's savings — this
     * household already moves money about specifically to avoid it.
     */
    val minimumPaise: Long = 0L,
    /**
     * Whether the ledger itself references this account — a payment came out of it.
     *
     * Such an account cannot be removed, and the screen must not offer to: the money is
     * recorded against it, and `accountBalances()` rebuilds it from the transactions on
     * every read, so "removing" it would hide it for a moment and leave spending
     * attributed to an account the screen denies exists.
     */
    val fromLedger: Boolean = false,
    /**
     * Movements the app has seen since a **typed** balance was entered, already folded
     * into [balancePaise]. Negative when more went out than came in; zero for a
     * bank-quoted figure, which corrects itself.
     *
     * Carried so the screen can say the figure was adjusted rather than silently showing
     * a number nobody typed.
     */
    val movedSincePaise: Long = 0L,
) {
    /** What can actually be taken out before the bank starts charging for it. */
    val usablePaise: Long? get() = balancePaise?.let { (it - minimumPaise).coerceAtLeast(0L) }
}

/**
 * Where a balance came from, which the screen has to say out loud.
 *
 * A figure the bank quoted corrects itself: the next message from that account brings a
 * newer one. A figure somebody typed does not — it sits there looking equally
 * authoritative while the real balance moves underneath it. Mixing the two without
 * marking which is which would make the honest number and the guess indistinguishable.
 */
enum class BalanceSource { BANK, HAND }

/** A balance somebody typed in, and when — so the bank can outrank it later. */
data class ManualBalance(
    /**
     * Null when the row marks that the account exists without claiming a figure for it.
     *
     * Zero used to carry that meaning, which made a genuinely empty account impossible
     * to record: a zero-balance current account typed as 0 was read back as "nobody has
     * said", and the figure never stuck. Unknown is never zero.
     */
    val paise: Long?,
    val setAt: Long,
    val bank: String?,
    /**
     * Who typed it. Null for entries made before this was recorded — those show only to
     * the household owner, who could see every account anyway.
     */
    val ownerUid: String? = null,
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
    /**
     * Debits kept out of [totalSpentPaise] — savings, Ours, and income.
     *
     * **Not** card bills or transfers, whatever their names suggest: both carry
     * `MoneyFlow.SPENDING` and both count, deliberately. The same wrong claim was fixed
     * on `NON_SPEND` and survived here.
     */
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

/**
 * "Kerala Gramin ···3062", or just the bank when it never named an account.
 *
 * Short enough for a chip: the bank's own name is already long, and the tail is what
 * distinguishes two accounts at the same bank.
 */
fun AccountBalance.shortLabel(): String {
    val name = bank ?: accountTail?.let { "Account" } ?: key
    val tail = accountTail?.takeIf { it.isNotBlank() }
    return if (tail != null) "$name ···$tail" else name
}

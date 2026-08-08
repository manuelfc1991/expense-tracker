package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.Category
import org.junit.Test

/**
 * The rule that decides whether paying a card bill counts as spending.
 *
 * This is the whole double-count defence, and until now nothing tested it. It sat inline
 * in `importParsed` — reachable only through Room, a parser and a DAO — so every test in
 * this tree went round it. `CreditCardTest` and `CardBillEchoTest` re-implement their own
 * predicates locally; none of them touch this one.
 *
 * What it has to get right is a pair of opposite failures:
 *
 * - Counting a **registered** card's bill counts that money twice, because the card's
 *   purchases already arrived one at a time. The SuperCard sends 185 messages a period and
 *   both halves of the money reach the app.
 * - *Not* counting an **unregistered** card's bill hides the money outright, because the
 *   bill is the only record that card ever leaves. ICICI sends nothing at all — no
 *   purchases, no statements — so its bill is the single trace of everything spent on it.
 *
 * So the same message must be spending or not spending depending on nothing but whether
 * the household has said "this is a card I track", and the app has to be able to tell the
 * two apart from the last four digits the message names.
 */
class SettlesTrackedCardTest {

    private val superCard = setOf("2020")

    private fun category(
        kind: SmsParser.Kind,
        accountTail: String? = null,
        counterpartyTail: String? = null,
        registered: Set<String> = superCard,
    ) = TransactionRepository.categoryForKind(kind, accountTail, counterpartyTail, registered)

    /**
     * The case the mechanism exists for: a bill naming a card whose purchases the app
     * already counts is money moving between two things the household owns.
     */
    @Test
    fun `a bill naming a registered card is not spending`() {
        assertThat(category(SmsParser.Kind.CARD_BILL_PAYMENT, accountTail = "2020"))
            .isEqualTo(Category.SELF_TRANSFER)
        assertThat(Category.SELF_TRANSFER.countsAsSpending).isFalse()
    }

    /**
     * The opposite case, and the reason this is decided per card rather than per category.
     *
     * An unregistered card's purchases never reach the app, so excluding its bill would
     * remove the only record of that spending. ICICI is exactly this account.
     */
    @Test
    fun `a bill naming an unregistered card is spending`() {
        val category = category(SmsParser.Kind.CARD_BILL_PAYMENT, accountTail = "3008")
        assertThat(category).isEqualTo(Category.CARD_PAYMENT)
        assertThat(category!!.countsAsSpending).isTrue()
    }

    /**
     * Deliberately narrow: a bill that never says which card is left counting.
     *
     * The alternative is quietly removing spending the app cannot prove it recorded
     * elsewhere — and with no last four there is nothing to check that claim against.
     */
    @Test
    fun `a bill naming no card at all keeps counting`() {
        assertThat(category(SmsParser.Kind.CARD_BILL_PAYMENT)).isEqualTo(Category.CARD_PAYMENT)
    }

    /**
     * Either tail answers, because only one of the two is ever populated.
     *
     * The card is the account being settled when its own issuer sends the message, and the
     * counterparty being paid when the bank the money left sends it. Checking one side
     * only would make the rule fire or not fire depending on which phone read which SMS.
     */
    @Test
    fun `the card may be named as either side of the payment`() {
        assertThat(category(SmsParser.Kind.CARD_BILL_PAYMENT, counterpartyTail = "2020"))
            .isEqualTo(Category.SELF_TRANSFER)
        assertThat(
            category(
                SmsParser.Kind.CARD_BILL_PAYMENT,
                accountTail = "3062",
                counterpartyTail = "2020",
            )
        ).isEqualTo(Category.SELF_TRANSFER)
    }

    /**
     * Registration is what fires it, and nothing else.
     *
     * The same message, unchanged, flips category on the strength of one shared rule. This
     * is the sentence "registering the SuperCard is what defuses the double count" written
     * as an assertion.
     */
    @Test
    fun `registering the card is the only thing that changes the answer`() {
        val message = { registered: Set<String> ->
            category(SmsParser.Kind.CARD_BILL_PAYMENT, accountTail = "2020", registered = registered)
        }
        assertThat(message(emptySet())).isEqualTo(Category.CARD_PAYMENT)
        assertThat(message(superCard)).isEqualTo(Category.SELF_TRANSFER)
    }

    /**
     * A blank rule is the tombstone, so a card turned back into an account stops
     * exempting its bills. This is the state `adoptKnownCard` refuses to overwrite.
     */
    @Test
    fun `a card the household un-registered stops exempting its bills`() {
        assertThat(
            category(SmsParser.Kind.CARD_BILL_PAYMENT, accountTail = "2020", registered = emptySet())
        ).isEqualTo(Category.CARD_PAYMENT)
    }

    /**
     * Only a card **bill** is ever exempted.
     *
     * A purchase on the SuperCard names ···2020 too. If the tail alone decided this, every
     * purchase on a registered card would fall out of the spend total — which is the exact
     * money the mechanism is trying to keep, counted once.
     */
    @Test
    fun `a purchase on a registered card is still a purchase`() {
        // Null means "the kind cannot answer — ask the Categorizer", which is what a
        // purchase must always come back as, tail or no tail.
        assertThat(category(SmsParser.Kind.PURCHASE, accountTail = "2020")).isNull()
        assertThat(category(SmsParser.Kind.TRANSFER, accountTail = "2020"))
            .isEqualTo(Category.TRANSFERS)
        assertThat(category(SmsParser.Kind.SAVINGS_DEPOSIT, accountTail = "2020"))
            .isEqualTo(Category.INVESTMENTS)
    }

    /** The predicate itself, stated plainly, since the category mapping wraps it. */
    @Test
    fun `only a card bill payment can settle a tracked card`() {
        SmsParser.Kind.entries.forEach { kind ->
            val settles = TransactionRepository.settlesTrackedCard(kind, "2020", null, superCard)
            assertThat(settles).isEqualTo(kind == SmsParser.Kind.CARD_BILL_PAYMENT)
        }
    }
}

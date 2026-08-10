package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.BankRules
import org.junit.Test

/**
 * Recognising a credit card from what the message says, when the sender cannot say it.
 *
 * `isCardBank` asks the TRAI header, and for this household's own ICICI card the header gives
 * the wrong answer: acknowledgements arrive from `ICICIT`, which `BankRules` maps to
 * **"ICICI Bank"** — the account rule. The card rule uses `ICICICC` and `ICICCD`, which this
 * card never sends from, and `ICICIO` is not in the table at all.
 *
 * The consequence was measured, not guessed. On a clean emulator, three real messages in, the
 * Accounts panel filed ···3008 under *What is left* — a debt counted as money to spend, which
 * is the exact failure `adoptKnownCard` was written to prevent. The real phone looks right only
 * because the card was registered by hand; a restore or a new phone would not be.
 *
 * The eager direction is the dangerous one here. A bill reminder can name a credit card while
 * quoting the **account** it will be debited from, and filing that account as a card inverts the
 * sign on real money — the same class of mistake as [CardConversionTest] and [PutAsideTest].
 * So the digits have to belong to the phrase, not merely share a message with it.
 */
class CardFromMessageTest {

    // ── The message the household actually receives ───────────────────────────────────

    /** Verbatim, from the ICICI acknowledgement quoted in CLAUDE.md. */
    @Test
    fun `the ICICI acknowledgement names its own card`() {
        val body = "Payment of Rs 468.41 has been received on your ICICI Bank Credit Card " +
            "XX3008 through Bharat Bill Payment System."
        assertThat(BankRules.namesCreditCard(body, "3008")).isTrue()
    }

    /** And the sender alone still cannot tell you, which is why the text test exists. */
    @Test
    fun `the sender for that message is not a card bank`() {
        val rule = BankRules.forSender("VM-ICICIT")
        assertThat(rule?.bank).isEqualTo("ICICI Bank")
        assertThat(BankRules.isCardBank(rule?.bank)).isFalse()
    }

    // ── Shapes that should match ──────────────────────────────────────────────────────

    @Test
    fun `common ways of writing the number are all recognised`() {
        val forms = listOf(
            "spent on your Credit Card XX3008 today",
            "your credit card ending 3008 was used",
            "Credit Card ending in 3008 has a payment due",
            "CREDIT CARD NO. XXXX3008 statement generated",
            "your Credit Card ****3008 was charged",
            "creditcard 3008 payment received",
        )
        for (body in forms) {
            assertThat(BankRules.namesCreditCard(body, "3008")).isTrue()
        }
    }

    /** More than one card in a message: each is matched on its own digits. */
    @Test
    fun `a message naming two cards matches each of them`() {
        val body = "Credit Card XX3008 paid from Credit Card XX2020."
        assertThat(BankRules.namesCreditCard(body, "3008")).isTrue()
        assertThat(BankRules.namesCreditCard(body, "2020")).isTrue()
        assertThat(BankRules.namesCreditCard(body, "4657")).isFalse()
    }

    // ── Shapes that must not match ────────────────────────────────────────────────────

    /**
     * The failure that would cost real money: a reminder that mentions a card and then names
     * the *account* paying it. Filing 4657 as a card would move a bank balance out of "what is
     * left" and report it as debt.
     */
    @Test
    fun `an account quoted beside a card is not itself a card`() {
        val body = "Your Credit Card bill of Rs 500 is due. It will be debited from a/c XX4657."
        assertThat(BankRules.namesCreditCard(body, "4657")).isFalse()
    }

    /** A debit card is not a credit card, whatever the word "card" suggests. */
    @Test
    fun `a debit card is not matched`() {
        val body = "Rs 250 spent on your Debit Card XX4657 at a shop."
        assertThat(BankRules.namesCreditCard(body, "4657")).isFalse()
    }

    /** Distance breaks the claim: the digits have to belong to the phrase. */
    @Test
    fun `digits far from the phrase are not matched`() {
        val body = "Your credit card statement is ready. Please call us about a/c 3008 shortly."
        assertThat(BankRules.namesCreditCard(body, "3008")).isFalse()
    }

    /** A different card's digits in the same message do not make this account one. */
    @Test
    fun `another card's digits do not adopt this account`() {
        val body = "Payment received on your ICICI Bank Credit Card XX3008."
        assertThat(BankRules.namesCreditCard(body, "2020")).isFalse()
    }

    @Test
    fun `nothing to read is not a card`() {
        assertThat(BankRules.namesCreditCard(null, "3008")).isFalse()
        assertThat(BankRules.namesCreditCard("", "3008")).isFalse()
        assertThat(BankRules.namesCreditCard("Credit Card XX3008", null)).isFalse()
        assertThat(BankRules.namesCreditCard("Credit Card XX3008", "")).isFalse()
    }

    /**
     * A Kerala Gramin debit, the household's commonest message by far. Nothing in it is a card
     * and nothing in it should be read as one.
     */
    @Test
    fun `an ordinary bank debit names no card`() {
        val body = "Debited Rs 151.00 from a/c X4657 on 01Jul26 07:48 via UPI to KEECHERIL ST. " +
            "Ref 618233824289.Bal Rs 3469.55. -Federal Bank"
        assertThat(BankRules.namesCreditCard(body, "4657")).isFalse()
    }
}

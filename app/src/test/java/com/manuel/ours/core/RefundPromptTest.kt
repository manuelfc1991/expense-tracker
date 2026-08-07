package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.ui.screens.transactions.bankPaidYou
import org.junit.Test

/**
 * Which credits are worth asking "Is this money coming back?" about.
 *
 * The question used to be asked about every credit there was — the condition was literally
 * `if (current.type == TxnType.CREDIT)`. On this ledger that meant the ₹81 of savings
 * interest Federal pays, on a message that reads in full:
 *
 *     Dear Customer, Rs.81 credited to your A/c XX4657 on 07AUG2026 03:19:57.
 *     BAL-Rs.3081.23-Federal Bank
 *
 * Nothing in that says "interest", and the app cannot know that it is. What it *can* know
 * is that the message named no payee, so the parser fell back to the bank's own name — and
 * money from the bank holding the account is interest, a reversal or a salary, none of
 * which can be a merchant refund.
 */
class RefundPromptTest {

    // ── the bank paying you: no banner ───────────────────────────────────────

    /** The real message behind the complaint. */
    @Test
    fun `interest credited by your own bank is not asked about`() {
        assertThat(bankPaidYou(merchant = "Federal Bank", bank = "Federal Bank")).isTrue()
    }

    @Test
    fun `case does not change the answer`() {
        assertThat(bankPaidYou("federal bank", "Federal Bank")).isTrue()
        assertThat(bankPaidYou("FEDERAL BANK", "Federal Bank")).isTrue()
    }

    /** A credit the parser could put no name to at all. */
    @Test
    fun `an unnamed payee is not asked about`() {
        assertThat(bankPaidYou("Unknown payee", "Kerala Gramin Bank")).isTrue()
        assertThat(bankPaidYou("", "Kerala Gramin Bank")).isTrue()
        assertThat(bankPaidYou("   ", "Kerala Gramin Bank")).isTrue()
    }

    // ── a merchant paying you: still asked ───────────────────────────────────

    /**
     * The case the prompt exists for. A ₹2,000 return leaves the ledger holding a ₹2,000
     * debit *and* a ₹2,000 credit — net worth right, spending overstated by ₹2,000, and
     * the budget charged for a purchase that was undone. Only a person can say.
     */
    @Test
    fun `a credit from a merchant is still asked about`() {
        assertThat(bankPaidYou("Amazon", "Kerala Gramin Bank")).isFalse()
        assertThat(bankPaidYou("Flipkart", "Federal Bank")).isFalse()
    }

    /**
     * A merchant credit on an account whose bank was never recorded. Null bank must not
     * quietly match a real merchant name and swallow the prompt.
     */
    @Test
    fun `a merchant credit with no bank recorded is still asked about`() {
        assertThat(bankPaidYou("Amazon", null)).isFalse()
    }

    /** But a credit with no payee and no bank is still the bank's, not a merchant's. */
    @Test
    fun `no payee and no bank is still not a refund prompt`() {
        assertThat(bankPaidYou("", null)).isTrue()
    }
}

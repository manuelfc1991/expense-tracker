package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * An account that really is empty, against one nobody has spoken for.
 *
 * Zero used to carry both meanings. A typed 0 was written as the empty tombstone and
 * read back as "no figure", so the household's zero-balance ICICI current account could
 * not be recorded at all — you typed 0, saved, and the account went on claiming it did
 * not know. Unknown is never zero, and the reverse holds too: zero is not unknown.
 */
class ZeroBalanceAccountTest {

    private fun rupees(amount: Long) = amount * 100

    private fun txn(tail: String, bank: String, at: Long, balance: Long? = null) = Transaction(
        id = "$tail-$at",
        amountPaise = rupees(100),
        type = TxnType.DEBIT,
        merchant = "shop",
        category = Category.GROCERIES,
        occurredAt = at,
        accountTail = tail,
        bank = bank,
        ownerUid = "self",
        ownerName = "Manuel",
        balancePaise = balance,
    )

    @Test
    fun `a typed zero is a balance, not an absence of one`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = listOf(txn("3008", "ICICI", at = 1_000L)),
            manual = mapOf(
                "3008" to ManualBalance(paise = 0L, setAt = 2_000L, bank = "ICICI"),
            ),
        )

        val icici = result.single { it.key == "3008" }
        assertThat(icici.balancePaise).isEqualTo(0L)
        assertThat(icici.source).isEqualTo(BalanceSource.HAND)
    }

    @Test
    fun `a null figure marks the account without claiming a balance`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = mapOf(
                "9999" to ManualBalance(paise = null, setAt = 2_000L, bank = "Federal"),
            ),
        )

        val account = result.single { it.key == "9999" }
        // The account is listed — that is what the row is for — but nothing is claimed
        // about what is in it, so no source and no figure.
        assertThat(account.balancePaise).isNull()
        assertThat(account.source).isNull()
        assertThat(account.bank).isEqualTo("Federal")
    }

    @Test
    fun `a typed zero still loses to a bank figure that arrived later`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = listOf(
                txn("3008", "ICICI", at = 5_000L, balance = rupees(2_500)),
            ),
            manual = mapOf(
                "3008" to ManualBalance(paise = 0L, setAt = 1_000L, bank = "ICICI"),
            ),
        )

        val icici = result.single { it.key == "3008" }
        assertThat(icici.balancePaise).isEqualTo(rupees(2_500))
        assertThat(icici.source).isEqualTo(BalanceSource.BANK)
    }

    @Test
    fun `a typed zero outranks an older bank figure`() {
        // The bank said ₹2,500 yesterday; the account has since been emptied and somebody
        // typed that in. The newer hand figure wins, exactly as a non-zero one would.
        val result = MonthlyAggregator.accountBalances(
            transactions = listOf(
                txn("3008", "ICICI", at = 1_000L, balance = rupees(2_500)),
            ),
            manual = mapOf(
                "3008" to ManualBalance(paise = 0L, setAt = 5_000L, bank = "ICICI"),
            ),
        )

        val icici = result.single { it.key == "3008" }
        assertThat(icici.balancePaise).isEqualTo(0L)
        assertThat(icici.source).isEqualTo(BalanceSource.HAND)
    }
}

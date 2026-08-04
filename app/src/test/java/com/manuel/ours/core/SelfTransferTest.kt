package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * Money that only moved between the household's own accounts.
 *
 * The evidence is the account tail. This phone receives alerts for accounts the
 * household owns, so a stored tail is always one of theirs — a debit on one and a
 * credit on another, same amount, minutes apart, is a round trip rather than a
 * purchase and a windfall.
 *
 * Both legs are marked neutral rather than deleted: they are real messages about real
 * movements, and a household that transferred by accident should be able to see that
 * it happened. What they should not see is the month claiming they spent it.
 */
class SelfTransferTest {

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(2026, 8, day).atStartOfDay(MonthlyAggregator.ZONE)
            .plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    /** The pairing rule as the repository applies it. */
    private fun pairs(
        debitAt: Long, creditAt: Long,
        debitAmount: Long = 10_000_00, creditAmount: Long = 10_000_00,
        debitTail: String? = "4657", creditTail: String? = "3062",
    ): Boolean =
        !debitTail.isNullOrBlank() && !creditTail.isNullOrBlank() &&
            debitAmount == creditAmount &&
            debitTail != creditTail &&
            abs(creditAt - debitAt) <= TransactionRepository.SELF_TRANSFER_WINDOW_MS

    private fun txn(amount: Long, category: Category, type: TxnType) = Transaction(
        id = "t${amount}${category.name}${type.name}",
        amountPaise = amount,
        type = type,
        merchant = "x",
        category = category,
        occurredAt = at(2, 10, 0),
        ownerUid = "uid",
        ownerName = "Manuel",
    )

    @Test
    fun `a round trip between two of our accounts is paired`() {
        assertThat(pairs(at(2, 10, 50), at(2, 10, 52))).isTrue()
    }

    @Test
    fun `neither leg counts once marked`() {
        val txns = listOf(
            txn(10_000_00, Category.SELF_TRANSFER, TxnType.DEBIT),
            txn(10_000_00, Category.SELF_TRANSFER, TxnType.CREDIT),
            txn(2_000_00, Category.FOOD, TxnType.DEBIT),
        )
        assertThat(MonthlyAggregator.totalSpent(txns)).isEqualTo(2_000_00)
        assertThat(MonthlyAggregator.totalReceived(txns)).isEqualTo(0)
    }

    // ─── what it must never pair ────────────────────────────────────────────

    /**
     * The 2 August pair on the real ledger: a fixed deposit maturing and rent paid to a
     * person, ₹10,000 each, a minute apart, both on the *same* account. Same tail means
     * one account, so this is two real events and not a round trip — pairing them would
     * erase both a genuine expense and a genuine credit.
     */
    @Test
    fun `an FD credit and a rent debit on the same account are not a round trip`() {
        assertThat(pairs(at(2, 10, 51), at(2, 10, 50), debitTail = "4657", creditTail = "4657"))
            .isFalse()
    }

    @Test
    fun `a missing tail is never enough evidence`() {
        assertThat(pairs(at(2, 10, 50), at(2, 10, 52), debitTail = null)).isFalse()
        assertThat(pairs(at(2, 10, 50), at(2, 10, 52), creditTail = "")).isFalse()
    }

    @Test
    fun `an expense and an unrelated credit hours later are left alone`() {
        assertThat(pairs(at(2, 9, 0), at(2, 18, 0))).isFalse()
    }

    @Test
    fun `different amounts are different events`() {
        assertThat(pairs(at(2, 10, 50), at(2, 10, 52), creditAmount = 10_000_01)).isFalse()
    }

    @Test
    fun `salary arriving the same minute as a payment is not a round trip`() {
        // Same instant, but nothing like the same amount.
        assertThat(
            pairs(at(2, 10, 50), at(2, 10, 50), debitAmount = 84_200, creditAmount = 58_200_00)
        ).isFalse()
    }
}

package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.TxnType
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * One credit-card bill, two banks, two messages.
 *
 * The paying account texts with a timestamp; the card issuer texts with a date and no
 * clock time, so its row lands at midnight and the pair sits most of a day apart. No
 * ordinary dedupe window reaches that, and on the first real ledger it meant
 * Rs.7,177.79 was counted twice — once leaving the bank, once arriving on the card.
 *
 * Written mostly as refusals, because the repair deletes rows. Two ₹10,000 movements on
 * the same day from the same bank turned out to be an FD maturing and a rent payment —
 * a household's real money, one row of which would have been destroyed by a rule that
 * matched on amount and date alone.
 */
class CardBillEchoTest {

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(2026, 8, day).atStartOfDay(MonthlyAggregator.ZONE)
            .plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    /** The predicate the repair applies. */
    private fun isEcho(
        debitAt: Long, echoAt: Long,
        debitAmount: Long = 717_779, echoAmount: Long = 717_779,
        debitBank: String = "Kerala Gramin Bank", echoBank: String = "ICICI Bank",
        echoCategory: String = Category.CARD_PAYMENT.name,
        debitType: String = TxnType.DEBIT.name,
    ): Boolean =
        debitType == TxnType.DEBIT.name &&
            debitAmount == echoAmount &&
            debitBank != echoBank &&
            echoCategory == Category.CARD_PAYMENT.name &&
            abs(echoAt - debitAt) <= SmsDeduplicator.CARD_BILL_WINDOW_MS

    @Test
    fun `the real pair is collapsed`() {
        // KGB debited 19:43:47; ICICI acknowledged with a date only, so midnight.
        assertThat(isEcho(debitAt = at(3, 19, 43), echoAt = at(3, 0, 0))).isTrue()
    }

    @Test
    fun `an acknowledgement the next morning still counts`() {
        assertThat(isEcho(debitAt = at(3, 22, 10), echoAt = at(4, 0, 0))).isTrue()
    }

    // ─── what it must never destroy ─────────────────────────────────────────

    /**
     * Two ₹10,000 movements on 2 August from Federal Bank: a fixed deposit maturing and
     * rent paid to a person. Same amount, same day, same bank, opposite directions —
     * and both are real. Neither is an echo of the other.
     */
    @Test
    fun `an FD maturing and a rent payment of the same amount are both kept`() {
        assertThat(
            isEcho(
                debitAt = at(2, 10, 51), echoAt = at(2, 10, 50),
                debitAmount = 10_000_00, echoAmount = 10_000_00,
                debitBank = "Federal Bank", echoBank = "Federal Bank",
                echoCategory = Category.INCOME.name,
            )
        ).isFalse()
    }

    @Test
    fun `a card payment from the same bank is not an echo`() {
        // Same bank means one message, not two banks describing one payment.
        assertThat(
            isEcho(
                debitAt = at(3, 19, 43), echoAt = at(3, 0, 0),
                debitBank = "ICICI Bank", echoBank = "ICICI Bank",
            )
        ).isFalse()
    }

    @Test
    fun `a different amount is a different bill`() {
        assertThat(isEcho(at(3, 19, 43), at(3, 0, 0), echoAmount = 717_780)).isFalse()
    }

    @Test
    fun `two days apart is not one bill`() {
        assertThat(isEcho(debitAt = at(3, 19, 43), echoAt = at(5, 0, 0))).isFalse()
    }

    @Test
    fun `an ordinary expense is never treated as an echo`() {
        assertThat(
            isEcho(at(3, 19, 43), at(3, 0, 0), echoCategory = Category.GROCERIES.name)
        ).isFalse()
    }
}

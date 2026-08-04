package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/**
 * The rule that decides whether two rows are the same payment recorded twice.
 *
 * Fixing the dropped AM/PM meridiem was correct, but on a database already holding
 * evening rows stored as morning, the next scan re-read those messages, arrived twelve
 * hours later, and found nothing within the three-minute dedupe window to match. Every
 * PM transaction already imported gained a copy.
 *
 * This pins the signature used to clean that up, and it is written from the direction
 * of what must **not** be deleted. The repair removes rows, and losing a real
 * transaction is far worse than leaving a duplicate: a duplicate is visible and one tap
 * removes it, a missing row is invisible forever.
 */
class MeridiemTwinTest {

    private val twelveHours = 12 * 60 * 60 * 1000L
    private val tolerance = 60 * 1000L

    /** Agree where both know something; ignore where one is silent. */
    private fun agrees(a: String?, b: String?) =
        a.isNullOrBlank() || b.isNullOrBlank() || a == b

    /** The predicate as the repair applies it. */
    private fun isTwin(
        aAt: Long, bAt: Long,
        aAmount: Long = 100_00, bAmount: Long = 100_00,
        aBank: String? = "Kerala Gramin Bank", bBank: String? = "Kerala Gramin Bank",
        aTail: String? = "4657", bTail: String? = "4657",
        aRef: String? = null, bRef: String? = null,
        aType: String = "DEBIT", bType: String = "DEBIT",
    ): Boolean =
        aAmount == bAmount && aBank == bBank && aType == bType &&
            agrees(aTail, bTail) && agrees(aRef, bRef) &&
            abs(abs(aAt - bAt) - twelveHours) <= tolerance

    private fun at(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 8, 3).atStartOfDay(MonthlyAggregator.ZONE)
            .plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    // ─── what it must catch ─────────────────────────────────────────────────

    @Test
    fun `the exact pair seen on a real phone is a twin`() {
        // "Rs.1778.00 on 03/08/26 07:49 PM" — stored once as 07:49, once as 19:49.
        assertThat(isTwin(at(7, 49), at(19, 49))).isTrue()
    }

    @Test
    fun `a minute of drift is still a twin`() {
        assertThat(isTwin(at(8, 14), at(20, 14) + 45_000)).isTrue()
    }

    // ─── what it must never touch ───────────────────────────────────────────

    @Test
    fun `two payments eleven hours apart are left alone`() {
        assertThat(isTwin(at(8, 0), at(19, 0))).isFalse()
    }

    @Test
    fun `two payments thirteen hours apart are left alone`() {
        assertThat(isTwin(at(7, 0), at(20, 0))).isFalse()
    }

    @Test
    fun `same time, different amount, is two payments`() {
        assertThat(isTwin(at(7, 49), at(19, 49), aAmount = 100_00, bAmount = 250_00))
            .isFalse()
    }

    @Test
    fun `same amount from different banks is not a twin`() {
        // The 7,177.79 pair on the real ledger: one bank paid, the other was paid.
        // Twelve hours apart it would otherwise qualify, and deleting either would
        // lose a real record.
        assertThat(
            isTwin(at(7, 43), at(19, 43), aBank = "ICICI Bank", bBank = "Kerala Gramin Bank")
        ).isFalse()
    }

    @Test
    fun `same amount on a different account is not a twin`() {
        assertThat(isTwin(at(7, 49), at(19, 49), aTail = "4657", bTail = "9911")).isFalse()
    }

    /**
     * The shape actually found on the phone, and the reason the first attempt at this
     * repair matched nothing: the two messages describing one payment never carry the
     * same fields. The bank names an account and no reference; the UPI app names a
     * reference and no account.
     */
    @Test
    fun `a bank row and a UPI row for one payment are a twin`() {
        assertThat(
            isTwin(
                at(7, 49), at(19, 49),
                aTail = null, bTail = "3062",
                aRef = "658119750447", bRef = null,
            )
        ).isTrue()
    }

    @Test
    fun `different references still mean different payments`() {
        assertThat(
            isTwin(at(7, 49), at(19, 49), aRef = "111111111111", bRef = "222222222222")
        ).isFalse()
    }

    @Test
    fun `a debit and a credit are never the same payment`() {
        assertThat(isTwin(at(7, 49), at(19, 49), aType = "DEBIT", bType = "CREDIT"))
            .isFalse()
    }

    @Test
    fun `a genuine twice-daily payment more than a minute off survives`() {
        // Someone paying the same amount morning and evening is unusual but real. Only
        // an offset within a minute of exactly twelve hours is treated as the artefact.
        assertThat(isTwin(at(7, 49), at(19, 52))).isFalse()
    }
}

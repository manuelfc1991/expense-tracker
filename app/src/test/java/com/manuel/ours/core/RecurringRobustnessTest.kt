package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.RecurringDetector
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * A commitment the detector drops is a commitment pacing spends twice.
 *
 * Both defects here end the same way: `monthlyCommitted` comes out wrong, `Pacing` divides
 * the wrong discretionary budget, and a month that is not on course is reported as though
 * it were.
 */
class RecurringRobustnessTest {

    private val day = 24 * 3_600_000L

    private fun charge(rupees: Long, daysAgo: Long, merchant: String = "Netflix") = Transaction(
        id = "$merchant$daysAgo",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = merchant,
        category = Category.ENTERTAINMENT,
        occurredAt = 1_800_000_000_000L - daysAgo * day,
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
    )

    /**
     * One odd figure used to delete the whole charge: `rows.any { … } -> return null`. A
     * single ₹99 month against a ₹649 subscription is 85% off the median, so twenty-four
     * months of commitment disappeared and the budget looked ₹649 a month roomier.
     */
    @Test
    fun `one outlier does not delete a year of subscription`() {
        val rows = (0..11).map { charge(649, daysAgo = it * 30L) } + charge(99, daysAgo = 365)
        val found = RecurringDetector.detect(rows.sortedBy { it.occurredAt })

        val netflix = found.singleOrNull { it.merchant.equals("Netflix", ignoreCase = true) }
        assertThat(netflix).isNotNull()
        assertThat(netflix!!.typicalPaise).isEqualTo(649_00)
    }

    /**
     * WEEKLY is declared first and its ×2 window covers 10–18 days, so a fortnightly
     * charge matched it before MONTHLY was tried — and `monthlyEquivalentPaise` then
     * multiplies by 52/12, turning ₹1,000 a fortnight into ₹4,333 a month.
     */
    @Test
    fun `a fortnightly charge is not counted as weekly`() {
        val rows = (0..7).map { charge(1_000, daysAgo = it * 14L, merchant = "Cleaner") }
        val found = RecurringDetector.detect(rows.sortedBy { it.occurredAt })
        val cleaner = found.single { it.merchant.equals("Cleaner", ignoreCase = true) }

        // Whatever cadence it settles on, the monthly cost must be about ₹2,167 —
        // certainly not the ₹4,333 that WEEKLY produced.
        assertThat(cleaner.monthlyEquivalentPaise).isLessThan(3_000_00)
    }

    /** A genuinely weekly charge is still weekly. */
    @Test
    fun `a weekly charge is still weekly`() {
        val rows = (0..9).map { charge(200, daysAgo = it * 7L, merchant = "Milk") }
        val found = RecurringDetector.detect(rows.sortedBy { it.occurredAt })
        val milk = found.single { it.merchant.equals("Milk", ignoreCase = true) }
        assertThat(milk.cadence).isEqualTo(RecurringCharge.Cadence.WEEKLY)
    }

    /** And a monthly one is still monthly, at its face value. */
    @Test
    fun `a monthly charge keeps its figure`() {
        val rows = (0..5).map { charge(15_000, daysAgo = it * 30L, merchant = "Rent") }
        val found = RecurringDetector.detect(rows.sortedBy { it.occurredAt })
        val rent = found.single { it.merchant.equals("Rent", ignoreCase = true) }
        assertThat(rent.cadence).isEqualTo(RecurringCharge.Cadence.MONTHLY)
        assertThat(rent.monthlyEquivalentPaise).isEqualTo(15_000_00)
    }
}

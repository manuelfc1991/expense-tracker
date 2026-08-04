package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.RecurringDetector
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import org.junit.Test
import java.time.LocalDate

class RecurringDetectorTest {

    private val zone = MonthlyAggregator.ZONE

    private fun at(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 60 * 60 * 1000

    private var seq = 0

    private fun txn(
        merchant: String,
        rupees: Long,
        date: LocalDate,
        type: TxnType = TxnType.DEBIT,
        category: Category = Category.ENTERTAINMENT,
        deleted: Boolean = false,
    ) = Transaction(
        id = "t${seq++}",
        amountPaise = rupees * 100,
        type = type,
        merchant = merchant,
        category = category,
        occurredAt = at(date),
        ownerUid = "uid-me",
        ownerName = "Manuel",
        deleted = deleted,
    )

    /** The same charge on the same day of the month, n times running. */
    private fun monthly(
        merchant: String,
        rupees: Long,
        months: Int,
        day: Int = 12,
        startMonth: Int = 1,
        category: Category = Category.ENTERTAINMENT,
    ) = (0 until months).map {
        txn(merchant, rupees, LocalDate.of(2026, startMonth + it, day), category = category)
    }

    // ─── What it should find ────────────────────────────────────────────────

    @Test
    fun `three identical monthly charges are a subscription`() {
        val found = RecurringDetector.detect(monthly("Netflix", 649, months = 3))

        assertThat(found).hasSize(1)
        with(found.single()) {
            assertThat(merchant).isEqualTo("Netflix")
            assertThat(cadence).isEqualTo(RecurringCharge.Cadence.MONTHLY)
            assertThat(typicalPaise).isEqualTo(64_900)
            assertThat(occurrences).isEqualTo(3)
        }
    }

    @Test
    fun `next expected date steps a full period from the last sighting`() {
        val found = RecurringDetector.detect(monthly("Spotify", 119, months = 4)).single()

        val lastSeen = at(LocalDate.of(2026, 4, 12))
        assertThat(found.lastSeenAt).isEqualTo(lastSeen)
        assertThat(found.nextExpectedAt)
            .isEqualTo(lastSeen + 30L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `weekly, quarterly and yearly cadences are told apart`() {
        val weekly = (0 until 4).map { txn("Milk Co", 210, LocalDate.of(2026, 1, 5).plusWeeks(it.toLong())) }
        val quarterly = (0 until 3).map { txn("Domain Host", 900, LocalDate.of(2026, 1, 8).plusMonths(3L * it)) }
        val yearly = (0 until 3).map { txn("Car Insurance", 14_000, LocalDate.of(2022, 6, 3).plusYears(it.toLong())) }

        val byMerchant = RecurringDetector.detect(weekly + quarterly + yearly)
            .associateBy { it.merchant }

        assertThat(byMerchant["Milk Co"]?.cadence).isEqualTo(RecurringCharge.Cadence.WEEKLY)
        assertThat(byMerchant["Domain Host"]?.cadence).isEqualTo(RecurringCharge.Cadence.QUARTERLY)
        assertThat(byMerchant["Car Insurance"]?.cadence).isEqualTo(RecurringCharge.Cadence.YEARLY)
    }

    @Test
    fun `a missed month does not hide a long-running subscription`() {
        // Feb never arrived — the bank skipped an SMS, or the sender was unrecognised.
        val rows = listOf(
            txn("Prime", 179, LocalDate.of(2026, 1, 9)),
            txn("Prime", 179, LocalDate.of(2026, 3, 9)),
            txn("Prime", 179, LocalDate.of(2026, 4, 9)),
            txn("Prime", 179, LocalDate.of(2026, 5, 9)),
        )

        val found = RecurringDetector.detect(rows).single()
        assertThat(found.cadence).isEqualTo(RecurringCharge.Cadence.MONTHLY)
    }

    @Test
    fun `a modest price rise still counts as the same charge`() {
        val rows = listOf(
            txn("Gym", 1_000, LocalDate.of(2026, 1, 2)),
            txn("Gym", 1_000, LocalDate.of(2026, 2, 2)),
            txn("Gym", 1_150, LocalDate.of(2026, 3, 2)),
            txn("Gym", 1_150, LocalDate.of(2026, 4, 2)),
        )

        val found = RecurringDetector.detect(rows).single()
        // Median, not mean: the rise should not drag the figure to something that was
        // never actually charged.
        assertThat(found.typicalPaise).isEqualTo(107_500)
    }

    @Test
    fun `merchant casing and padding do not split one subscription into two`() {
        val rows = listOf(
            txn("Netflix", 649, LocalDate.of(2026, 1, 12)),
            txn("  netflix ", 649, LocalDate.of(2026, 2, 12)),
            txn("NETFLIX", 649, LocalDate.of(2026, 3, 12)),
        )

        assertThat(RecurringDetector.detect(rows)).hasSize(1)
    }

    // ─── What it must not find ──────────────────────────────────────────────

    @Test
    fun `two occurrences are a coincidence, not a pattern`() {
        assertThat(RecurringDetector.detect(monthly("Netflix", 649, months = 2))).isEmpty()
    }

    @Test
    fun `weekly groceries at one shop are a habit, not a subscription`() {
        // Regular as clockwork, but the amounts are nothing alike — there is no
        // commitment here to cancel or budget for.
        val rows = listOf(
            txn("Reliance Smart", 2_140, LocalDate.of(2026, 1, 4), category = Category.GROCERIES),
            txn("Reliance Smart", 780, LocalDate.of(2026, 1, 11), category = Category.GROCERIES),
            txn("Reliance Smart", 3_960, LocalDate.of(2026, 1, 18), category = Category.GROCERIES),
            txn("Reliance Smart", 1_205, LocalDate.of(2026, 1, 25), category = Category.GROCERIES),
        )

        assertThat(RecurringDetector.detect(rows)).isEmpty()
    }

    @Test
    fun `same amount at irregular intervals is not recurring`() {
        val rows = listOf(
            txn("Cafe", 300, LocalDate.of(2026, 1, 3)),
            txn("Cafe", 300, LocalDate.of(2026, 1, 9)),
            txn("Cafe", 300, LocalDate.of(2026, 3, 21)),
            txn("Cafe", 300, LocalDate.of(2026, 3, 28)),
        )

        assertThat(RecurringDetector.detect(rows)).isEmpty()
    }

    @Test
    fun `unknown payee is never treated as one recurring merchant`() {
        val rows = (0 until 4).map {
            txn("Unknown payee", 5_000, LocalDate.of(2026, 1, 6).plusMonths(it.toLong()))
        }

        assertThat(RecurringDetector.detect(rows)).isEmpty()
    }

    @Test
    fun `credits are not commitments`() {
        val salary = (0 until 4).map {
            txn("Acme Payroll", 58_200, LocalDate.of(2026, 1, 1).plusMonths(it.toLong()),
                type = TxnType.CREDIT)
        }

        assertThat(RecurringDetector.detect(salary)).isEmpty()
    }

    @Test
    fun `deleted rows do not keep a cancelled subscription alive`() {
        val rows = monthly("Netflix", 649, months = 3).map { it.copy(deleted = true) }

        assertThat(RecurringDetector.detect(rows)).isEmpty()
    }

    // ─── Ranking and arithmetic ─────────────────────────────────────────────

    @Test
    fun `monthly equivalent puts every cadence on one scale`() {
        val quarterly = (0 until 3).map {
            txn("Host", 1_200, LocalDate.of(2026, 1, 8).plusMonths(3L * it))
        }

        val found = RecurringDetector.detect(quarterly).single()
        assertThat(found.typicalPaise).isEqualTo(120_000)
        assertThat(found.monthlyEquivalentPaise).isEqualTo(40_000)
    }

    @Test
    fun `the biggest monthly commitment ranks first, not the most frequent`() {
        // ₹200 a week is ₹866 a month; ₹2,000 a month is ₹2,000. The weekly charge
        // recurs far more often and still matters less.
        val weekly = (0 until 5).map { txn("Milk Co", 200, LocalDate.of(2026, 1, 5).plusWeeks(it.toLong())) }
        val emi = monthly("Home Loan EMI", 2_000, months = 4, day = 3, category = Category.EMI)

        val found = RecurringDetector.detect(weekly + emi)

        assertThat(found.map { it.merchant })
            .containsExactly("Home Loan EMI", "Milk Co").inOrder()
    }

    @Test
    fun `the category is the one most of the occurrences agreed on`() {
        val rows = listOf(
            txn("Airtel", 999, LocalDate.of(2026, 1, 15), category = Category.BILLS),
            txn("Airtel", 999, LocalDate.of(2026, 2, 15), category = Category.OTHER),
            txn("Airtel", 999, LocalDate.of(2026, 3, 15), category = Category.BILLS),
        )

        assertThat(RecurringDetector.detect(rows).single().category)
            .isEqualTo(Category.BILLS)
    }
}

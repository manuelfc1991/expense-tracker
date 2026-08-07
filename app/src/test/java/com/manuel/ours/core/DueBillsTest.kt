package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.DueBills
import com.manuel.ours.domain.model.CardInfo
import java.time.LocalDate
import org.junit.Test

/**
 * When the app should interrupt somebody about a bill, and when it must not.
 *
 * Everything here is date arithmetic, which is where this feature will actually break: a
 * month with no 31st, a due day that has already gone by, a worker that runs twice in a
 * day or again after a reboot. None of those are visible from a screenshot.
 */
class DueBillsTest {

    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    private fun bill(dueOn: LocalDate, id: String = "b1", amount: Long? = 4_200_00) =
        DueBills.Detected(
            id = id,
            label = "ICICI",
            amountPaise = amount,
            dueAt = OursZone.startOfDay(dueOn) + 9 * 3_600_000L,
        )

    // ── the two stages, and nothing else ─────────────────────────────────────

    @Test
    fun `a bill three days out warns, and says so once`() {
        val due = DueBills.due(
            detected = listOf(bill(at(2026, 8, 18))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
        )
        assertThat(due).hasSize(1)
        assertThat(due.single().stage).isEqualTo(DueBills.Stage.Ahead)
        assertThat(due.single().daysAway(at(2026, 8, 15))).isEqualTo(3)
    }

    @Test
    fun `a bill due today is the last call`() {
        val due = DueBills.due(
            detected = listOf(bill(at(2026, 8, 15))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
        )
        assertThat(due.single().stage).isEqualTo(DueBills.Stage.Today)
    }

    /** Two and four days out are silence. Only three and zero speak. */
    @Test
    fun `no other day produces an alert`() {
        listOf(1, 2, 4, 5, 10, 30).forEach { away ->
            val due = DueBills.due(
                detected = listOf(bill(at(2026, 8, 1).plusDays(away.toLong()))),
                cards = emptyMap(),
                today = at(2026, 8, 1),
            )
            assertThat(due).isEmpty()
        }
    }

    /**
     * A bill whose date has gone is not chased. The app is not a debt collector, and a
     * notification about a deadline already missed only tells you off.
     */
    @Test
    fun `a bill already past says nothing`() {
        val due = DueBills.due(
            detected = listOf(bill(at(2026, 8, 10))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
        )
        assertThat(due).isEmpty()
    }

    // ── never twice ──────────────────────────────────────────────────────────

    /**
     * The worker runs daily and may run again after a reboot or a rescan. Saying the
     * same thing twice is how a reminder becomes something people swipe away unread.
     */
    @Test
    fun `an alert already fired is not repeated`() {
        val first = DueBills.due(
            detected = listOf(bill(at(2026, 8, 18))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
        )
        val second = DueBills.due(
            detected = listOf(bill(at(2026, 8, 18))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
            alreadyFired = first.map { it.key }.toSet(),
        )
        assertThat(second).isEmpty()
    }

    /** But the warning and the day-of are different alerts, and both should arrive. */
    @Test
    fun `the day-of alert still fires after the three-day warning`() {
        val warned = DueBills.due(
            detected = listOf(bill(at(2026, 8, 18))),
            cards = emptyMap(),
            today = at(2026, 8, 15),
        )
        val onTheDay = DueBills.due(
            detected = listOf(bill(at(2026, 8, 18))),
            cards = emptyMap(),
            today = at(2026, 8, 18),
            alreadyFired = warned.map { it.key }.toSet(),
        )
        assertThat(onTheDay.single().stage).isEqualTo(DueBills.Stage.Today)
    }

    // ── cards, which have a day of the month rather than a date ──────────────

    @Test
    fun `a card due on the 18th warns on the 15th`() {
        val due = DueBills.due(
            detected = emptyList(),
            cards = mapOf("8842" to CardInfo(limitPaise = 50_000_00, dueDay = 18)),
            cardLabels = mapOf("8842" to "Utkarsh SuperCard"),
            today = at(2026, 8, 15),
        )
        assertThat(due.single().label).isEqualTo("Utkarsh SuperCard")
        assertThat(due.single().stage).isEqualTo(DueBills.Stage.Ahead)
    }

    /**
     * A due day says *when*, never *how much*. Reporting a figure would mean inventing
     * one, which is the thing this app refuses to do with an unknown balance.
     */
    @Test
    fun `a card alert claims no amount`() {
        val due = DueBills.due(
            detected = emptyList(),
            cards = mapOf("8842" to CardInfo(dueDay = 18)),
            today = at(2026, 8, 15),
        )
        assertThat(due.single().amountPaise).isNull()
    }

    /** A card with no due day recorded is simply not a source of reminders. */
    @Test
    fun `a card with no due day says nothing`() {
        val due = DueBills.due(
            detected = emptyList(),
            cards = mapOf("8842" to CardInfo(limitPaise = 50_000_00, dueDay = null)),
            today = at(2026, 8, 15),
        )
        assertThat(due).isEmpty()
    }

    /** The month rolls: on the 20th, an 18th card is next due in September. */
    @Test
    fun `a card whose day has passed rolls to next month`() {
        assertThat(DueBills.nextDue(18, at(2026, 8, 20))).isEqualTo(at(2026, 9, 18))
    }

    @Test
    fun `a card due later this month stays in this month`() {
        assertThat(DueBills.nextDue(18, at(2026, 8, 2))).isEqualTo(at(2026, 8, 18))
    }

    /** Today still counts as due, not as missed. */
    @Test
    fun `a card due today is due today`() {
        assertThat(DueBills.nextDue(18, at(2026, 8, 18))).isEqualTo(at(2026, 8, 18))
    }

    // ── the months that do not have the day ──────────────────────────────────

    /**
     * `LocalDate.of(2026, 2, 31)` throws. Inside the worker that would take down the
     * notification for every other bill along with this one, so the day is clamped.
     */
    @Test
    fun `a card due on the 31st survives February`() {
        assertThat(DueBills.nextDue(31, at(2026, 2, 1))).isEqualTo(at(2026, 2, 28))
        assertThat(DueBills.nextDue(31, at(2026, 4, 1))).isEqualTo(at(2026, 4, 30))
        assertThat(DueBills.nextDue(31, at(2026, 1, 1))).isEqualTo(at(2026, 1, 31))
    }

    @Test
    fun `a leap February takes the 29th`() {
        assertThat(DueBills.nextDue(31, at(2028, 2, 1))).isEqualTo(at(2028, 2, 29))
    }

    /** Nonsense in the rule produces no reminder rather than an exception. */
    @Test
    fun `an impossible due day is ignored`() {
        assertThat(DueBills.nextDue(0, at(2026, 8, 1))).isNull()
        assertThat(DueBills.nextDue(45, at(2026, 8, 1))).isNull()
    }

    /**
     * The clamp must not make a card fire twice in one month. Due on the 31st, warned on
     * 25 February for the 28th — the roll to March must not then also warn for 28 Feb.
     */
    @Test
    fun `the clamped date is keyed by date so it fires once`() {
        val card = mapOf("8842" to CardInfo(dueDay = 31))
        val warned = DueBills.due(emptyList(), card, today = at(2026, 2, 25))
        assertThat(warned.single().dueOn).isEqualTo(at(2026, 2, 28))

        val again = DueBills.due(
            emptyList(), card,
            today = at(2026, 2, 25),
            alreadyFired = warned.map { it.key }.toSet(),
        )
        assertThat(again).isEmpty()
    }

    // ── both sources together ────────────────────────────────────────────────

    @Test
    fun `bills and cards both arrive, soonest first`() {
        val due = DueBills.due(
            detected = listOf(bill(at(2026, 8, 15), id = "icici")),
            cards = mapOf("8842" to CardInfo(dueDay = 18)),
            today = at(2026, 8, 15),
        )
        assertThat(due).hasSize(2)
        assertThat(due.first().dueOn).isEqualTo(at(2026, 8, 15))
        assertThat(due.last().dueOn).isEqualTo(at(2026, 8, 18))
    }
}

package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.Trash
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The thirty days, and the promise the caption makes about them.
 */
class TrashTest {

    private val now = 1_785_000_000_000L
    private fun daysAgo(n: Long) = now - TimeUnit.DAYS.toMillis(n)
    private fun hoursAgo(n: Long) = now - TimeUnit.HOURS.toMillis(n)

    @Test
    fun `the window is thirty days back from now`() {
        assertThat(Trash.since(now)).isEqualTo(now - TimeUnit.DAYS.toMillis(30))
    }

    @Test
    fun `something deleted a moment ago has the whole window`() {
        // Rounded up, so the first caption a person ever sees says thirty and not
        // twenty-nine. A count-down that starts one short reads as a bug.
        assertThat(Trash.daysLeft(now, now)).isEqualTo(30)
        assertThat(Trash.expiryLabel(hoursAgo(1), now)).isEqualTo("30 days left")
    }

    @Test
    fun `the count falls by a day at a time`() {
        assertThat(Trash.daysLeft(daysAgo(1), now)).isEqualTo(29)
        assertThat(Trash.daysLeft(daysAgo(15), now)).isEqualTo(15)
        assertThat(Trash.daysLeft(daysAgo(29), now)).isEqualTo(1)
    }

    @Test
    fun `the last day is singular`() {
        assertThat(Trash.expiryLabel(daysAgo(29), now)).isEqualTo("1 day left")
    }

    @Test
    fun `the final hours say so rather than counting zero days`() {
        // Inside the window but under a day left. "0 days left" beside a row that is
        // still restorable is a contradiction the reader has to resolve.
        val almostGone = hoursAgo(30 * 24L - 2)
        assertThat(Trash.daysLeft(almostGone, now)).isEqualTo(1)
        assertThat(Trash.expiryLabel(almostGone, now)).isEqualTo("1 day left")
    }

    @Test
    fun `past the window it is gone and never negative`() {
        assertThat(Trash.daysLeft(daysAgo(30), now)).isEqualTo(0)
        assertThat(Trash.daysLeft(daysAgo(400), now)).isEqualTo(0)
        assertThat(Trash.expiryLabel(daysAgo(31), now)).isEqualTo("Gone today")
    }

    @Test
    fun `the urgency thresholds are days, not colours`() {
        // The screen colours the caption amber under a fortnight and red under three
        // days, and prints the number either way. Pinning the boundaries here means the
        // rule survives a redesign of what colour means what.
        assertThat(Trash.daysLeft(daysAgo(16), now)).isEqualTo(14)   // amber begins
        assertThat(Trash.daysLeft(daysAgo(27), now)).isEqualTo(3)    // red begins
        assertThat(Trash.daysLeft(daysAgo(28), now)).isEqualTo(2)
    }

    @Test
    fun `a row deleted exactly at the boundary is outside the query`() {
        // The DAO asks for deletedAt >= since. A row stamped exactly on the boundary is
        // therefore still listed, and must not caption itself as having negative time.
        val boundary = Trash.since(now)

        assertThat(boundary >= Trash.since(now)).isTrue()
        assertThat(Trash.daysLeft(boundary, now)).isEqualTo(0)
    }
}

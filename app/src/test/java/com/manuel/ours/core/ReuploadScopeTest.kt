package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.ReuploadTally
import org.junit.Test

/**
 * What a re-upload admits to leaving behind.
 *
 * The rule under test is not "which rows are sent" — that was always right, and retiring
 * a month is meant to keep it off the sheet. It is whether the phone says so. "Queued 214
 * expenses" for a household holding 460 was a success message for a partial upload, and
 * the number was the only clue.
 */
class ReuploadScopeTest {

    private val cutoff = "1 Feb 2026"

    @Test
    fun `no cutoff set says only what was queued`() {
        val line = ReuploadTally(queued = 460, retired = 0).statusLine(cutoffLabel = null)

        assertThat(line).isEqualTo("Queued 460 expenses to upload")
    }

    @Test
    fun `nothing retired reads the same even with a cutoff`() {
        // A cutoff older than the whole history withholds nothing. Mentioning it anyway
        // would teach the household to ignore a line that usually means nothing.
        val line = ReuploadTally(queued = 460, retired = 0).statusLine(cutoff)

        assertThat(line).isEqualTo("Queued 460 expenses to upload")
    }

    @Test
    fun `a retired month is named, counted and dated`() {
        val line = ReuploadTally(queued = 214, retired = 246).statusLine(cutoff)

        assertThat(line).contains("Queued 214 expenses")
        assertThat(line).contains("246 expenses")
        assertThat(line).contains("1 Feb 2026")
        assertThat(line).contains("retired")
    }

    @Test
    fun `everything retired does not read as a failure`() {
        // The case that looks broken and is not: a cutoff later than every stored row.
        // "Queued 0 expenses to upload" is what a dead URL or a stale script produces,
        // and sending someone to debug their Apps Script deployment over a date they set
        // themselves is the worst version of this bug, not the mildest.
        val line = ReuploadTally(queued = 0, retired = 88).statusLine(cutoff)

        assertThat(line).startsWith("Nothing to upload")
        assertThat(line).contains("88 expenses")
        assertThat(line).contains("before 1 Feb 2026")
        assertThat(line).doesNotContain("Queued 0")
    }

    @Test
    fun `counts of one are not pluralised`() {
        assertThat(ReuploadTally(queued = 1, retired = 0).statusLine(null))
            .isEqualTo("Queued 1 expense to upload")
        assertThat(ReuploadTally(queued = 5, retired = 1).statusLine(cutoff))
            .contains("1 expense from before")
    }

    @Test
    fun `a retired count with no date to attach it to stays quiet`() {
        // Defensive: the caller passes null only when no cutoff is set, in which case
        // nothing can be retired. If those two ever disagree, say the safe half rather
        // than printing "before null".
        val line = ReuploadTally(queued = 10, retired = 3).statusLine(cutoffLabel = null)

        assertThat(line).isEqualTo("Queued 10 expenses to upload")
    }
}

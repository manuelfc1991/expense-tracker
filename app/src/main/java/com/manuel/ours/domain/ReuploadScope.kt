package com.manuel.ours.domain

/**
 * What a re-upload actually sent, and what it deliberately did not.
 *
 * "Re-upload everything" honours the tracking cutoff, and that is correct: retiring a
 * month is a statement about what the household counts as theirs, not about what one
 * screen draws, so a retired month is kept off the sheet and away from the other phone.
 *
 * The bug was never the rule — it was the silence. The button says *everything*, the
 * status said "Queued N expenses", and N being a fraction of the history was the only
 * hint that months had been left behind. A household that retired anything got a
 * confident success message for a partial upload, and the README called it the single
 * most confusing thing in the app.
 *
 * So the count of what was withheld travels with the count of what was sent, and the
 * message names both. Anything a sync bounds on purpose has to say so out loud.
 */
data class ReuploadTally(val queued: Int, val retired: Int) {

    /**
     * The line shown under the Sheet settings once a re-upload finishes.
     *
     * @param cutoffLabel the tracking-start date, already formatted for display, or null
     *   when no cutoff is set — in which case there is nothing to disclose and the
     *   message stays as short as it always was.
     */
    fun statusLine(cutoffLabel: String?): String {
        val expenses = if (queued == 1) "1 expense" else "$queued expenses"
        if (retired == 0 || cutoffLabel == null) return "Queued $expenses to upload"

        val held = if (retired == 1) "1 expense" else "$retired expenses"
        // Nothing queued at all is its own answer. "Queued 0 expenses" reads as a
        // failure — a dead URL, a broken script — when in fact everything the phone
        // holds is simply older than the date the household chose to start at.
        if (queued == 0) {
            return "Nothing to upload: all $held are from before $cutoffLabel, " +
                "which is where tracking starts."
        }
        return "Queued $expenses. $held from before $cutoffLabel stayed behind — " +
            "those months are retired."
    }
}

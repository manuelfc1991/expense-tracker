package com.manuel.ours.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the bottom bar needs to know, which is nothing to do with any one screen.
 *
 * ## Why a badge exists at all
 *
 * The untagged count lived **only on Home**, inside a card the mockup draws only while something
 * is untagged. So a household with ninety-four unsorted rows had no idea until it happened to
 * open Home and scroll — and the moment the last row was categorised, the only route into Sort
 * disappeared with the card.
 *
 * A count on the tab is the fix: visible from every screen, and it says how much work is waiting
 * without anyone having to go looking for it.
 *
 * ## Why it is its own ViewModel
 *
 * The bar outlives every screen, so reading this from `HomeViewModel` would mean the badge only
 * updated while Home happened to be composed. Scoped to the nav host, it is one collector for
 * the life of the app rather than one per screen.
 */
@HiltViewModel
class NavBadgeViewModel @Inject constructor(
    repository: TransactionRepository,
    prefs: AppPrefs,
) : ViewModel() {

    data class Badges(
        /** Rows with no category. The number on the Activity tab. */
        val untagged: Int = 0,
        /**
         * Something needs a decision — a delete request waiting on the owner.
         *
         * A dot rather than a number: unlike untagged rows this is not a quantity of work you
         * chip away at, it is a question you answer, and one question is as interrupting as three.
         */
        val needsAttention: Boolean = false,
    )

    val badges: StateFlow<Badges> = combine(
        repository.observeNeedsReviewCount(),
        repository.observeDeleteRequestCount(),
        prefs.householdOwner,
    ) { untagged, deleteRequests, isOwner ->
        Badges(
            untagged = untagged,
            // Only the owner can answer a delete request, so only the owner is told there is one.
            needsAttention = isOwner && deleteRequests > 0,
        )
    }.stateIn(
        scope = viewModelScope,
        // Kept warm briefly across configuration changes so a rotation does not blank the badge
        // and re-query, but dropped when the app really goes away.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Badges(),
    )
}

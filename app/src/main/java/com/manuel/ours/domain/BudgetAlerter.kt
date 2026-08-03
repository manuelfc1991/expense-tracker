package com.manuel.ours.domain

import android.content.Context
import com.manuel.ours.core.Money
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.db.BudgetDao
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.db.toDomain
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.domain.model.Category
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Decides when a budget warning is worth interrupting someone for.
 *
 * The hard part is not the arithmetic, it is firing **once**. A naive check runs on
 * every incoming SMS, so crossing 80% on a Tuesday means a fresh notification for
 * every purchase for the rest of the month. That trains people to swipe budget alerts
 * away without reading them, which is worse than never sending one.
 *
 * So each (month, budget, threshold) fires at most once, and the record resets
 * naturally when the month rolls over because the month is part of the key.
 */
@Singleton
class BudgetAlerter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val txnDao: TransactionDao,
    private val prefs: AppPrefs,
) {

    data class Alert(
        val title: String,
        val body: String,
        val overBudget: Boolean,
        val key: String,
    )

    /** Returns the alerts that should be shown now, and records them as fired. */
    suspend fun checkAndConsume(): List<Alert> {
        val budgets = budgetDao.all()
        if (budgets.isEmpty()) return emptyList()

        val today = LocalDate.now(MonthlyAggregator.ZONE)
        val range = MonthlyAggregator.monthRange(today.year, today.monthValue)
        val transactions = txnDao.getBetween(range.first, range.last + 1).map { it.toDomain() }
        val monthKey = "${today.year}-${today.monthValue}"

        val alerts = mutableListOf<Alert>()

        for (budget in budgets) {
            if (budget.limitPaise <= 0) continue

            val isOverall = budget.categoryKey == AppDatabase.OVERALL_BUDGET_KEY
            val spent = if (isOverall) {
                MonthlyAggregator.totalSpent(transactions)
            } else {
                MonthlyAggregator.spentInCategory(
                    transactions,
                    Category.fromNameOrOther(budget.categoryKey),
                )
            }

            val percent = (spent * 100.0 / budget.limitPaise).roundToInt()
            // Highest threshold first: crossing straight from 60% to 105% in one
            // purchase should say "over budget", not "80% used".
            val threshold = THRESHOLDS.firstOrNull { percent >= it } ?: continue

            val key = "$monthKey:${budget.categoryKey}:$threshold"
            if (prefs.hasBudgetAlertFired(key)) continue

            val label = if (isOverall) "monthly budget"
            else Category.fromNameOrOther(budget.categoryKey).label
            val remaining = budget.limitPaise - spent

            alerts += Alert(
                title = if (threshold >= 100) {
                    "Over your $label"
                } else {
                    "$percent% of your $label used"
                },
                body = if (remaining >= 0) {
                    "${Money.format(spent)} of ${Money.format(budget.limitPaise)} · " +
                        "${Money.format(remaining)} left"
                } else {
                    "${Money.format(spent)} of ${Money.format(budget.limitPaise)} · " +
                        "${Money.format(-remaining)} over"
                },
                overBudget = threshold >= 100,
                key = key,
            )
            prefs.markBudgetAlertFired(key)
        }
        return alerts
    }

    companion object {
        /** Descending, so the most severe crossed threshold is the one reported. */
        private val THRESHOLDS = listOf(100, 80)
    }
}

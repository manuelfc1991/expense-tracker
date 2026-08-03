package com.manuel.ours.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.manuel.ours.MainActivity
import com.manuel.ours.R
import com.manuel.ours.core.Money
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.db.toDomain
import com.manuel.ours.domain.MonthlyAggregator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Home-screen widget: this month's household spend at a glance. */
class SpendWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        scope.launch {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
            val database = entryPoint.database()

            val today = LocalDate.now(MonthlyAggregator.ZONE)
            val range = MonthlyAggregator.monthRange(today.year, today.monthValue)
            val transactions = database.transactionDao()
                .getBetween(range.first, range.last + 1)
                .map { it.toDomain() }

            val spent = MonthlyAggregator.totalSpent(transactions)
            val budget = database.budgetDao().limitFor(AppDatabase.OVERALL_BUDGET_KEY)

            val views = RemoteViews(context.packageName, R.layout.widget_spend).apply {
                setTextViewText(R.id.widget_amount, Money.format(spent))
                setTextViewText(
                    R.id.widget_sub,
                    if (budget != null && budget > 0) {
                        "${(spent * 100 / budget)}% of ${Money.formatCompact(budget)}"
                    } else {
                        "${transactions.size} transactions"
                    },
                )
                setOnClickPendingIntent(R.id.widget_amount, openAppIntent(context))
            }

            appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface WidgetEntryPoint {
        fun database(): AppDatabase
    }
}

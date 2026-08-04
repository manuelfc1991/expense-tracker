package com.manuel.ours.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
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

    companion object {
        /**
         * Redraw every placed widget now.
         *
         * `updatePeriodMillis` is 30 minutes and the system treats even that as a
         * suggestion, so without this the widget showed a figure that could be half an
         * hour old — and never moved in the moment that matters, right after you spend.
         * A widget that is confidently wrong is worse than one that admits it is a
         * shortcut, because the whole point is glancing instead of opening the app.
         *
         * Broadcasting APPWIDGET_UPDATE rather than building the RemoteViews here keeps
         * one drawing path: [onUpdate] stays the only place that knows what a widget
         * looks like.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SpendWidgetProvider::class.java)
            )
            // Nothing placed on a home screen: skip the broadcast entirely rather than
            // wake the provider to do nothing.
            if (ids.isEmpty()) return

            context.sendBroadcast(
                Intent(context, SpendWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
            )
        }
    }
}

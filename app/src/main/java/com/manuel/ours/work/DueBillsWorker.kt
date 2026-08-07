package com.manuel.ours.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manuel.ours.OursApp
import com.manuel.ours.core.Money
import com.manuel.ours.core.OursZone
import com.manuel.ours.data.db.ReminderDao
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.DueBills
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Says out loud that a bill is coming, which nothing did.
 *
 * The pieces were all here and none of them were joined up. The parser understood
 * "payment is due" and pulled the date; `IngestNotifier.saveReminder` stored it; Home drew
 * a card. But the card appeared the moment the *statement* arrived — a fortnight early,
 * when there is nothing to do about it — and after that the app never mentioned it again.
 * Nothing was scheduled and no notification channel existed, so the only way to be
 * reminded on the day was to open the app on the day.
 *
 * Runs daily rather than at an exact time. An exact alarm would need
 * `SCHEDULE_EXACT_ALARM`, which is a permission this app should not be asking for to tell
 * somebody about a credit-card bill — and a bill due today is still due today whether the
 * phone says so at 9am or at 2pm.
 *
 * Which bills are due is decided by [DueBills], deliberately not here: the failures in
 * this feature are date arithmetic, and they are cheap to test as a pure function and
 * expensive to test through a Worker.
 */
@HiltWorker
class DueBillsWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val reminderDao: ReminderDao,
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = NotificationManagerCompat.from(context)
        // Nothing is marked as fired when notifications are off, so switching them on
        // does not swallow the bills that fell due while they were off.
        if (!manager.areNotificationsEnabled() || !canPost()) return Result.success()

        val today = OursZone.today()
        val fired = prefs.firedBillAlerts()

        val detected = reminderDao.observeUpcoming(0L).first()
            .filterNot { it.dismissed }
            .map {
                DueBills.Detected(
                    id = it.id,
                    label = it.bank ?: "Bill",
                    amountPaise = it.amountPaise,
                    dueAt = it.dueAt,
                )
            }

        val cards = repository.observeCards().first()
        // A card's own name, so the notification reads "Utkarsh SuperCard" rather than
        // the account key. Falls back inside DueBills when the household never named it.
        val labels = repository.observeBalances(viewerUid = "", isOwner = true).first()
            .filter { it.isCard }
            .mapNotNull { balance -> balance.bank?.let { balance.key to it } }
            .toMap()

        val due = DueBills.due(
            detected = detected,
            cards = cards,
            cardLabels = labels,
            today = today,
            alreadyFired = fired,
        )
        if (due.isEmpty()) return Result.success()

        due.forEach { alert ->
            val days = alert.daysAway(today)
            val title = if (alert.stage == DueBills.Stage.Today) {
                "${alert.label} is due today"
            } else {
                "${alert.label} is due in $days days"
            }
            // The amount only when something actually said what it is. A due day records
            // when a bill lands, never how much it is for, and inventing a figure here
            // would be the one thing this app refuses to do elsewhere.
            val body = buildString {
                alert.amountPaise?.let { append(Money.whole(it)).append(" · ") }
                append(alert.dueOn.format(DAY_MONTH))
            }
            manager.notify(
                alert.key.hashCode(),
                NotificationCompat.Builder(context, OursApp.CHANNEL_BILLS)
                    .setSmallIcon(com.manuel.ours.R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
            )
        }

        // Recorded only after they are posted, so a crash mid-loop repeats a
        // notification rather than silently losing one.
        prefs.markBillAlertsFired(due.map { it.key })
        return Result.success()
    }

    private fun canPost(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PERIODIC = "due-bills"
        private const val ONE_SHOT = "due-bills-now"

        /** "15 Aug" — the date said plainly, since the title already said how far off. */
        private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        /**
         * Checks now, as well as on the daily schedule.
         *
         * The periodic job is subject to Doze and to WorkManager's own batching — on a
         * phone left alone it can slip by hours, and a bill due *today* announced at
         * eleven at night is a bill announced too late. Opening the app is the moment the
         * household is most able to act on it.
         *
         * Safe to call on every launch: the worker skips anything already in
         * `firedBillAlerts`, so this can run a hundred times and still say each thing
         * once. `KEEP` rather than `REPLACE` so rapid restarts do not cancel a run that
         * is already under way.
         */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DueBillsWorker>().build(),
            )
        }

        /**
         * Daily, with a flex window rather than a fixed hour.
         *
         * `KEEP`, so an app restart does not reset the schedule and push the run further
         * away every time the household opens the app.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DueBillsWorker>(
                1, TimeUnit.DAYS,
                6, TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

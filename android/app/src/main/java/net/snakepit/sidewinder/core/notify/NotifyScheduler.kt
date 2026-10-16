package net.snakepit.sidewinder.core.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the background notification poll as unique periodic work.
 * Driven by the "Background notifications" toggle in SettingsScreen.
 */
object NotifyScheduler {

    private const val UNIQUE_NAME = "sidewinder-notify"

    /** Enqueues (or keeps) the 15-minute polling work. */
    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancels the polling work entirely. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_NAME)
    }

    /** One-shot catch-up check, run when the app starts with polling on so
     *  the user does not wait for the next periodic window. The worker's
     *  lastNotifiedId state makes this idempotent with the periodic run. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$UNIQUE_NAME-now",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

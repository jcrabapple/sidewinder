package net.snakepit.sidewinder.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import net.snakepit.sidewinder.MainActivity
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Notification
import net.snakepit.sidewinder.core.network.Api

/**
 * Background poll for new notifications of the active session. Port of the
 * web client's polling in src/api/client.ts, as a WorkManager worker.
 *
 * Fetches `/api/v1/notifications?since_id=<lastNotifiedId>` (no since_id on
 * the very first run, which only records the newest id without notifying),
 * posts ONE summary notification when something is new, and stores the
 * newest seen id per session. Every failure is silent: the worker always
 * returns success and never crashes the app in the background.
 */
class NotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            pollOnce(applicationContext)
        } catch (e: CancellationException) {
            // Worker cancelled (stopped/expired): re-arm so WorkManager
            // sees the cancellation instead of a silent success.
            throw e
        } catch (_: Exception) {
            // Every failure is silent: the worker never crashes the app
            // in the background.
        }
        return Result.success()
    }

    private suspend fun pollOnce(context: Context) {
        val session = SessionStore(context).active() ?: return
        val state = NotifyState(context)
        val sinceId = state.lastNotifiedId(session.id)

        val api = Api(session.base, session.token)
        val query = sinceId?.let { mapOf("since_id" to listOf(it)) } ?: emptyMap()
        val page = api.page(
            "/api/v1/notifications",
            ListSerializer(Notification.serializer()),
            query = query,
        )

        // Keep only rows strictly newer than the last delivered id; the
        // server honours since_id, this guards against ordering quirks.
        val fresh = page.items
            .filter { sinceId == null || it.id > sinceId }
            .distinctBy { it.id }
        if (fresh.isEmpty()) return

        val newestId = fresh.maxOf { it.id }
        if (sinceId != null) post(context, fresh)
        state.setLastNotifiedId(session.id, newestId)
    }

    /** One summary notification: single row text, or "N new notifications". */
    private fun post(context: Context, fresh: List<Notification>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        // Newest first, matching the notifications screen.
        val sorted = fresh.sortedByDescending { it.id }
        val newestLine = sorted.firstOrNull()?.let { rowText(it) } ?: return
        val title =
            if (sorted.size == 1) newestLine
            else "${sorted.size} new notifications"

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // MainActivity routes this to the Notifications tab.
            putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NOTIFICATIONS)
        }
        val tapPending = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(newestLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(newestLine))
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(TAG, 0, notification) }
    }

    /** Same phrasing as the notifications screen rows, screen-name first. */
    private fun rowText(notification: Notification): String {
        val account = notification.account
        val name = account.display_name?.takeIf { it.isNotBlank() } ?: "@${account.acct}"
        return when (notification.type) {
            "mention" -> "$name mentioned you"
            "status" -> "$name posted"
            "reblog" -> "$name boosted"
            "favourite" -> "$name favourited"
            "follow" -> "$name followed you"
            "follow_request" -> "$name requested to follow you"
            "poll" -> "A poll you took part in has ended"
            "update" -> "$name edited a post"
            else -> "$name (${notification.type})"
        }
    }

    private companion object {
        const val CHANNEL_ID = "notifications"
        const val TAG = "sidewinder-notify"
    }
}

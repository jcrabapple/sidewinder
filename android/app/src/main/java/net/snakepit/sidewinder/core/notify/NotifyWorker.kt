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
import net.snakepit.sidewinder.core.network.Page

private class NotifyStateReadFailure(cause: Throwable) : Exception(cause)

/**
 * Background poll for new notifications of the active session. Port of the
 * web client's polling in src/api/client.ts, as a WorkManager worker.
 *
 * Fetches `/api/v1/notifications?since_id=<lastNotifiedId>`, following next
 * links until the old watermark (up to ten pages). The very first run only
 * records the newest id without notifying. Fresh pages produce ONE summary
 * before the newest id is stored. Fetch/write failures leave the watermark
 * untouched. Unreadable state is quarantined and retried; other failures
 * return success so an already-posted summary is not duplicated.
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
        } catch (_: NotifyStateReadFailure) {
            // The bad file was quarantined when possible. Retry establishes
            // a fresh baseline instead of disabling notifications forever.
            return Result.retry()
        } catch (_: Exception) {
            // Network and post-write failures stay silent: retrying after a
            // successful system notification could duplicate that summary.
        }
        return Result.success()
    }

    private suspend fun pollOnce(context: Context) {
        val session = SessionStore(context).active() ?: return
        val state = NotifyState(context)
        val sinceId = try {
            state.lastNotifiedId(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                state.quarantineUnreadable()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A later retry can recover a transient filesystem failure.
            }
            throw NotifyStateReadFailure(e)
        }
        val api = Api(session.base, session.token)

        if (sinceId == null) {
            // Baseline only the newest visible row. Following older pages on
            // the first run would make the watermark needlessly expensive and
            // must never produce a backlog notification.
            val page = api.page(
                "/api/v1/notifications",
                ListSerializer(Notification.serializer()),
            )
            val newestId = page.items.maxOfOrNull { it.id } ?: return
            state.setLastNotifiedId(session.id, newestId)
            return
        }

        val query = mapOf("since_id" to listOf(sinceId))
        val fresh = collectFreshNotifications(sinceId) { maxId ->
            api.page(
                "/api/v1/notifications",
                ListSerializer(Notification.serializer()),
                query = query,
                maxId = maxId,
            )
        }
        if (fresh.isEmpty()) return

        post(context, fresh)
        state.setLastNotifiedId(session.id, fresh.maxOf { it.id })
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

internal const val NOTIFY_PAGE_LIMIT = 10

/**
 * Collects every row newer than [sinceId], following each page's Link cursor.
 * A page containing the old watermark ends the walk even if the server sends
 * another next link. Any fetch failure propagates before the caller notifies
 * or advances state.
 */
internal suspend fun collectFreshNotifications(
    sinceId: String,
    fetchPage: suspend (maxId: String?) -> Page<Notification>,
): List<Notification> {
    val fresh = linkedMapOf<String, Notification>()
    var maxId: String? = null

    repeat(NOTIFY_PAGE_LIMIT) {
        val page = fetchPage(maxId)
        page.items.forEach { notification ->
            if (notification.id > sinceId && notification.id !in fresh) {
                fresh[notification.id] = notification
            }
        }

        val nextMaxId = page.nextMaxId
        val crossedWatermark = page.items.any { it.id <= sinceId } ||
            (nextMaxId != null && nextMaxId <= sinceId)
        if (crossedWatermark || nextMaxId == null || nextMaxId == maxId) {
            return fresh.values.toList()
        }
        maxId = nextMaxId
    }
    return fresh.values.toList()
}

package com.claude.drawterm.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.claude.drawterm.MainActivity
import com.claude.drawterm.R
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private const val SESSION = "session"
    private const val MESSAGES = "messages"
    private const val ENDED = "ended"
    const val SESSION_ID = 1
    private val nextId = AtomicInteger(100)

    fun createChannels(ctx: Context) {
        fun channel(id: String, name: Int, desc: Int, importance: Int) =
            NotificationChannel(id, ctx.getString(name), importance).apply {
                description = ctx.getString(desc)
                setShowBadge(false)
            }
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannels(
            listOf(
                channel(SESSION, R.string.channel_session, R.string.channel_session_desc, NotificationManager.IMPORTANCE_LOW),
                channel(MESSAGES, R.string.channel_messages, R.string.channel_messages_desc, NotificationManager.IMPORTANCE_DEFAULT),
                channel(ENDED, R.string.channel_ended, R.string.channel_ended_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /** The foreground service's quiet ongoing notification. */
    fun session(ctx: Context, title: String): Notification {
        val disconnect = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, SessionService::class.java).setAction(SessionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(ctx, SESSION, ctx.getString(R.string.notif_connected, title), null, sessionIntent(ctx))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .addAction(0, ctx.getString(R.string.disconnect), disconnect)
            .build()
    }

    /** Text a Plan 9 program wrote to /dev/notification. */
    fun message(ctx: Context, text: String) {
        val body = text.trim().ifEmpty { return }
        post(ctx, builder(ctx, MESSAGES, Session.profile?.title ?: ctx.getString(R.string.app_name), body, sessionIntent(ctx)))
    }

    /** The session ended with an error while nobody was looking. */
    fun sessionEnded(ctx: Context, title: String, message: String) {
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        post(ctx, builder(ctx, ENDED, ctx.getString(R.string.notif_ended, title), message.ifBlank { null }, open))
    }

    private fun builder(ctx: Context, channel: String, title: String, text: String?, intent: PendingIntent) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_drawterm)
            .setContentTitle(title)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .apply {
                if (text != null) {
                    setContentText(text.lineSequence().last())
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
            }

    private fun post(ctx: Context, b: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ctx.getSystemService(NotificationManager::class.java)?.notify(nextId.getAndIncrement(), b.build())
    }

    private fun sessionIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, SessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

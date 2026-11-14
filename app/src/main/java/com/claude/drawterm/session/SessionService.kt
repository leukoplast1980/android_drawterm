package com.claude.drawterm.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.claude.drawterm.R

/** Keeps the session process and its connection alive in the background. */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Session.init(this)
        if (intent?.action == ACTION_DISCONNECT) {
            Session.disconnect()
            return START_NOT_STICKY
        }
        val title = Session.profile?.title ?: getString(R.string.app_name)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.SESSION_ID, Notifications.session(this, title), type)
        return START_NOT_STICKY
    }

    /** Swiping the app away from Recents ends the session. */
    override fun onTaskRemoved(rootIntent: Intent?) = Session.disconnect()

    companion object {
        const val ACTION_DISCONNECT = "com.claude.drawterm.DISCONNECT"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}

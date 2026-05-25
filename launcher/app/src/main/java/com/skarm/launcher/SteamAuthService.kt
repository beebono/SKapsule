package com.skarm.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Short-lived foreground service that keeps the :game process alive while
 * frenchpress is mid Steam login.
 *
 * Steam Mobile App approval — and reading a Steam Guard code from an
 * authenticator — forces the user to tab away to another app. Without an
 * elevated process priority Android reaps the backgrounded JVM, the auth poll
 * (JavaSteam's pollingWaitForResult) dies with it, and the user lands back at
 * the menu; because the refresh token is only persisted after a *successful*
 * logon, the next launch re-runs the whole 2FA dance — which is exactly the
 * "why does it ask every time" the field report describes.
 *
 * A foreground service is the lever that raises oom_adj enough to survive the
 * tab-out. It runs in :game (same process as the JVM it protects) and is
 * started/stopped strictly around the auth attempt by SteamSession.attempt()
 * via NativeBridge.steamKeepAlive — never during gameplay or at the menu, so
 * backgrounding at any other time still gets a normal, graceful teardown.
 */
class SteamAuthService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.steam_login_notif_title))
            .setContentText(getString(R.string.steam_login_notif_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        // The auth window is bounded by attempt()'s try/finally; if Android kills
        // us anyway there's nothing to resume, so don't let it recreate us.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.steam_login_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "steam_login"
        const val NOTIF_ID = 4242
    }
}

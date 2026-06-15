package com.kododake.aabrowser.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kododake.aabrowser.MainActivity
import com.kododake.aabrowser.R

/**
 * Foreground service that keeps AABrowser alive while driving.
 *
 * Connects to the AAOS Car service to monitor UX restrictions and broadcasts
 * driving state changes to the rest of the app. By running as a foreground
 * service, the process is kept alive even when MainActivity is backgrounded,
 * so browsing sessions and open tabs survive switching to other car apps
 * (like Maps or Music) and back.
 */
class DrivingForegroundService : Service() {

    private var car: Car? = null
    private var carUxManager: CarUxRestrictionsManager? = null
    private var isDriving = false

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        val driving = restrictions.isRequiresDistractionOptimization
        if (driving != isDriving) {
            isDriving = driving
            refreshNotification()
            broadcastDrivingState(driving)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(isDriving),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        connectToCarService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        disconnectFromCarService()
        super.onDestroy()
    }

    // ── Car service connection ────────────────────────────────────────────────

    private fun connectToCarService() {
        runCatching {
            car = Car.createCar(
                this,
                null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER
            ) { connectedCar, ready ->
                if (ready) {
                    runCatching {
                        carUxManager = connectedCar
                            .getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        carUxManager?.registerListener(uxListener)
                        // Seed current state immediately
                        carUxManager?.currentCarUxRestrictions
                            ?.isRequiresDistractionOptimization
                            ?.let { driving ->
                                isDriving = driving
                                refreshNotification()
                                broadcastDrivingState(driving)
                            }
                    }
                }
            }
        }
    }

    private fun disconnectFromCarService() {
        runCatching { carUxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        carUxManager = null
        car = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(isDriving))
    }

    private fun buildNotification(driving: Boolean): Notification {
        val text = if (driving) {
            getString(R.string.driving_service_text_driving)
        } else {
            getString(R.string.driving_service_text_parked)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.driving_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.driving_service_channel_description)
                setShowBadge(false)
            }
        )
    }

    // ── Broadcasting ──────────────────────────────────────────────────────────

    private fun broadcastDrivingState(driving: Boolean) {
        sendBroadcast(
            Intent(ACTION_DRIVING_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_DRIVING, driving)
                setPackage(packageName)
            }
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "aabrowser_driving_channel"

        const val ACTION_DRIVING_STATE_CHANGED =
            "com.kododake.aabrowser.DRIVING_STATE_CHANGED"
        const val EXTRA_IS_DRIVING = "extra_is_driving"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DrivingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DrivingForegroundService::class.java))
        }
    }
}

package com.example.watchspeedometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow

/** Текущая скорость (м/с), которую сервис отдаёт экрану. */
object SpeedState {
    val speed = MutableStateFlow(0f)
}

/**
 * Foreground-сервис: держит GPS и процесс живыми, даже когда экран погас
 * или включился скринсейвер. Останавливается только по команде пользователя.
 */
class SpeedService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.watchspeedometer.STOP"
        private const val CHANNEL_ID = "speedometer"
        private const val NOTIF_ID = 1
    }

    private lateinit var client: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val s = if (loc.hasSpeed() && loc.speed >= 0f) loc.speed else 0f
            // шум GPS на стоянке (< ~1.8 км/ч) считаем нулём
            SpeedState.speed.value = if (s < 0.5f) 0f else s
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "WatchSpeedometer::gps"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        startGps()
        return START_STICKY
    }

    private fun startGps() {
        // сначала снимаем старую подписку, чтобы не было дублей
        client.removeLocationUpdates(callback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Спидометр", NotificationManager.IMPORTANCE_LOW)
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Спидометр работает")
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        wakeLock?.let { if (it.isHeld) it.release() }
        SpeedState.speed.value = 0f
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

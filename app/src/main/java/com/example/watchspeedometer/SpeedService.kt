package com.example.watchspeedometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow

/** Скорость в м/с. null — нет сигнала GPS. */
object SpeedState {
    val speed = MutableStateFlow<Float?>(null)

    /** Подсказка под «--»: сколько спутников в решении, или «GPS выключен». */
    val status = MutableStateFlow("")

    /** true, когда пользователь нажал «Стоп» в уведомлении — экран должен закрыться. */
    val stopRequested = MutableStateFlow(false)
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

        /** Ниже этой скорости (м/с) показываем 0. 0.1 м/с ≈ 0.2 узла. */
        private const val MIN_SPEED = 0.1f

        /** Сглаживание: 1 = без сглаживания, меньше = плавнее. */
        private const val SMOOTHING = 0.5f

        /** Если фиксов нет дольше этого времени — показываем «--». */
        private const val SIGNAL_TIMEOUT_MS = 5000L

        /** true, пока сервис запущен (чтобы экран не запускал его повторно). */
        @Volatile
        var running = false
    }

    private lateinit var locationManager: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLoc: Location? = null
    private var lastFixAt = 0L
    private var smoothed = 0f

    /** Прямой GPS-приёмник (доплеровская скорость, без фильтров fused). */
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
        override fun onProviderEnabled(provider: String) = updateStatus()
        override fun onProviderDisabled(provider: String) = updateStatus()
    }

    private var satsUsed = 0

    /** Считаем спутники, участвующие в определении позиции. */
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
            updateStatus()
        }
    }

    /** Запасной вариант, если у часов нет GPS-провайдера. */
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    /** Если сигнал пропал — не держим старую скорость, показываем «--». */
    private val watchdog = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastFixAt > SIGNAL_TIMEOUT_MS) {
                SpeedState.speed.value = null
                smoothed = 0f
                lastLoc = null
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SpeedState.stopRequested.value = true // экран закроется сам
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

        lastFixAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 1000L)

        startGps()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        stopGps() // без дублей подписки
        updateStatus()
        try {
            locationManager.registerGnssStatusCallback(
                ContextCompat.getMainExecutor(this), gnssCallback
            )
        } catch (e: SecurityException) {
        }
        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()
                )
            } else {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setMinUpdateDistanceMeters(0f)
                    .setWaitForAccurateLocation(false)
                    .build()
                fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopGps() {
        try {
            locationManager.removeUpdates(gpsListener)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: SecurityException) {
        }
        fusedClient.removeLocationUpdates(fusedCallback)
    }

    private fun updateStatus() {
        val gpsOn = !locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        SpeedState.status.value = when {
            !gpsOn -> "GPS выключен"
            satsUsed > 0 -> "спутников: $satsUsed"
            else -> "поиск спутников…"
        }
    }

    private fun handleLocation(loc: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        val prev = lastLoc
        lastLoc = loc

        // 1) скорость от GPS (доплер); 2) если её нет — считаем по смещению; 3) иначе держим прежнюю
        var s: Float = when {
            loc.hasSpeed() -> loc.speed
            prev != null -> {
                val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
                if (dt in 0.3f..5f) prev.distanceTo(loc) / dt else smoothed
            }
            else -> smoothed
        }
        if (s.isNaN() || s < 0f) s = 0f
        if (s < MIN_SPEED) s = 0f

        val hadSignal = SpeedState.speed.value != null
        smoothed = if (hadSignal) smoothed + SMOOTHING * (s - smoothed) else s
        if (smoothed < MIN_SPEED) smoothed = 0f

        SpeedState.speed.value = smoothed
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
        handler.removeCallbacks(watchdog)
        stopGps()
        wakeLock?.let { if (it.isHeld) it.release() }
        SpeedState.speed.value = null
        SpeedState.status.value = ""
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package io.github.codemastervy.hatv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service: keeps the HA WebSocket open and shows the front-door pop-up over any app. */
class DoorbellService : Service(), HaConnection.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val gate = DetectionGate()
    private lateinit var settings: HaSettings
    private lateinit var overlay: DoorOverlay
    private var connection: HaConnection? = null
    private var cameraToken: String? = null
    private var popup: PopupConfig? = null
    private var debugReceiver: BroadcastReceiver? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { connection?.onNetworkAvailable() } }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { connection?.onScreenOn() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = HaSettings(this)
        createChannel()
        // Android 14 requires a declared type; "specialUse" (see manifest property) fits a doorbell watcher.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting…"), type)
        overlay = DoorOverlay(this) { openDashboard() }
        // Load the WebView engine now, so the first pop-up doesn't pay ~0.5-1 s of Chromium start-up.
        runCatching { WebView(this).destroy() }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) registerDebugReceiver()
        L.i("Front-door service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null means Android restarted us after a kill (START_STICKY).
        if (intent?.action == ACTION_RESTART || connection == null) restartConnection()
        return START_STICKY
    }

    override fun onDestroy() {
        L.i("Front-door service stopped")
        connection?.stop()
        overlay.dismiss()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        debugReceiver?.let { unregisterReceiver(it) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun restartConnection() {
        connection?.stop()
        connection = null
        cameraToken = null
        val token = settings.token
        val config = settings.popup
        if (token == null || !config.enabled) {
            L.i("Pop-up not configured (no token or no person sensor); stopping front-door service")
            stopSelf(); return
        }
        popup = config
        gate.quietMs = config.quietSeconds * 1000L
        connection = HaConnection(settings.baseUrl, token, config.personEntity, config.cameraEntity, this)
            .also { it.start() }
    }

    override fun onStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onPersonState(state: String) = handlePersonState(state, "Home Assistant")

    override fun onCameraToken(accessToken: String?) {
        cameraToken = accessToken
        if (accessToken != null) overlay.updateStream(streamUrl(accessToken))
    }

    /** The ONE path for both real and debug detections. */
    private fun handlePersonState(state: String, source: String) {
        L.i("Person sensor -> $state (from $source)")
        if (!gate.onPersonState(state, SystemClock.elapsedRealtime())) return
        val config = popup ?: return
        when {
            AppState.dashboardVisible -> L.i("Dashboard is in front; it has its own alert, no pop-up")
            !Settings.canDrawOverlays(this) -> L.w("Overlay permission missing; cannot show pop-up")
            !getSystemService(PowerManager::class.java).isInteractive -> L.i("Screen is off; no pop-up")
            else -> {
                L.i("Showing front-door pop-up")
                overlay.show(
                    settings.baseUrl, config.title, config.autoCloseSeconds,
                    cameraToken?.let(::streamUrl),
                    if (config.cameraEntity.isBlank()) "No camera set in HA TV setup" else "Connecting to camera…",
                )
            }
        }
    }

    private fun streamUrl(accessToken: String) =
        Urls.cameraStream(settings.baseUrl, popup?.cameraEntity ?: "", accessToken)

    private fun openDashboard() {
        // Allowed from the background because the app holds SYSTEM_ALERT_WINDOW.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    /**
     * Debug builds only:
     *   adb shell am broadcast -a io.github.codemastervy.hatv.DEBUG_DOORBELL        -> a detection blip ("on" then "off")
     *   adb shell am broadcast -a io.github.codemastervy.hatv.DEBUG_DOORBELL_RESET  -> forget the current visit
     * Both go through handlePersonState(), the same code path as a real detection.
     */
    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DEBUG_ON -> {
                        handlePersonState("on", "debug broadcast")
                        handlePersonState("off", "debug broadcast")
                    }
                    ACTION_DEBUG_RESET -> { gate.reset(); L.i("Debug: visit reset") }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_DEBUG_ON); addAction(ACTION_DEBUG_RESET) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Front door watcher", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_house)
            .setContentTitle("HA TV is watching the front door")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "front_door"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RESTART = "io.github.codemastervy.hatv.RESTART"
        const val ACTION_DEBUG_ON = "io.github.codemastervy.hatv.DEBUG_DOORBELL"
        const val ACTION_DEBUG_RESET = "io.github.codemastervy.hatv.DEBUG_DOORBELL_RESET"

        fun start(ctx: Context) = launch(ctx, null)
        fun restart(ctx: Context) = launch(ctx, ACTION_RESTART)
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DoorbellService::class.java)) }

        private fun launch(ctx: Context, action: String?) {
            val s = HaSettings(ctx)
            if (!s.hasToken || !s.popup.enabled) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DoorbellService::class.java).setAction(action))
            } catch (e: Exception) {
                L.w("Could not start front-door service", e)
            }
        }
    }
}

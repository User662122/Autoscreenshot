package com.example.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenStreamService : Service() {
    private var mediaProjection: MediaProjection? = null
    
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webRTCManager: WebRTCManager
    
    private val TAG = "ScreenStreamService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "ScreenStreamService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenStreamService starting")

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid result code or data")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, handler)

            setupScreenCapture()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen stream service", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun setupScreenCapture() {
    try {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Log.d(TAG, "Display metrics: ${width}x${height} density=$density")

        // ----------------------------------------------------
        // WHATSAPP STYLE WEBRTC SCREEN CAPTURE
        // ----------------------------------------------------
        webRTCManager = WebRTCManager(
            context = this,
            mediaProjectionPermissionIntent =
                intent!!.getParcelableExtra("data")!!
        )

        // ----------------------------------------------------
        // Notification update
        // ----------------------------------------------------
        val serverIp = getDeviceIpAddress()
        if (serverIp != null) {
            val url = "http://$serverIp:8080"
            Log.d(TAG, "WebRTC Server running at: $url")
            updateNotification("Open: $url")
        }

        Log.d(TAG, "WebRTC screen streaming started")

    } catch (e: Exception) {
        Log.e(TAG, "Screen capture setup failed", e)
        stopSelf()
    }
}    
    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
    }
    
    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Streaming")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Stream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { 
                description = "Streaming screen in real-time"
                setShowBadge(false) 
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Streaming")
            .setContentText("Screen is being streamed via WebRTC")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenStreamService destroying")

        virtualDisplay?.release()
        virtualDisplay = null
        
        screenEncoder.release()
        webRTCManager.release()
        
        mediaProjection?.stop()
        mediaProjection = null

        Log.d(TAG, "ScreenStreamService destroyed")
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_stream_channel"
    }
}

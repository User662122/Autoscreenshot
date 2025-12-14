package com.example.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private lateinit var screenEncoder: ScreenEncoder
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

            // Initialize screen encoder with H.264
            screenEncoder = ScreenEncoder(width, height, this)
            
            // Initialize WebRTC manager
            webRTCManager = WebRTCManager(this, screenEncoder)
            
            // Create virtual display with encoder's input surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenStream",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenEncoder.getInputSurface(),
                null,
                null
            )

            Log.d(TAG, "Virtual display created and streaming started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture setup failed", e)
            stopSelf()
        }
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
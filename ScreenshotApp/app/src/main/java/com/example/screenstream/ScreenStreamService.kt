package com.example.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenStreamService : Service() {

    private val TAG = "ScreenStreamService"

    private var mediaProjection: MediaProjection? = null
    private lateinit var webRTCManager: WebRTCManager
    private var projectionIntent: Intent? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        projectionIntent = intent?.getParcelableExtra("data")

        if (resultCode != Activity.RESULT_OK || projectionIntent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode!!, projectionIntent!!)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        startWebRTC()
        return START_STICKY
    }

    private fun startWebRTC() {
        webRTCManager = WebRTCManager(
            context = this,
            mediaProjectionPermissionIntent = projectionIntent!!
        )

        updateNotification("Open http://${getDeviceIp()}:8080")
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCManager.release()
        mediaProjection?.stop()
    }

    private fun getDeviceIp(): String {
        return java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress ?: "device-ip"
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "screen_stream")
            .setContentTitle("Screen Streaming")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                "screen_stream",
                "Screen Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
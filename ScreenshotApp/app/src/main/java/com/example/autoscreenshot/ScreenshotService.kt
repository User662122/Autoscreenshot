package com.example.autoscreenshot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenshotService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    
    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureScreenshot()
                handler.postDelayed(this, 5000) // 5 seconds
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "ScreenshotService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenshotService starting")
        
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
            })
            
            setupVirtualDisplay()
            isCapturing = true
            handler.post(screenshotRunnable)
            Log.d(TAG, "Screenshot capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screenshot service: ${e.message}")
            e.printStackTrace()
            stopSelf()
        }
        
        return START_STICKY
    }
    
    private fun setupVirtualDisplay() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }
            
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            
            Log.d(TAG, "Display metrics: $width x $height, density: $density")
            
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            
            Log.d(TAG, "Virtual display setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up virtual display: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun captureScreenshot() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image acquired successfully")
                val bitmap = imageToBitmap(image)
                image.close()
                
                if (bitmap != null) {
                    saveBitmap(bitmap)
                    bitmap.recycle()
                    Log.d(TAG, "Screenshot saved successfully")
                } else {
                    Log.e(TAG, "Failed to convert image to bitmap")
                }
            } else {
                Log.d(TAG, "No image available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private fun saveBitmap(bitmap: Bitmap) {
        try {
            // Use Pictures directory for better compatibility
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val folder = File(picturesDir, "AutoScreenshot")
            
            if (!folder.exists()) {
                folder.mkdirs()
            }
            
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "screenshot_${dateFormat.format(Date())}.png"
            val file = File(folder, fileName)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            
            Log.d(TAG, "Screenshot saved to: ${file.absolutePath}")
            
            // Notify media scanner
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(file)
            sendBroadcast(mediaScanIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Taking screenshots every 5 seconds"
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
            .setContentTitle("Screenshot Service")
            .setContentText("Taking screenshots every 5 seconds")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenshotService destroying")
        isCapturing = false
        handler.removeCallbacks(screenshotRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        Log.d(TAG, "ScreenshotService destroyed")
    }
    
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screenshot_service_channel"
    }
}
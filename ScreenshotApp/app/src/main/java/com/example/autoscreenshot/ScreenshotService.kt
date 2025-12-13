package com.example.autoscreenshot

import android.app.*
import android.util.Log
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ScreenshotService : Service() {
private var mediaProjection: MediaProjection? = null
private var virtualDisplay: VirtualDisplay? = null
private var imageReader: ImageReader? = null
private val handler = Handler(Looper.getMainLooper())
private var isCapturing = false
private lateinit var modelManager: TFLiteModelManager

private var captureJob: Job? = null  

override fun onBind(intent: Intent?): IBinder? = null  

override fun onCreate() {  
    super.onCreate()  
    createNotificationChannel()  
    startForeground(NOTIFICATION_ID, createNotification())  
    modelManager = TFLiteModelManager(this)  
    Log.w(TAG, "Service created successfully")  
}  

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {  

    Prefs.resetAllGameData(this)  
    ChessMoveAccessibilityService.restartPolling(this)  
    Log.w(TAG, "Prefs reset & accessibility service restarted")  

    val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1  
    val data = intent?.getParcelableExtra<Intent>("data")  

    if (resultCode != Activity.RESULT_OK || data == null) {  
        Log.e(TAG, "Invalid permissions: resultCode=$resultCode, data=$data")  
        showToast("✗ Invalid permissions")  
        stopSelf()  
        return START_NOT_STICKY  
    }  

    try {  
        val mediaProjectionManager =  
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager  
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)  
        Log.w(TAG, "MediaProjection initialized successfully")  

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {  
            override fun onStop() {  
                super.onStop()  
                Log.w(TAG, "MediaProjection stopped")  
                stopSelf()  
            }  
        }, handler)  

        setupVirtualDisplay()  
        isCapturing = true  

        captureJob = CoroutineManager.launchIO {  
            delay(15000) // 15 second delay before starting  
            while (isActive && isCapturing) {  
                try {  
                    val pendingMove = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")  

                    if (pendingMove.isNotEmpty()) {  
                        while (isActive && isCapturing) {  
                            val currentMove = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")  
                            if (currentMove.isEmpty()) break  
                            delay(500)  
                        }  
                        delay(2000)  
                    }  

                    captureScreenshot()  
                    delay(3000)  
                } catch (e: Exception) {  
                    Log.e(TAG, "Error in capture loop: ${e.message}", e)  
                    delay(5000)  
                }  
            }  
        }  

    } catch (e: Exception) {  
        Log.e(TAG, "MediaProjection setup error: ${e.message}", e)  
        showToast("✗ Error: ${e.message}")  
        stopSelf()  
    }  

    return START_STICKY  
}  

private fun setupVirtualDisplay() {  
try {  
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager  
    val metrics = DisplayMetrics()  

    @Suppress("DEPRECATION")  
    windowManager.defaultDisplay.getRealMetrics(metrics)  

    val width = metrics.widthPixels  
    val height = metrics.heightPixels  
    val density = metrics.densityDpi  

    imageReader = ImageReader.newInstance(  
        width,  
        height,  
        PixelFormat.RGBA_8888,  
        2  
    )  

    virtualDisplay = mediaProjection?.createVirtualDisplay(  
        "ScreenCapture",  
        width,  
        height,  
        density,  
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,  
        imageReader!!.surface,  
        null,  
        null  
    )  

    Log.w(TAG, "Virtual display setup successfully: ${width}x$height @ $density dpi")  

} catch (e: Exception) {  
    Log.e(TAG, "Display setup error: ${e.message}", e)  
    stopSelf()  
}

}
private suspend fun captureScreenshot() {
    val image = imageReader?.acquireLatestImage()
    if (image != null) {
        try {
            val bitmap = withContext(Dispatchers.Default) { imageToBitmap(image) }
            if (bitmap != null) {
                // Resize bitmap to 720x1620 if different
                val resizedBitmap = if (bitmap.width != 720 || bitmap.height != 1620) {
                    Bitmap.createScaledBitmap(bitmap, 720, 1620, true)
                } else {
                    bitmap
                }

                val cropped = withContext(Dispatchers.Default) { cropBitmap(resizedBitmap, 11, 436, 709, 1141) }
                Log.w(TAG, "Screenshot cropped successfully")

                val pieces = extract64Pieces(cropped)
                Log.w(TAG, "64 chess pieces extracted successfully")

                cropped.recycle()
                if (resizedBitmap != bitmap) bitmap.recycle() // Original bitmap free if resized

                modelManager.processChessBoard(pieces, this@ScreenshotService)
                Log.w(TAG, "Model processing triggered successfully")
            } else {
                Log.e(TAG, "Failed to convert Image to Bitmap")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in captureScreenshot: ${e.message}", e)
        } finally {
            image.close()
        }
    } else {
        Log.e(TAG, "No image acquired from ImageReader")
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

        val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)  
        Log.w(TAG, "Image converted to Bitmap successfully")  
        finalBitmap  
    } catch (e: Exception) {  
        Log.e(TAG, "Image to Bitmap conversion failed: ${e.message}", e)  
        null  
    }  
}  

private fun cropBitmap(src: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {  
    return try {  
        val cropped = Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)  
        Log.w(TAG, "Bitmap cropped successfully")  
        cropped  
    } catch (e: Exception) {  
        Log.e(TAG, "Bitmap cropping failed: ${e.message}", e)  
        src  
    }  
}  

private fun extract64Pieces(croppedBoard: Bitmap): List<Bitmap> {  
    val cellW = croppedBoard.width / 8  
    val cellH = croppedBoard.height / 8  
    val pieces = mutableListOf<Bitmap>()  

    try {  
        for (r in 0 until 8) {  
            for (c in 0 until 8) {  
                val x = c * cellW  
                val y = r * cellH  
                val piece = Bitmap.createBitmap(croppedBoard, x, y, cellW, cellH)  
                val resized = Bitmap.createScaledBitmap(piece, 96, 96, true)  
                pieces.add(resized)  
                piece.recycle()  
            }  
        }  
        Log.w(TAG, "All 64 pieces extracted & resized successfully")  
    } catch (e: Exception) {  
        Log.e(TAG, "Error extracting chess pieces: ${e.message}", e)  
    }  

    return pieces  
}  

private fun showToast(message: String) {  
    CoroutineManager.launchMain {  
        Toast.makeText(this@ScreenshotService, message, Toast.LENGTH_SHORT).show()  
    }  
}  

private fun createNotificationChannel() {  
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {  
        val channel = NotificationChannel(  
            CHANNEL_ID,  
            "Screenshot Service",  
            NotificationManager.IMPORTANCE_LOW  
        ).apply {   
            description = "Taking screenshots every 3 seconds"  
            setShowBadge(false)   
        }  
        val notificationManager = getSystemService(NotificationManager::class.java)  
        notificationManager.createNotificationChannel(channel)  
        Log.w(TAG, "Notification channel created successfully")  
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

    Log.w(TAG, "Foreground notification created successfully")  
    return NotificationCompat.Builder(this, CHANNEL_ID)  
        .setContentTitle("Screenshot Service")  
        .setContentText("Taking screenshots every 3 seconds")  
        .setSmallIcon(android.R.drawable.ic_menu_camera)  
        .setContentIntent(pendingIntent)  
        .setOngoing(true)  
        .setOnlyAlertOnce(true)  
        .build()  
}  

override fun onDestroy() {  
    super.onDestroy()  

    isCapturing = false  
    captureJob?.cancel()  

    virtualDisplay?.release()  
    imageReader?.close()  
    mediaProjection?.stop()  
    modelManager.close()  
    CoroutineManager.cancelAll()  

    Log.w(TAG, "Service destroyed and resources released successfully")  
}  

companion object {  
    private const val TAG = "ScreenshotService"  
    private const val NOTIFICATION_ID = 1  
    private const val CHANNEL_ID = "screenshot_service_channel"  
}

}
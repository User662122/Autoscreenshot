package com.example.autoscreenshot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Canvas       // <-- added
import android.graphics.Paint       // <-- added
import android.graphics.Color       // <-- added
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
            }, handler)

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
                null
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

                    // ⭐ ADDITION: draw grid + box BEFORE saving
                    val finalBitmap = drawBoxGrid(bitmap, 11, 504, 709, 1201)

                    saveBitmap(finalBitmap)
                    finalBitmap.recycle()

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
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Log.d(TAG, "Pictures directory: ${picturesDir?.absolutePath}")

            val folder = File(picturesDir, "AutoScreenshot")

            if (!folder.exists()) {
                val created = folder.mkdirs()
                Log.d(TAG, "Folder created: $created at ${folder.absolutePath}")
            }

            if (!folder.canWrite()) {
                Log.e(TAG, "Cannot write to folder: ${folder.absolutePath}")
                showNotification("Screenshot Error", "Cannot write to storage. Check permissions.")
                return
            }

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "screenshot_${dateFormat.format(Date())}.jpg"
            val file = File(folder, fileName)

            Log.d(TAG, "Attempting to save to: ${file.absolutePath}")

            FileOutputStream(file).use { out ->
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                out.flush()
                Log.d(TAG, "Bitmap compressed: $compressed")
            }

            if (file.exists()) {
                Log.d(TAG, "Screenshot saved successfully: ${file.absolutePath} (Size: ${file.length()} bytes)")
                showNotification("Screenshot Saved", "Saved to ${file.name}")

                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                val contentUri = Uri.fromFile(file)
                mediaScanIntent.data = contentUri
                sendBroadcast(mediaScanIntent)
            } else {
                Log.e(TAG, "File was not created: ${file.absolutePath}")
                showNotification("Screenshot Error", "File was not created")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            e.printStackTrace()
            showNotification("Screenshot Error", "Error: ${e.message}")
        }
    }

    private fun showNotification(title: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}")
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

    // ⭐ FULL ANDROID GRID + BOX DRAW FUNCTION (same as your Python logic)
    private fun drawBoxGrid(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val thick = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val thin = Paint().apply {
            color = Color.RED
            strokeWidth = 1.6f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        canvas.drawRect(
            x1.toFloat(), y1.toFloat(),
            x2.toFloat(), y2.toFloat(),
            thick
        )

        val w = (x2 - x1).toFloat()
        val h = (y2 - y1).toFloat()

        val cw = w / 8f
        val ch = h / 8f

        for (i in 1 until 8) {
            val x = x1 + i * cw
            canvas.drawLine(x, y1.toFloat(), x, y2.toFloat(), thin)
        }

        for (i in 1 until 8) {
            val y = y1 + i * ch
            canvas.drawLine(x1.toFloat(), y, x2.toFloat(), y, thin)
        }

        return mutable
    }
}
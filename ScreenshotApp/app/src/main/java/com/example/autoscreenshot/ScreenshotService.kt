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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Prefs.resetAllGameData(this)

        // Old service_active removed
        // New polling start
        ChessMoveAccessibilityService.instance?.startPollingForMoves()

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            showToast("✗ Invalid permissions")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopSelf()
                }
            }, handler)

            setupVirtualDisplay()
            isCapturing = true

            captureJob = CoroutineManager.launchIO {
                delay(15000)
                while (isActive && isCapturing) {
                    try {
                        val pendingMove = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")

                        if (pendingMove.isNotEmpty()) {
                            while (isActive && isCapturing) {
                                val currentMove = Prefs.getString(
                                    this@ScreenshotService,
                                    "pending_ai_move",
                                    ""
                                )
                                if (currentMove.isEmpty()) break
                                delay(500)
                            }
                            delay(2000)
                        }

                        captureScreenshot()
                        delay(3000)
                    } catch (e: Exception) {
                        delay(5000)
                    }
                }
            }

        } catch (e: Exception) {
            showToast("✗ Error: ${e.message}")
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

        } catch (e: Exception) {
            showToast("✗ Display setup error")
        }
    }

    private suspend fun captureScreenshot() {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            try {
                val bitmap = withContext(Dispatchers.Default) { imageToBitmap(image) }
                if (bitmap != null) {
                    val cropped = withContext(Dispatchers.Default) {
                        cropBitmap(bitmap, 11, 505, 709, 1201)
                    }

                    val pieces = extract64Pieces(cropped)

                    cropped.recycle()
                    bitmap.recycle()

                    modelManager.processChessBoard(pieces, this@ScreenshotService)
                }
            } finally {
                image.close()
            }
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
            null
        }
    }

    private fun cropBitmap(src: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
    }

    private fun extract64Pieces(croppedBoard: Bitmap): List<Bitmap> {
        val cellW = croppedBoard.width / 8
        val cellH = croppedBoard.height / 8
        val pieces = mutableListOf<Bitmap>()

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
    }

    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screenshot_service_channel"
    }
}
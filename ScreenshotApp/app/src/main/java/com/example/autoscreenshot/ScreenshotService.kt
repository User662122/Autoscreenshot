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
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class ScreenshotService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    private lateinit var modelManager: TFLiteModelManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var storedOrientation: Boolean? = null
    private var hasStoredOrientation = false
    private var hasStartColorSent = false

    private var captureJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        modelManager = TFLiteModelManager(this)
        Log.d(TAG, "ScreenshotService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenshotService starting")

        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false

        Prefs.resetAllGameData(this)
        Log.d(TAG, "All game data reset for fresh session")

        Prefs.setString(this, "screenshot_service_active", "true")

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid result code or data")
            showToast("❌ Invalid permissions")
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
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, handler)

            setupVirtualDisplay()
            isCapturing = true

            captureJob = serviceScope.launch {
                delay(15000)
                
                while (isActive && isCapturing) {
                    try {
                        captureScreenshot()
                        delay(3000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in capture loop: ${e.message}")
                        e.printStackTrace()
                        delay(5000)
                    }
                }
            }

            Log.d(TAG, "Screenshot capture will begin in 15 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screenshot service: ${e.message}")
            showToast("❌ Error: ${e.message}")
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
            showToast("❌ Display setup error")
            e.printStackTrace()
        }
    }

    private suspend fun captureScreenshot() = withContext(Dispatchers.IO) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image acquired successfully")
                
                try {
                    val bitmap = imageToBitmap(image)
                    
                    if (bitmap != null) {
                        val cropped = cropBitmap(bitmap, 11, 505, 709, 1201)
                        save64Pieces(cropped, bitmap)
                        Log.d(TAG, "64 screenshot pieces processed successfully")
                    } else {
                        Log.e(TAG, "Failed to convert image to bitmap")
                    }
                } finally {
                    image.close()
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

    private fun cropBitmap(src: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
    }

    private suspend fun save64Pieces(croppedBoard: Bitmap, fullBitmap: Bitmap) = withContext(Dispatchers.IO) {
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

            modelManager.classifyChessBoardAsync(pieces, this@ScreenshotService, storedOrientation) { uciMapping, orientation ->
                if (!hasStoredOrientation) {
                    storedOrientation = orientation
                    hasStoredOrientation = true
                    Log.d(TAG, "Board orientation stored: $orientation")
                }

                sendDataToBackend()
            }
        } finally {
            try {
                pieces.forEach { it.recycle() }
                croppedBoard.recycle()
                fullBitmap.recycle()
                Log.d(TAG, "All bitmaps recycled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling bitmaps: ${e.message}")
            }
        }
    }

    private fun sendDataToBackend() {
        serviceScope.launch {
            try {
                val bottomColor = Prefs.getString(this@ScreenshotService, "bottom_color", "")
                
                if (!hasStartColorSent && bottomColor.isNotEmpty()) {
                    val colorLower = bottomColor.lowercase()
                    val (startSuccess, startResponse) = NetworkManager.sendStartColor(this@ScreenshotService, colorLower)
                    
                    if (startSuccess) {
                        hasStartColorSent = true
                        Log.d(TAG, "Start color sent: $colorLower. Response: $startResponse")
                        
                        if (startResponse.isNotEmpty() && startResponse != "Invalid" && startResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", startResponse)
                            Log.d(TAG, "Stored pending AI move: $startResponse")
                            
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                        }
                    } else {
                        Log.e(TAG, "Failed to send start color. Response: $startResponse")
                    }
                }

                val whiteUCI = Prefs.getString(this@ScreenshotService, "uci_white", "")
                val blackUCI = Prefs.getString(this@ScreenshotService, "uci_black", "")

                if (whiteUCI.isNotEmpty() && blackUCI.isNotEmpty()) {
                    val whitePositions = whiteUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val blackPositions = blackUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    val (positionSuccess, positionResponse) = NetworkManager.sendPiecePositions(
                        this@ScreenshotService,
                        whitePositions,
                        blackPositions
                    )

                    if (positionSuccess) {
                        Log.d(TAG, "Piece positions sent successfully. Response: $positionResponse")
                        
                        if (positionResponse.isNotEmpty() && positionResponse != "Invalid" && positionResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", positionResponse)
                            Log.d(TAG, "Stored pending AI move: $positionResponse")
                            
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                        }
                    } else {
                        Log.e(TAG, "Failed to send piece positions. Response: $positionResponse")
                    }
                } else {
                    Log.w(TAG, "No piece positions available to send")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in sendDataToBackend: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        captureJob?.cancel()
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        modelManager.close()

        serviceScope.cancel()

        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false

        Prefs.setString(this, "screenshot_service_active", "false")

        Log.d(TAG, "ScreenshotService destroyed")
    }

    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screenshot_service_channel"
    }
}
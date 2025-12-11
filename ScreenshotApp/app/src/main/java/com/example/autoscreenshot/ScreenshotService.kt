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

class ScreenshotService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    private lateinit var modelManager: TFLiteModelManager

    // ✅ FIX: Removed old coroutine scope and using CoroutineManager
    private var captureJob: Job? = null

    // Store orientation and track if start color was sent
    private var storedOrientation: Boolean? = null
    private var hasStoredOrientation = false
    private var hasStartColorSent = false

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

        // Reset states when service starts fresh
        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false

        // Reset ALL game data in SharedPreferences (keeps ngrok_url in AutoScreenshotPrefs)
        Prefs.resetAllGameData(this)
        Log.d(TAG, "All game data reset for fresh session")

        // Mark service as active
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

            // ✅ FIX: Start periodic capture using CoroutineManager
            captureJob = CoroutineManager.launchIO {
                delay(15000) // Initial 15-second delay
                
                while (isActive && isCapturing) {
                    try {
                        captureScreenshot()
                        delay(3000) // Wait 3 seconds between captures
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in capture loop: ${e.message}")
                        e.printStackTrace()
                        delay(5000) // Longer delay on error
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

    // ✅ FIX: Using CoroutineManager for background processing
    private suspend fun captureScreenshot() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image acquired successfully")
                
                // ✅ FIX: Ensure image is always closed
                try {
                    val bitmap = CoroutineManager.launchDefault {
                        imageToBitmap(image)
                    }.await()
                    
                    if (bitmap != null) {
                        // Crop the chess board region
                        val cropped = CoroutineManager.launchDefault {
                            cropBitmap(bitmap, 11, 505, 709, 1201)
                        }.await()
                        
                        // Process 64 pieces
                        save64Pieces(cropped, bitmap)
                        
                        Log.d(TAG, "64 screenshot pieces processed successfully")
                    } else {
                        Log.e(TAG, "Failed to convert image to bitmap")
                    }
                } finally {
                    // ✅ FIX: Always close the image to release system buffer
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

    // ✅ FIX: Using CoroutineManager for piece processing
    private fun save64Pieces(croppedBoard: Bitmap, fullBitmap: Bitmap) {
        CoroutineManager.launchIO {
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
                        piece.recycle() // Recycle intermediate bitmap
                    }
                }

                // ✅ FIX: Using CoroutineManager for ML classification
                CoroutineManager.launchDefault {
                    modelManager.classifyChessBoardAsync(pieces, this@ScreenshotService, storedOrientation) { uciMapping, orientation ->
                        // Store orientation for future use
                        if (!hasStoredOrientation) {
                            storedOrientation = orientation
                            hasStoredOrientation = true
                            Log.d(TAG, "Board orientation stored: $orientation")
                        }

                        // Send data to backend
                        sendDataToBackend()

                        // Show notification on main thread
                        CoroutineManager.launchMain {
                            showNotification("Chess Board Detected", uciMapping)
                        }
                    }
                }
            } finally {
                // ✅ FIX: Ensure all bitmaps are recycled
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
    }

    private fun sendDataToBackend() {
        CoroutineManager.launchIO {
            try {
                // Get bottom color from SharedPreferences
                val bottomColor = Prefs.getString(this@ScreenshotService, "bottom_color", "")
                
                // Send start color only once
                if (!hasStartColorSent && bottomColor.isNotEmpty()) {
                    val colorLower = bottomColor.lowercase()
                    val (startSuccess, startResponse) = NetworkManager.sendStartColor(this@ScreenshotService, colorLower)
                    
                    if (startSuccess) {
                        hasStartColorSent = true
                        Log.d(TAG, "Start color sent: $colorLower. Response: $startResponse")
                        
                        // Store AI move in SharedPreferences for AccessibilityService
                        if (startResponse.isNotEmpty() && startResponse != "Invalid" && startResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", startResponse)
                            Log.d(TAG, "Stored pending AI move: $startResponse")
                            
                            // Verify it was saved
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                        }
                    } else {
                        Log.e(TAG, "Failed to send start color. Response: $startResponse")
                    }
                }

                // Get piece positions from SharedPreferences
                val whiteUCI = Prefs.getString(this@ScreenshotService, "uci_white", "")
                val blackUCI = Prefs.getString(this@ScreenshotService, "uci_black", "")

                if (whiteUCI.isNotEmpty() && blackUCI.isNotEmpty()) {
                    // Convert comma-separated strings to lists
                    val whitePositions = whiteUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val blackPositions = blackUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    // Send piece positions
                    val (positionSuccess, positionResponse) = NetworkManager.sendPiecePositions(
                        this@ScreenshotService,
                        whitePositions,
                        blackPositions
                    )

                    if (positionSuccess) {
                        Log.d(TAG, "Piece positions sent successfully. Response: $positionResponse")
                        
                        // Store AI move in SharedPreferences for AccessibilityService
                        if (positionResponse.isNotEmpty() && positionResponse != "Invalid" && positionResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", positionResponse)
                            Log.d(TAG, "Stored pending AI move: $positionResponse")
                            
                            // Verify it was saved
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                        }
                        
                        CoroutineManager.launchMain {
                            showNotification("Data Sent", "Board state sent to backend")
                        }
                    } else {
                        Log.e(TAG, "Failed to send piece positions. Response: $positionResponse")
                        CoroutineManager.launchMain {
                            showNotification("Error", "Failed to send board state")
                        }
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
        CoroutineManager.launchMain {
            Toast.makeText(this@ScreenshotService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNotification(title: String, message: String) {
        CoroutineManager.launchMain {
            try {
                val notification = NotificationCompat.Builder(this@ScreenshotService, CHANNEL_ID)
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
        
        // ✅ FIX: Cancel capture job
        captureJob?.cancel()
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        modelManager.close()

        // Cancel all coroutines
        CoroutineManager.cancelAll()

        // Clear stored states
        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false

        // Mark service as inactive
        Prefs.setString(this, "screenshot_service_active", "false")

        Log.d(TAG, "ScreenshotService destroyed")
    }

    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screenshot_service_channel"
    }
}
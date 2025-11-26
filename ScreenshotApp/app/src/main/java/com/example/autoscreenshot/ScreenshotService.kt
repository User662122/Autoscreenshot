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
import kotlinx.coroutines.CoroutineScope
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

    // Coroutine scope for network calls
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Store orientation and track if start color was sent
    private var storedOrientation: Boolean? = null
    private var hasStoredOrientation = false
    private var hasStartColorSent = false

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureScreenshot()
                handler.postDelayed(this, 3000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        modelManager = TFLiteModelManager(this)
        showToast("📱 Screenshot Service Started")
        Log.d(TAG, "ScreenshotService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenshotService starting")
        showToast("🚀 Initializing Screenshot Service...")

        // Reset states when service starts fresh
        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false

        // Clear any old pending moves
        Prefs.setString(this, "pending_ai_move", "")
        showToast("🧹 Cleared old AI moves")

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

            // Add 15-second delay before starting screenshot capture
            handler.postDelayed({
                handler.post(screenshotRunnable)
                showToast("✅ Screenshot capture started!")
                Log.d(TAG, "Screenshot capture started after 15-second delay")
            }, 15000)

            showToast("⏰ Starting in 15 seconds...")
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

    private fun captureScreenshot() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                Log.d(TAG, "Image acquired successfully")
                val bitmap = imageToBitmap(image)
                image.close()

                if (bitmap != null) {
                    // Crop the chess board region
                    val cropped = cropBitmap(bitmap, 11, 505, 709, 1201)

                    // Process 64 pieces
                    save64Pieces(cropped)

                    Log.d(TAG, "64 screenshot pieces processed successfully")
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

    private fun cropBitmap(src: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
    }

    private fun save64Pieces(bmp: Bitmap) {
        val cellW = bmp.width / 8
        val cellH = bmp.height / 8
        val pieces = mutableListOf<Bitmap>()

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val x = c * cellW
                val y = r * cellH

                val piece = Bitmap.createBitmap(bmp, x, y, cellW, cellH)
                val resized = Bitmap.createScaledBitmap(piece, 96, 96, true)

                pieces.add(resized)
                piece.recycle()
            }
        }

        // Classify chess board and send to backend
        modelManager.classifyChessBoard(pieces, this, storedOrientation) { uciMapping, orientation ->
            // Store orientation for future use
            if (!hasStoredOrientation) {
                storedOrientation = orientation
                hasStoredOrientation = true
                showToast("🎯 Board detected: ${if (orientation) "Normal" else "Reversed"}")
                Log.d(TAG, "Board orientation stored: $orientation")
            }

            // Send data to backend
            sendDataToBackend()

            // Show notification
            showNotification("Chess Board Detected", uciMapping)
        }

        // Clean up
        pieces.forEach { it.recycle() }
    }

    private fun sendDataToBackend() {
        serviceScope.launch {
            try {
                // Get bottom color from SharedPreferences
                val bottomColor = Prefs.getString(this@ScreenshotService, "bottom_color", "")
                
                // Send start color only once
                if (!hasStartColorSent && bottomColor.isNotEmpty()) {
                    showToast("📤 Sending START: $bottomColor")
                    
                    val colorLower = bottomColor.lowercase()
                    val (startSuccess, startResponse) = NetworkManager.sendStartColor(this@ScreenshotService, colorLower)
                    
                    if (startSuccess) {
                        hasStartColorSent = true
                        showToast("✅ START OK | AI: $startResponse")
                        Log.d(TAG, "Start color sent: $colorLower. Response: $startResponse")
                        
                        // Store AI move in SharedPreferences for AccessibilityService
                        if (startResponse.isNotEmpty() && startResponse != "Invalid" && startResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", startResponse)
                            showToast("💾 Saved AI move: $startResponse")
                            Log.d(TAG, "Stored pending AI move: $startResponse")
                            
                            // Verify it was saved
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                            if (verification == startResponse) {
                                showToast("✅ Verified: Move saved!")
                            } else {
                                showToast("⚠️ Save failed! Got: $verification")
                            }
                        } else {
                            showToast("⚠️ AI response empty/invalid")
                        }
                    } else {
                        showToast("❌ START failed!")
                        Log.e(TAG, "Failed to send start color. Response: $startResponse")
                    }
                }

                // Get piece positions from SharedPreferences
                val whiteUCI = Prefs.getString(this@ScreenshotService, "uci_white", "")
                val blackUCI = Prefs.getString(this@ScreenshotService, "uci_black", "")

                if (whiteUCI.isNotEmpty() && blackUCI.isNotEmpty()) {
                    showToast("📤 Sending positions...")
                    
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
                        showToast("✅ Positions OK | AI: $positionResponse")
                        Log.d(TAG, "Piece positions sent successfully. Response: $positionResponse")
                        
                        // Store AI move in SharedPreferences for AccessibilityService
                        if (positionResponse.isNotEmpty() && positionResponse != "Invalid" && positionResponse != "Game Over") {
                            Prefs.setString(this@ScreenshotService, "pending_ai_move", positionResponse)
                            showToast("💾 Saved AI move: $positionResponse")
                            Log.d(TAG, "Stored pending AI move: $positionResponse")
                            
                            // Verify it was saved
                            val verification = Prefs.getString(this@ScreenshotService, "pending_ai_move", "")
                            Log.d(TAG, "Verification - Read back from Prefs: $verification")
                            if (verification == positionResponse) {
                                showToast("✅ Verified: Move saved!")
                            } else {
                                showToast("⚠️ Save failed! Got: $verification")
                            }
                        } else {
                            showToast("⚠️ AI response empty/invalid")
                        }
                        
                        showNotification("Data Sent", "Board state sent to backend")
                    } else {
                        showToast("❌ Position send failed!")
                        Log.e(TAG, "Failed to send piece positions. Response: $positionResponse")
                        showNotification("Error", "Failed to send board state")
                    }
                } else {
                    showToast("⚠️ No positions available")
                    Log.w(TAG, "No piece positions available to send")
                }

            } catch (e: Exception) {
                showToast("❌ Exception: ${e.message}")
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
        showToast("🛑 Screenshot Service Stopped")
        Log.d(TAG, "ScreenshotService destroying")
        
        isCapturing = false
        handler.removeCallbacks(screenshotRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        modelManager.close()

        // Cancel coroutine scope
        serviceScope.cancel()

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
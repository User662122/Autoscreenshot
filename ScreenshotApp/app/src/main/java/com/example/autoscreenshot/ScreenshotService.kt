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
import okhttp3.*
import java.io.IOException

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    private lateinit var modelManager: TFLiteModelManager

    private var storedUCIMapping: String? = null
    private var hasStoredMapping = false

    private var lastSentUCI: String? = null   // NEW

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureScreenshot()
                handler.postDelayed(this, 5000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        modelManager = TFLiteModelManager(this)
        Toast.makeText(this, "Screenshot service started", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        storedUCIMapping = null
        hasStoredMapping = false
        lastSentUCI = null

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, handler)

            setupVirtualDisplay()
            isCapturing = true
            handler.post(screenshotRunnable)

        } catch (e: Exception) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
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
    }

    private fun captureScreenshot() {
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap != null) {
                val cropped = Bitmap.createBitmap(bitmap, 11, 505, 709, 1201)
                processBoard(cropped)
            }

        } catch (_: Exception) {}
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val temp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            temp.copyPixelsFromBuffer(buffer)

            Bitmap.createBitmap(temp, 0, 0, image.width, image.height)

        } catch (e: Exception) {
            null
        }
    }

    private fun processBoard(bmp: Bitmap) {
        val cellW = bmp.width / 8
        val cellH = bmp.height / 8
        val pieces = ArrayList<Bitmap>()

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val piece = Bitmap.createBitmap(bmp, c * cellW, r * cellH, cellW, cellH)
                val resized = Bitmap.createScaledBitmap(piece, 96, 96, true)
                pieces.add(resized)
                piece.recycle()
            }
        }

        if (!hasStoredMapping || storedUCIMapping == null) {

            modelManager.classifyChessBoard(pieces, this) { uciMapping ->
                storedUCIMapping = uciMapping
                hasStoredMapping = true
                UCIManager.currentUCI = uciMapping

                detectAndSend(uciMapping)     // NEW CALL
            }

        } else {
            UCIManager.currentUCI = storedUCIMapping!!
            detectAndSend(storedUCIMapping!!)   // NEW CALL
        }

        pieces.forEach { it.recycle() }
    }

    private fun detectAndSend(uci: String) {
        if (uci == lastSentUCI) return

        lastSentUCI = uci

        val pref = getSharedPreferences("global", MODE_PRIVATE)
        val url = pref.getString("ngrok_url", "") ?: ""
        if (url.isEmpty()) return

        val fullUrl = "$url/move"

        val body = RequestBody.create(MediaType.parse("text/plain"), uci)
        val req = Request.Builder().url(fullUrl).post(body).build()

        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        handler.removeCallbacks(screenshotRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        modelManager.close()

        storedUCIMapping = null
        hasStoredMapping = false
        lastSentUCI = null

        Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
    }
}
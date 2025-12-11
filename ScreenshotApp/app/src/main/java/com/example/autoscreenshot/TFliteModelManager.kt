package com.example.autoscreenshot

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class TFLiteModelManager(private val context: Context) {
    private var interpreters: Array<Interpreter?> = arrayOfNulls(8)
    private val MODEL_PATH = "wbe_mnv2_96.tflite"
    
    // HTTP Client for server communication
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Class names in the same order as your training
    private val classNames = arrayOf("White", "Black", "Empty")
    
    // Input image dimensions
    private val INPUT_SIZE = 96
    private val CHANNEL_COUNT = 3
    
    // Chess board mapping arrays
    private val chessSquaresNormal = arrayOf(
        "a8", "b8", "c8", "d8", "e8", "f8", "g8", "h8",
        "a7", "b7", "c7", "d7", "e7", "f7", "g7", "h7", 
        "a6", "b6", "c6", "d6", "e6", "f6", "g6", "h6",
        "a5", "b5", "c5", "d5", "e5", "f5", "g5", "h5",
        "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4",
        "a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3",
        "a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2",
        "a1", "b1", "c1", "d1", "e1", "f1", "g1", "h1"
    )
    
    private val chessSquaresReversed = arrayOf(
        "h1", "g1", "f1", "e1", "d1", "c1", "b1", "a1",
        "h2", "g2", "f2", "e2", "d2", "c2", "b2", "a2",
        "h3", "g3", "f3", "e3", "d3", "c3", "b3", "a3",
        "h4", "g4", "f4", "e4", "d4", "c4", "b4", "a4",
        "h5", "g5", "f5", "e5", "d5", "c5", "b5", "a5",
        "h6", "g6", "f6", "e6", "d6", "c6", "b6", "a6",
        "h7", "g7", "f7", "e7", "d7", "c7", "b7", "a7",
        "h8", "g8", "f8", "e8", "d8", "c8", "b8", "a8"
    )

    private var storedOrientation: Boolean? = null
    private var hasStoredOrientation = false
    private var hasStartColorSent = false

    init {
        initializeModel(context)
    }

    private fun initializeModel(context: Context) {
        try {
            val model = loadModelFile(context)
            // Create 8 interpreters with the same model
            for (i in 0 until 8) {
                interpreters[i] = Interpreter(model)
            }
            Log.d(TAG, "Initialized 8 interpreter instances")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model: ${e.message}")
            e.printStackTrace()
        }
    }

    @Throws(Exception::class)
    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Main entry point: receives 64 pieces from ScreenshotService
     * and handles all processing from here
     */
    suspend fun processChessBoard(pieces: List<Bitmap>, context: Context) {
        if (interpreters[0] == null) {
            CoroutineManager.launchMain {
                Toast.makeText(context, "Model not loaded", Toast.LENGTH_SHORT).show()
            }
            recycleBitmaps(pieces)
            return
        }

        if (pieces.size != 64) {
            CoroutineManager.launchMain {
                Toast.makeText(context, "Need exactly 64 pieces for chess board", Toast.LENGTH_SHORT).show()
            }
            recycleBitmaps(pieces)
            return
        }

        try {
            // Step 1: Classify all 64 pieces
            val classifications = classifyAllPieces(pieces)
            
            // Step 2: Detect or use stored orientation
            val orientation = determineOrientation(classifications)
            
            // Step 3: Create UCI mapping and save to SharedPreferences
            val uciMapping = createUCIResult(classifications, orientation)
            
            // Step 4: Send data to backend
            sendDataToBackend(context)
            
            // Step 5: Show notification
            showNotification(context, "Chess Board Detected", uciMapping)
            
            Log.d(TAG, "Chess board processing completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chess board: ${e.message}")
            e.printStackTrace()
            CoroutineManager.launchMain {
                Toast.makeText(context, "Processing error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            // Always recycle bitmaps to free memory
            recycleBitmaps(pieces)
        }
    }

    /**
     * Classify all 64 pieces in parallel using 8 interpreters
     */
    private suspend fun classifyAllPieces(pieces: List<Bitmap>): List<String> {
        return withContext(Dispatchers.Default) {
            val classifications = Array(64) { "" }
            
            // Process 8 pieces in parallel using coroutines
            val deferredResults = (0 until 64 step 8).map { i ->
                async {
                    val interpreterIndex = (i / 8) % 8
                    val interpreter = interpreters[interpreterIndex]!!

                    for (j in i until minOf(i + 8, 64)) {
                        val classification = classifyBitmapWithInterpreter(pieces[j], interpreter)
                        classifications[j] = classification
                        Log.d(TAG, "Processed piece $j with interpreter $interpreterIndex: $classification")
                    }
                }
            }

            // Wait for all coroutines to complete
            deferredResults.awaitAll()

            Log.d(TAG, "All 64 pieces classified")
            classifications.toList()
        }
    }

    /**
     * Classify a single bitmap with a specific interpreter
     */
    private fun classifyBitmapWithInterpreter(bitmap: Bitmap, interpreter: Interpreter): String {
        val input = preprocessBitmap(bitmap)
        val output = Array(1) { FloatArray(classNames.size) }
        
        interpreter.run(input, output)
        
        val probabilities = output[0]
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        
        return classNames[maxIndex]
    }

    /**
     * Determine board orientation (normal or reversed)
     */
    private fun determineOrientation(classifications: List<String>): Boolean {
        val bottomColorPref = Prefs.getString(context, "bottom_color", "")
        val isFirstDetection = bottomColorPref.isEmpty()

        if (isFirstDetection && storedOrientation == null) {
            // First detection: detect which color is at bottom
            val bottomColor = detectBottomColor(classifications)
            Prefs.setString(context, "bottom_color", bottomColor)
            Prefs.setString(context, "board_orientation_detected", "true")
            storedOrientation = (bottomColor == "White")
            hasStoredOrientation = true

            Log.d(TAG, "First detection: $bottomColor pieces at bottom, orientation: ${if (storedOrientation == true) "normal" else "reversed"}")
        } else if (storedOrientation == null) {
            // Use existing detection logic
            storedOrientation = detectOrientation(classifications)
            hasStoredOrientation = true
        }

        return storedOrientation ?: true
    }

    /**
     * Detect which color is at the bottom of the board
     */
    private fun detectBottomColor(classifications: List<String>): String {
        // Check bottom two rows (indices 48-63)
        var whiteCountBottom = 0
        var blackCountBottom = 0
        
        for (i in 48..63) {
            when (classifications[i]) {
                "White" -> whiteCountBottom++
                "Black" -> blackCountBottom++
            }
        }
        
        return if (whiteCountBottom >= blackCountBottom) "White" else "Black"
    }

    /**
     * Default orientation detection
     */
    private fun detectOrientation(classifications: List<String>): Boolean {
        var blackCountBottom = 0
        var whiteCountBottom = 0
        
        for (i in 48..63) {
            when (classifications[i]) {
                "White" -> whiteCountBottom++
                "Black" -> blackCountBottom++
            }
        }
        return blackCountBottom <= whiteCountBottom // true = normal, false = reversed
    }

    /**
     * Preprocess bitmap for TFLite model
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNEL_COUNT * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        resizedBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val value = intValues[pixel++]
                
                // Extract RGB values and normalize to [0,1]
                inputBuffer.putFloat(((value shr 16 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((value shr 8 and 0xFF) / 255.0f))
                inputBuffer.putFloat(((value and 0xFF) / 255.0f))
            }
        }
        
        return inputBuffer
    }

    /**
     * Create UCI format result and save to SharedPreferences
     */
    private fun createUCIResult(classifications: List<String>, orientation: Boolean): String {
        // Choose mapping based on orientation
        val chessSquares = if (orientation) {
            chessSquaresNormal
        } else {
            chessSquaresReversed
        }
        
        // Build piece positions
        val whitePieces = mutableListOf<String>()
        val blackPieces = mutableListOf<String>()
        
        for (i in classifications.indices) {
            when (classifications[i]) {
                "White" -> whitePieces.add(chessSquares[i])
                "Black" -> blackPieces.add(chessSquares[i])
            }
        }
        
        // Sort pieces alphabetically
        whitePieces.sort()
        blackPieces.sort()
        
        // Create UCI format strings
        val whiteUCI = whitePieces.joinToString(",")
        val blackUCI = blackPieces.joinToString(",")
        val mappingType = if (orientation) "normal" else "reversed"
        
        // Save to SharedPreferences
        Prefs.setString(context, "uci_white", whiteUCI)
        Prefs.setString(context, "uci_black", blackUCI)
        Prefs.setString(context, "uci_mapping", mappingType)
        
        // Create combined UCI string
        val combinedUCI = "W:$whiteUCI|B:$blackUCI|M:$mappingType"
        Prefs.setString(context, "uci", combinedUCI)
        
        // Create display message
        val message = buildString {
            append("White: ")
            append(whitePieces.joinToString(", "))
            append("\nBlack: ")
            append(blackPieces.joinToString(", "))
            append("\nMapping: ${if (orientation) "Normal (a-h)" else "Reversed (h-a)"}")
        }
        
        Log.d(TAG, "White pieces at: ${whitePieces.joinToString(", ")}")
        Log.d(TAG, "Black pieces at: ${blackPieces.joinToString(", ")}")
        Log.d(TAG, "Using: ${if (orientation) "Normal (a-h)" else "Reversed (h-a)"} mapping")
        Log.d(TAG, "Saved to Prefs - UCI: $combinedUCI")
        
        return message
    }

    /**
     * Send board state to backend server
     */
    private fun sendDataToBackend(context: Context) {
        CoroutineManager.launchIO {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                
                // Send start color if not sent yet
                val bottomColor = Prefs.getString(context, "bottom_color", "")
                if (!hasStartColorSent && bottomColor.isNotEmpty()) {
                    val colorLower = bottomColor.lowercase()
                    val (startSuccess, startResponse) = sendStartColor(ngrokUrl, colorLower)
                    if (startSuccess) {
                        hasStartColorSent = true
                        Log.d(TAG, "Start color sent: $colorLower. Response: $startResponse")
                        if (startResponse.isNotEmpty() && startResponse != "Invalid" && startResponse != "Game Over") {
                            Prefs.setString(context, "pending_ai_move", startResponse)
                            showToast("AI: $startResponse")
                        }
                    }
                }

                // Send piece positions
                val whiteUCI = Prefs.getString(context, "uci_white", "")
                val blackUCI = Prefs.getString(context, "uci_black", "")
                if (whiteUCI.isNotEmpty() && blackUCI.isNotEmpty()) {
                    val whitePositions = whiteUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val blackPositions = blackUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val (positionSuccess, positionResponse) = sendPiecePositions(
                        ngrokUrl,
                        whitePositions,
                        blackPositions
                    )

                    if (positionSuccess) {
                        if (positionResponse.isNotEmpty() && positionResponse != "Invalid" && positionResponse != "Game Over") {
                            Prefs.setString(context, "pending_ai_move", positionResponse)
                            showToast("AI: $positionResponse")
                        }
                        Log.d(TAG, "Board state sent to backend successfully")
                    } else {
                        Log.e(TAG, "Failed to send board state to backend")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in sendDataToBackend: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Send starting color to /start endpoint
     */
    private suspend fun sendStartColor(ngrokUrl: String, color: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$ngrokUrl/start"
                Log.d(TAG, "Sending start color: $color to $url")

                val requestBody = color.toRequestBody("text/plain".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val bodyString = response.body?.string()?.trim() ?: ""
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Start color sent, response: $bodyString")
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                    showToast("✗ /start failed: ${response.code}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending start color: ${e.message}")
                showToast("✗ Network error: ${e.message?.take(30)}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }

    /**
     * Send piece positions format: "white:a1,a2;black:a7,a8"
     */
    private suspend fun sendPiecePositions(
        ngrokUrl: String,
        whitePositions: List<String>,
        blackPositions: List<String>
    ): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$ngrokUrl/move"

                val whiteStr = whitePositions.joinToString(",")
                val blackStr = blackPositions.joinToString(",")
                val positionData = "white:$whiteStr;black:$blackStr"

                Log.d(TAG, "Sending positions to $url: $positionData")

                val requestBody = positionData.toRequestBody("text/plain".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val bodyString = response.body?.string()?.trim() ?: ""
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Positions sent, response: $bodyString")
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                    showToast("✗ /move failed: ${response.code}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending piece positions: ${e.message}")
                showToast("✗ Network error: ${e.message?.take(30)}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }

    /**
     * Show toast on UI thread
     */
    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show notification to user
     */
    private fun showNotification(context: Context, title: String, message: String) {
        CoroutineManager.launchMain {
            try {
                val notification = NotificationCompat.Builder(context, "screenshot_service_channel")
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .build()

                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing notification: ${e.message}")
            }
        }
    }

    /**
     * Recycle all bitmaps to free memory
     */
    private fun recycleBitmaps(pieces: List<Bitmap>) {
        try {
            pieces.forEach { 
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            Log.d(TAG, "Recycled ${pieces.size} bitmaps")
        } catch (e: Exception) {
            Log.e(TAG, "Error recycling bitmaps: ${e.message}")
        }
    }

    fun getInterpreter(index: Int = 0): Interpreter? {
        return if (index in 0..7) interpreters[index] else interpreters[0]
    }

    fun isModelLoaded(): Boolean {
        return interpreters[0] != null
    }

    fun close() {
        // Reset state variables
        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false
        
        // Close all interpreters
        for (i in 0 until 8) {
            interpreters[i]?.close()
            interpreters[i] = null
        }
        
        Log.d(TAG, "TFLiteModelManager closed")
    }

    companion object {
        private const val TAG = "TFLiteModelManager"
    }
}
package com.example.autoscreenshot

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private val classNames = arrayOf("White", "Black", "Empty")
    private val INPUT_SIZE = 96
    private val CHANNEL_COUNT = 3
    private val chessSquaresNormal = arrayOf(/* ... keep as before ... */)
    private val chessSquaresReversed = arrayOf(/* ... keep as before ... */)

    private var storedOrientation: Boolean? = null
    private var hasStoredOrientation = false
    private var hasStartColorSent = false

    init {
        initializeModel(context)
    }

    private fun initializeModel(context: Context) {
        try {
            val model = loadModelFile(context)
            for (i in 0 until 8) {
                interpreters[i] = Interpreter(model)
            }
        } catch (_: Exception) { }
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
            val classifications = classifyAllPieces(pieces)
            val orientation = determineOrientation(classifications)
            createUCIResult(classifications, orientation)
            sendDataToBackend(context)
        } catch (_: Exception) {
            CoroutineManager.launchMain {
                Toast.makeText(context, "Processing error", Toast.LENGTH_SHORT).show()
            }
        } finally {
            recycleBitmaps(pieces)
        }
    }

    private suspend fun classifyAllPieces(pieces: List<Bitmap>): List<String> {
        return withContext(Dispatchers.Default) {
            val classifications = Array(64) { "" }
            val deferredResults = (0 until 64 step 8).map { i ->
                async {
                    val interpreterIndex = (i / 8) % 8
                    val interpreter = interpreters[interpreterIndex]!!
                    for (j in i until minOf(i + 8, 64)) {
                        classifications[j] = classifyBitmapWithInterpreter(pieces[j], interpreter)
                    }
                }
            }
            deferredResults.awaitAll()
            classifications.toList()
        }
    }

    private fun classifyBitmapWithInterpreter(bitmap: Bitmap, interpreter: Interpreter): String {
        val input = preprocessBitmap(bitmap)
        val output = Array(1) { FloatArray(classNames.size) }
        interpreter.run(input, output)
        val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        return classNames[maxIndex]
    }

    private fun determineOrientation(classifications: List<String>): Boolean {
        val bottomColorPref = Prefs.getString(context, "bottom_color", "")
        val isFirstDetection = bottomColorPref.isEmpty()

        if (isFirstDetection && storedOrientation == null) {
            val bottomColor = detectBottomColor(classifications)
            Prefs.setString(context, "bottom_color", bottomColor)
            Prefs.setString(context, "board_orientation_detected", "true")
            storedOrientation = (bottomColor == "White")
            hasStoredOrientation = true
        } else if (storedOrientation == null) {
            storedOrientation = detectOrientation(classifications)
            hasStoredOrientation = true
        }

        return storedOrientation ?: true
    }

    private fun detectBottomColor(classifications: List<String>): String {
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

    private fun detectOrientation(classifications: List<String>): Boolean {
        var blackCountBottom = 0
        var whiteCountBottom = 0
        for (i in 48..63) {
            when (classifications[i]) {
                "White" -> whiteCountBottom++
                "Black" -> blackCountBottom++
            }
        }
        return blackCountBottom <= whiteCountBottom
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNEL_COUNT * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        resizedBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val value = intValues[pixel++]
                inputBuffer.putFloat((value shr 16 and 0xFF) / 255.0f)
                inputBuffer.putFloat((value shr 8 and 0xFF) / 255.0f)
                inputBuffer.putFloat((value and 0xFF) / 255.0f)
            }
        }
        return inputBuffer
    }

    private fun createUCIResult(classifications: List<String>, orientation: Boolean): String {
        val chessSquares = if (orientation) chessSquaresNormal else chessSquaresReversed
        val whitePieces = mutableListOf<String>()
        val blackPieces = mutableListOf<String>()
        for (i in classifications.indices) {
            when (classifications[i]) {
                "White" -> whitePieces.add(chessSquares[i])
                "Black" -> blackPieces.add(chessSquares[i])
            }
        }
        whitePieces.sort()
        blackPieces.sort()
        Prefs.setString(context, "uci_white", whitePieces.joinToString(","))
        Prefs.setString(context, "uci_black", blackPieces.joinToString(","))
        Prefs.setString(context, "uci_mapping", if (orientation) "normal" else "reversed")
        Prefs.setString(context, "uci", "W:${whitePieces.joinToString(",")}|B:${blackPieces.joinToString(",")}|M:${if (orientation) "normal" else "reversed"}")
        return ""
    }

    private fun sendDataToBackend(context: Context) {
        CoroutineManager.launchIO {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                val bottomColor = Prefs.getString(context, "bottom_color", "")
                if (!hasStartColorSent && bottomColor.isNotEmpty()) {
                    val colorLower = bottomColor.lowercase()
                    val (startSuccess, _) = sendStartColor(ngrokUrl, colorLower)
                    if (startSuccess) hasStartColorSent = true else showToast("Sending failed")
                }

                val whiteUCI = Prefs.getString(context, "uci_white", "")
                val blackUCI = Prefs.getString(context, "uci_black", "")
                if (whiteUCI.isNotEmpty() && blackUCI.isNotEmpty()) {
                    val whitePositions = whiteUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val blackPositions = blackUCI.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val (_, success) = sendPiecePositions(ngrokUrl, whitePositions, blackPositions)
                    if (!success) showToast("Sending failed")
                }
            } catch (_: Exception) { showToast("Sending failed") }
        }
    }

    private suspend fun sendStartColor(ngrokUrl: String, color: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$ngrokUrl/start"
                val requestBody = color.toRequestBody("text/plain".toMediaTypeOrNull())
                val request = Request.Builder().url(url).post(requestBody).build()
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                response.close()
                Pair(success, "")
            } catch (_: Exception) { Pair(false, "") }
        }
    }

    private suspend fun sendPiecePositions(ngrokUrl: String, whitePositions: List<String>, blackPositions: List<String>): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$ngrokUrl/move"
                val positionData = "white:${whitePositions.joinToString(",")};black:${blackPositions.joinToString(",")}"
                val requestBody = positionData.toRequestBody("text/plain".toMediaTypeOrNull())
                val request = Request.Builder().url(url).post(requestBody).build()
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                response.close()
                Pair(success, "")
            } catch (_: Exception) { Pair(false, "") }
        }
    }

    private fun showToast(message: String) {
        handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun recycleBitmaps(pieces: List<Bitmap>) {
        try { pieces.forEach { if (!it.isRecycled) it.recycle() } } catch (_: Exception) { }
    }

    fun getInterpreter(index: Int = 0): Interpreter? = if (index in 0..7) interpreters[index] else interpreters[0]
    fun isModelLoaded(): Boolean = interpreters[0] != null

    fun close() {
        storedOrientation = null
        hasStoredOrientation = false
        hasStartColorSent = false
        for (i in 0 until 8) {
            interpreters[i]?.close()
            interpreters[i] = null
        }
    }

    companion object { private const val TAG = "TFLiteModelManager" }
}
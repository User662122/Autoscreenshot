package com.example.autoscreenshot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.util.concurrent.Executors

class TFLiteModelManager(context: Context) {
    private var interpreters: Array<Interpreter?> = arrayOfNulls(8)
    private val MODEL_PATH = "wbe_mnv2_96.tflite"
    private val executorService = Executors.newFixedThreadPool(8)
    
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
            Log.d("TFLiteModelManager", "Initialized 8 interpreter instances")
        } catch (e: Exception) {
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

// ✅ MODIFIED: Optimized parallel classification using 8 interpreters
fun classifyChessBoard(pieces: List<Bitmap>, context: Context, storedOrientation: Boolean?, callback: (String, Boolean) -> Unit) {
    if (interpreters[0] == null) {
        Toast.makeText(context, "Model not loaded", Toast.LENGTH_SHORT).show()
        return
    }

    if (pieces.size != 64) {
        Toast.makeText(context, "Need exactly 64 pieces for chess board", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val classifications = Array(64) { "" }
        val futures = mutableListOf<java.util.concurrent.Future<*>>()

        // Process 8 pieces in parallel (each interpreter processes 1 piece at a time)
        for (i in 0 until 64 step 8) {
            val future = executorService.submit {
                // Use different interpreter for each batch of 8 pieces
                val interpreterIndex = (i / 8) % 8
                val interpreter = interpreters[interpreterIndex]!!

                // ✅ CORRECTED: Use minOf instead of min
                for (j in i until minOf(i + 8, 64)) {
                    val classification = classifyBitmapWithInterpreter(pieces[j], interpreter)
                    classifications[j] = classification
                    Log.d("ParallelClassification", "Processed piece $j with interpreter $interpreterIndex")
                }
            }
            futures.add(future)
        }

        // Wait for all tasks to complete
        futures.forEach { it.get() }

        Log.d("ParallelClassification", "All 64 pieces processed in parallel")

        // ✅ Rest of your existing logic remains same...
        val bottomColorPref = Prefs.getString(context, "bottom_color", "")
        val isFirstDetection = bottomColorPref.isEmpty()
        var detectedOrientation = storedOrientation

        if (isFirstDetection && storedOrientation == null) {
            val bottomColor = detectBottomColor(classifications.toList())
            Prefs.setString(context, "bottom_color", bottomColor)
            Prefs.setString(context, "board_orientation_detected", "true")
            detectedOrientation = (bottomColor == "White")

            Toast.makeText(context, "First detection: $bottomColor pieces at bottom", Toast.LENGTH_LONG).show()
            Log.d("ChessOrientation", "First detection: $bottomColor pieces at bottom, orientation: ${if (detectedOrientation) "normal" else "reversed"}")
        } else if (storedOrientation == null) {
            detectedOrientation = detectOrientation(classifications.toList())
        }

        val uciMapping = createUCIResult(classifications.toList(), detectedOrientation ?: true, context)
        callback(uciMapping, detectedOrientation ?: true)

    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Classification error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

    // ✅ NEW: Classify bitmap with specific interpreter
    private fun classifyBitmapWithInterpreter(bitmap: Bitmap, interpreter: Interpreter): String {
        val input = preprocessBitmap(bitmap)
        val output = Array(1) { FloatArray(classNames.size) }
        
        interpreter.run(input, output)
        
        val probabilities = output[0]
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        
        return classNames[maxIndex]
    }

    // ✅ NEW: Detect which color is at the bottom of the board
    private fun detectBottomColor(classifications: List<String>): String {
        // Check bottom two rows (rows 7 and 8 in normal chess notation)
        // In our array indices: bottom rows are indices 48-63
        
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

    // ✅ NEW: Default orientation detection (existing logic)
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

    private fun createUCIResult(classifications: List<String>, orientation: Boolean, context: Context): String {
    // Choose mapping based on the provided orientation
    val chessSquares = if (orientation) {
        chessSquaresNormal
    } else {
        chessSquaresReversed
    }
    
    // Build FEN-like representation with CURRENT piece positions
    val whitePieces = mutableListOf<String>()
    val blackPieces = mutableListOf<String>()
    
    for (i in classifications.indices) {
        when (classifications[i]) {
            "White" -> whitePieces.add(chessSquares[i])
            "Black" -> blackPieces.add(chessSquares[i])
            // Empty squares are not tracked
        }
    }
    
    // ✅ FIX: Sort the pieces in alphabetical order
    whitePieces.sort()
    blackPieces.sort()
    
    // Create UCI format strings
    val whiteUCI = whitePieces.joinToString(",")
    val blackUCI = blackPieces.joinToString(",")
    val mappingType = if (orientation) "normal" else "reversed"
    
    // Save to SharedPreferences using Prefs utility
    Prefs.setString(context, "uci_white", whiteUCI)
    Prefs.setString(context, "uci_black", blackUCI)
    Prefs.setString(context, "uci_mapping", mappingType)
    
    // Create combined UCI string
    val combinedUCI = "W:$whiteUCI|B:$blackUCI|M:$mappingType"
    Prefs.setString(context, "uci", combinedUCI)
    
    // Create display message with CURRENT positions
    val message = buildString {
        append("White: ")
        append(whitePieces.joinToString(", "))
        append("\nBlack: ")
        append(blackPieces.joinToString(", "))
        append("\nMapping: ${if (orientation) "Normal (a-h)" else "Reversed (h-a)"}")
    }
    
    // Show Toast with CURRENT UCI mapping
   // Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    
    // Also log for debugging
    Log.d("ChessClassification", "White pieces at: ${whitePieces.joinToString(", ")}")
    Log.d("ChessClassification", "Black pieces at: ${blackPieces.joinToString(", ")}")
    Log.d("ChessClassification", "Using: ${if (orientation) "Normal (a-h)" else "Reversed (h-a)"} mapping")
    Log.d("ChessClassification", "Saved to Prefs - UCI: $combinedUCI")
    
    return message
}

    fun getInterpreter(index: Int = 0): Interpreter? {
        return if (index in 0..7) interpreters[index] else interpreters[0]
    }

    fun isModelLoaded(): Boolean {
        return interpreters[0] != null
    }

    fun close() {
        for (i in 0 until 8) {
            interpreters[i]?.close()
        }
        executorService.shutdown()
    }
}

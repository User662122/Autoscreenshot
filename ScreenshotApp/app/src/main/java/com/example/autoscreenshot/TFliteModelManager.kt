package com.example.autoscreenshot

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class TFLiteModelManager(context: Context) {
    private var interpreter: Interpreter? = null
    private val MODEL_PATH = "wbe_mnv2_96.tflite"
    
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
            interpreter = Interpreter(model)
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

    // ✅ FIXED: Added orientation parameter and return both mapping AND orientation
    fun classifyChessBoard(pieces: List<Bitmap>, context: Context, storedOrientation: Boolean?, callback: (String, Boolean) -> Unit) {
        if (interpreter == null) {
            Toast.makeText(context, "Model not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        if (pieces.size != 64) {
            Toast.makeText(context, "Need exactly 64 pieces for chess board", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val classifications = mutableListOf<String>()
            
            // Classify each piece
            for (piece in pieces) {
                val classification = classifyBitmap(piece)
                classifications.add(classification)
            }
            
            // Determine orientation (use stored if available, otherwise detect)
            val orientation = if (storedOrientation != null) {
                storedOrientation
            } else {
                // Detect orientation based on bottom two rows
                var blackCountBottom = 0
                var whiteCountBottom = 0
                
                for (i in 48..63) {
                    when (classifications[i]) {
                        "White" -> whiteCountBottom++
                        "Black" -> blackCountBottom++
                    }
                }
                blackCountBottom <= whiteCountBottom // true = normal, false = reversed
            }
            
            // Create UCI mapping with determined orientation
            val uciMapping = createUCIResult(classifications, orientation, context)
            
            // ✅ FIXED: Return both mapping and orientation
            callback(uciMapping, orientation)
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Classification error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun classifyBitmap(bitmap: Bitmap): String {
        val input = preprocessBitmap(bitmap)
        val output = Array(1) { FloatArray(classNames.size) }
        
        interpreter?.run(input, output)
        
        val probabilities = output[0]
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        
        return classNames[maxIndex]
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

    // ✅ FIXED: Use provided orientation instead of detecting every time
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
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    
    // Also log for debugging
    android.util.Log.d("ChessClassification", "White pieces at: ${whitePieces.joinToString(", ")}")
    android.util.Log.d("ChessClassification", "Black pieces at: ${blackPieces.joinToString(", ")}")
    android.util.Log.d("ChessClassification", "Using: ${if (orientation) "Normal (a-h)" else "Reversed (h-a)"} mapping")
    android.util.Log.d("ChessClassification", "Saved to Prefs - UCI: $combinedUCI")
    
    return message
}

    fun getInterpreter(): Interpreter? {
        return interpreter
    }

    fun isModelLoaded(): Boolean {
        return interpreter != null
    }

    fun close() {
        interpreter?.close()
    }
}
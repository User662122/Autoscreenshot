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
    
    // Chess board mapping (a1-h8)
    private val chessSquares = arrayOf(
        "a8", "b8", "c8", "d8", "e8", "f8", "g8", "h8",
        "a7", "b7", "c7", "d7", "e7", "f7", "g7", "h7", 
        "a6", "b6", "c6", "d6", "e6", "f6", "g6", "h6",
        "a5", "b5", "c5", "d5", "e5", "f5", "g5", "h5",
        "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4",
        "a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3",
        "a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2",
        "a1", "b1", "c1", "d1", "e1", "f1", "g1", "h1"
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

    fun classifyChessBoard(pieces: List<Bitmap>, context: Context) {
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
            
            // Create UCI mapping and display
            displayUCIResult(classifications, context)
            
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

    private fun displayUCIResult(classifications: List<String>, context: Context) {
        // Build FEN-like representation
        val whitePieces = mutableListOf<String>()
        val blackPieces = mutableListOf<String>()
        
        for (i in classifications.indices) {
            when (classifications[i]) {
                "White" -> whitePieces.add(chessSquares[i])
                "Black" -> blackPieces.add(chessSquares[i])
                // Empty squares are not tracked
            }
        }
        
        // Create display message
        val message = buildString {
            append("White: ")
            append(whitePieces.joinToString(", "))
            append("\nBlack: ")
            append(blackPieces.joinToString(", "))
        }
        
        // Show Toast with UCI mapping
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        
        // Also log for debugging
        android.util.Log.d("ChessClassification", "White pieces at: ${whitePieces.joinToString(", ")}")
        android.util.Log.d("ChessClassification", "Black pieces at: ${blackPieces.joinToString(", ")}")
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
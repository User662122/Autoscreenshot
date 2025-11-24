package com.example.autoscreenshot

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class TFLiteModelManager(context: Context) {
    private var interpreter: Interpreter? = null
    private val MODEL_PATH = "wbe_mnv2_96.tflite"

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
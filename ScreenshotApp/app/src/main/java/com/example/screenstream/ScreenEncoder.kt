package com.example.screenstream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val context: Context
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isEncoding = false
    private var encoderThread: Thread? = null
    
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    
    private val TAG = "ScreenEncoder"
    
    // Encoding parameters
    private val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
    private val FRAME_RATE = 30
    private val I_FRAME_INTERVAL = 2 // seconds
    private val BIT_RATE = 2_000_000 // 2 Mbps
    
    var onEncodedFrame: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    init {
        setupEncoder()
        setupMuxer()
    }

    private fun setupEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                
                // Low latency settings
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder?.createInputSurface()
            encoder?.start()
            
            isEncoding = true
            startEncoderThread()
            
            Log.d(TAG, "Encoder initialized: ${width}x${height} @ ${FRAME_RATE}fps, ${BIT_RATE}bps")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up encoder", e)
            throw e
        }
    }
    
    private fun setupMuxer() {
        try {
            val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenStreams")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            val outputFile = File(outputDir, "screen_${System.currentTimeMillis()}.mp4")
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            Log.d(TAG, "Muxer initialized: ${outputFile.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up muxer", e)
        }
    }

    private fun startEncoderThread() {
        encoderThread = Thread {
            try {
                encodeLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Encoder thread error", e)
            }
        }.apply {
            name = "EncoderThread"
            start()
        }
    }

    private fun encodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isEncoding) {
            try {
                val outputBufferIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: continue
                
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder?.outputFormat
                        Log.d(TAG, "Output format changed: $newFormat")
                        
                        // Setup muxer with new format
                        if (muxer != null && !isMuxerStarted) {
                            videoTrackIndex = muxer!!.addTrack(newFormat!!)
                            muxer!!.start()
                            isMuxerStarted = true
                            Log.d(TAG, "Muxer started")
                        }
                    }
                    
                    outputBufferIndex >= 0 -> {
                        val encodedData = encoder?.getOutputBuffer(outputBufferIndex)
                        
                        if (encodedData != null) {
                            // Write to file via muxer
                            if (isMuxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            
                            // Send to WebRTC
                            onEncodedFrame?.invoke(encodedData, bufferInfo)
                        }
                        
                        encoder?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in encode loop", e)
                if (!isEncoding) break
            }
        }
        
        Log.d(TAG, "Encode loop finished")
    }

    fun getInputSurface(): Surface? {
        return inputSurface
    }

    fun release() {
        Log.d(TAG, "Releasing encoder")
        isEncoding = false
        
        encoderThread?.join(2000)
        encoderThread = null
        
        try {
            if (isMuxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing muxer", e)
        }
        
        try {
            encoder?.stop()
            encoder?.release()
            encoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
        
        inputSurface?.release()
        inputSurface = null
        
        Log.d(TAG, "Encoder released")
    }
}

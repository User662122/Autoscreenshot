package com.example.autoscreenshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object NetworkManager {
    private val client = OkHttpClient()
    private const val TAG = "NetworkManager"
    
    /**
     * Send starting color to /start endpoint
     * @param context Application context
     * @param color "white" or "black"
     */
    suspend fun sendStartColor(context: Context, color: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                val url = "$ngrokUrl/start"
                
                Log.d(TAG, "Sending start color: $color to $url")
                
                val requestBody = color.toRequestBody("text/plain".toMediaTypeOrNull())
                
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                
                if (success) {
                    Log.d(TAG, "Start color sent successfully: ${response.body?.string()}")
                } else {
                    Log.e(TAG, "Failed to send start color: ${response.code} - ${response.message}")
                }
                
                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error sending start color: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
    
    /**
     * Send piece positions to /move endpoint
     * Format: "white:a1,a2,a3 black:h7,h8"
     * @param context Application context
     * @param whitePositions List of white piece positions
     * @param blackPositions List of black piece positions
     */
    suspend fun sendPiecePositions(
        context: Context,
        whitePositions: List<String>,
        blackPositions: List<String>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                val url = "$ngrokUrl/move"
                
                // Format: "white:a1,a2,a3 black:h7,h8"
                val whiteStr = whitePositions.joinToString(",")
                val blackStr = blackPositions.joinToString(",")
                val positionData = "white:$whiteStr black:$blackStr"
                
                Log.d(TAG, "Sending piece positions to $url: $positionData")
                
                val requestBody = positionData.toRequestBody("text/plain".toMediaTypeOrNull())
                
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                
                if (success) {
                    Log.d(TAG, "Piece positions sent successfully: ${response.body?.string()}")
                } else {
                    Log.e(TAG, "Failed to send positions: ${response.code} - ${response.message}")
                }
                
                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error sending piece positions: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
    
    /**
     * Send UCI move format to /move endpoint
     * Format: "e2e4"
     * @param context Application context
     * @param move UCI move string
     */
    suspend fun sendMove(context: Context, move: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                val url = "$ngrokUrl/move"
                
                Log.d(TAG, "Sending move to $url: $move")
                
                val requestBody = move.toRequestBody("text/plain".toMediaTypeOrNull())
                
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                
                if (success) {
                    Log.d(TAG, "Move sent successfully: ${response.body?.string()}")
                } else {
                    Log.e(TAG, "Failed to send move: ${response.code} - ${response.message}")
                }
                
                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Error sending move: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
}
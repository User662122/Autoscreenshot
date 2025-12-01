package com.example.autoscreenshot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object NetworkManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private const val TAG = "NetworkManager"
    private val handler = Handler(Looper.getMainLooper())

    private fun showToast(context: Context, message: String) {
        handler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Send starting color to /start endpoint
     * Returns Pair(success, responseText)
     */
    suspend fun sendStartColor(context: Context, color: String): Pair<Boolean, String> {
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
                val bodyString = response.body?.string()?.trim() ?: ""
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Start color sent, response: $bodyString")
                    // Show only AI move response
                    if (bodyString.isNotEmpty() && bodyString != "Invalid" && bodyString != "Game Over") {
                        showToast(context, "AI: $bodyString")
                    }
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                    showToast(context, "❌ /start failed: ${response.code}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending start color: ${e.message}")
                showToast(context, "❌ Network error: ${e.message?.take(30)}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }

    /**
     * Send piece positions format:
     * "white:a1,a2;black:a7,a8"
     */
    suspend fun sendPiecePositions(
        context: Context,
        whitePositions: List<String>,
        blackPositions: List<String>
    ): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
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
                    // Show only AI move response
                    if (bodyString.isNotEmpty() && bodyString != "Invalid" && bodyString != "Game Over") {
                        showToast(context, "AI: $bodyString")
                    }
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                    showToast(context, "❌ /move failed: ${response.code}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending piece positions: ${e.message}")
                showToast(context, "❌ Network error: ${e.message?.take(30)}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }

    /**
     * Send UCI move "e2e4"
     */
    suspend fun sendMove(context: Context, move: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val ngrokUrl = MainActivity.getNgrokUrl(context)
                val url = "$ngrokUrl/move"

                Log.d(TAG, "Sending move: $move → $url")

                val requestBody = move.toRequestBody("text/plain".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val bodyString = response.body?.string()?.trim() ?: ""
                val success = response.isSuccessful

                if (success) {
                    Log.d(TAG, "Move sent, AI Response: $bodyString")
                    // Show only AI move response
                    if (bodyString.isNotEmpty() && bodyString != "Invalid" && bodyString != "Game Over") {
                        showToast(context, "AI: $bodyString")
                    }
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                    showToast(context, "❌ Failed: ${response.code}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending move: ${e.message}")
                showToast(context, "❌ Network error: ${e.message?.take(30)}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }
}
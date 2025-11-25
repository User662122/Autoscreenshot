package com.example.autoscreenshot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object NetworkManager {
    private val client = OkHttpClient()
    private const val TAG = "NetworkManager"

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
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending start color: ${e.message}")
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
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending piece positions: ${e.message}")
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
                } else {
                    Log.e(TAG, "Failed: ${response.code} - ${response.message}")
                }

                response.close()
                Pair(success, bodyString)

            } catch (e: Exception) {
                Log.e(TAG, "Error sending move: ${e.message}")
                e.printStackTrace()
                Pair(false, "")
            }
        }
    }
}
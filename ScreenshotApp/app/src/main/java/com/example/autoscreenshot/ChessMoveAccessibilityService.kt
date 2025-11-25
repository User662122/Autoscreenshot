package com.example.autoscreenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException

class ChessMoveAccessibilityService : AccessibilityService() {
    
    companion object {
        fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
            val service = "${context.packageName}/${ChessMoveAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabledServices?.contains(service) == true
        }
        
        fun openAccessibilitySettings(context: android.content.Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    
    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isRunning = false
    private val TAG = "ChessMoveAccessibility"
    
    // Chess board coordinates (based on your crop: x1=11, y1=505, x2=709, y2=1201)
    private val BOARD_LEFT = 11
    private val BOARD_TOP = 505
    private val BOARD_RIGHT = 709
    private val BOARD_BOTTOM = 1201
    
    private val BOARD_WIDTH = BOARD_RIGHT - BOARD_LEFT
    private val BOARD_HEIGHT = BOARD_BOTTOM - BOARD_TOP
    private val CELL_WIDTH = BOARD_WIDTH / 8
    private val CELL_HEIGHT = BOARD_HEIGHT / 8
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Chess Move Accessibility Service Connected")
        isRunning = true
        startPollingForMoves()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for our use case
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
    
    private fun startPollingForMoves() {
        serviceScope.launch {
            // Wait for /start to be called first
            while (isRunning) {
                val bottomColor = Prefs.getString(this@ChessMoveAccessibilityService, "bottom_color", "")
                
                if (bottomColor.isNotEmpty()) {
                    Log.d(TAG, "Start color detected: $bottomColor. Beginning move polling...")
                    break
                }
                
                Log.d(TAG, "Waiting for /start to be called...")
                delay(3000)
            }
            
            // Now start polling for moves
            while (isRunning) {
                try {
                    val move = fetchMoveFromBackend()
                    if (move != null && move.isNotEmpty()) {
                        Log.d(TAG, "Received move: $move")
                        executeMove(move)
                    }
                    // Poll every 2 seconds
                    delay(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop: ${e.message}")
                    e.printStackTrace()
                    delay(5000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun fetchMoveFromBackend(): String? = withContext(Dispatchers.IO) {
        try {
            val ngrokUrl = MainActivity.getNgrokUrl(this@ChessMoveAccessibilityService)
            val url = "$ngrokUrl/move"
            
            Log.d(TAG, "Polling /move endpoint: $url")
            
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(null, ByteArray(0))) // Empty body
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val move = response.body?.string()?.trim()
                Log.d(TAG, "Backend response: $move")
                response.close()
                return@withContext move
            } else {
                Log.d(TAG, "Backend returned: ${response.code}")
                response.close()
                return@withContext null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching move: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
    
    private suspend fun executeMove(move: String) {
        if (move.length < 4) {
            Log.e(TAG, "Invalid move format: $move")
            return
        }
        
        try {
            // Parse UCI format: e2e4
            val fromSquare = move.substring(0, 2) // e2
            val toSquare = move.substring(2, 4)   // e4
            
            Log.d(TAG, "Executing move: $fromSquare -> $toSquare")
            
            // Get screen coordinates
            val fromCoords = squareToScreenCoordinates(fromSquare)
            val toCoords = squareToScreenCoordinates(toSquare)
            
            if (fromCoords != null && toCoords != null) {
                // First tap - pick up piece
                performTap(fromCoords.first, fromCoords.second)
                delay(300) // Wait between taps
                
                // Second tap - place piece
                performTap(toCoords.first, toCoords.second)
                
                Log.d(TAG, "Move executed successfully")
            } else {
                Log.e(TAG, "Failed to convert squares to coordinates")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing move: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun squareToScreenCoordinates(square: String): Pair<Float, Float>? {
        if (square.length != 2) return null
        
        val file = square[0] // a-h
        val rank = square[1] // 1-8
        
        // Check orientation from SharedPreferences
        val bottomColor = Prefs.getString(this, "bottom_color", "White")
        
        val col: Int
        val row: Int
        
        if (bottomColor == "White") {
            // Normal orientation: a-h left to right, 1-8 bottom to top
            col = file - 'a' // 0-7
            row = 7 - (rank - '1') // 0-7 (inverted)
        } else {
            // Reversed orientation: h-a left to right, 8-1 bottom to top
            col = 'h' - file // 0-7
            row = rank - '1' // 0-7
        }
        
        // Calculate center of the square
        val x = BOARD_LEFT + (col * CELL_WIDTH) + (CELL_WIDTH / 2)
        val y = BOARD_TOP + (row * CELL_HEIGHT) + (CELL_HEIGHT / 2)
        
        Log.d(TAG, "Square $square -> Screen coordinates ($x, $y)")
        
        return Pair(x.toFloat(), y.toFloat())
    }
    
    private suspend fun performTap(x: Float, y: Float) = withContext(Dispatchers.Main) {
        try {
            val path = Path()
            path.moveTo(x, y)
            
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(path, 0, 50)
            )
            
            val gesture = gestureBuilder.build()
            
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(TAG, "Tap completed at ($x, $y)")
                        continuation.resume(true) {}
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.e(TAG, "Tap cancelled at ($x, $y)")
                        continuation.resume(false) {}
                    }
                }, null)
            }
            
            if (!result) {
                Log.e(TAG, "Failed to perform tap")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing tap: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        Log.d(TAG, "Chess Move Accessibility Service Destroyed")
    }
}
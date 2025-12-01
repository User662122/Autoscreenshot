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
            
            // Now start polling SharedPreferences for moves
            while (isRunning) {
                try {
                    // Check if screenshot service is still running
                    val isServiceActive = Prefs.getString(this@ChessMoveAccessibilityService, "screenshot_service_active", "false")
                    
                    if (isServiceActive != "true") {
                        Log.d(TAG, "Screenshot service stopped. Pausing move polling...")
                        delay(3000)
                        continue
                    }
                    
                    // Read pending move from SharedPreferences (set by ScreenshotService)
                    val move = fetchMoveFromSharedPreferences()
                    if (move != null && move.isNotEmpty()) {
                        Log.d(TAG, "Found pending move: $move")
                        executeMove(move)
                        
                        // Clear the move after execution to prevent duplicate execution
                        Prefs.setString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
                        Log.d(TAG, "Cleared pending move after execution")
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
    
    private fun fetchMoveFromSharedPreferences(): String? {
        // Read pending AI move from SharedPreferences (stored by ScreenshotService)
        val pendingMove = Prefs.getString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
        
        if (pendingMove.isNotEmpty()) {
            Log.d(TAG, "Found pending AI move in SharedPreferences: $pendingMove")
            return pendingMove
        }
        
        Log.d(TAG, "No pending move in SharedPreferences")
        return null
    }
    
    private suspend fun executeMove(move: String) {
    if (move.length < 4) {
        Log.e(TAG, "Invalid move: $move")
        return
    }

    try {
        val fromSquare = move.substring(0, 2)
        val toSquare = move.substring(2, 4)

        Log.d(TAG, "Executing move: $fromSquare → $toSquare")

        val from = squareToScreenCoordinates(fromSquare)
        val to = squareToScreenCoordinates(toSquare)

        if (from == null || to == null) {
            Log.e(TAG, "Coordinate conversion failed for $move")
            return
        }

        // First tap
        val tap1 = performTap(from.first, from.second)
        if (!tap1) {
            Log.e(TAG, "FAILED: First tap could not be completed for $move")
            return
        }

        delay(300)

        // Second tap
        val tap2 = performTap(to.first, to.second)
        if (!tap2) {
            Log.e(TAG, "FAILED: Second tap could not be completed for $move")
            return
        }

        Log.d(TAG, "Move executed SUCCESSFULLY: $fromSquare → $toSquare")

    } catch (e: Exception) {
        Log.e(TAG, "executeMove error: ${e.message}")
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
    
    private suspend fun performTap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
    var attempt = 0
    while (attempt < 3) {
        attempt++
        try {
            val path = Path().apply {
                moveTo(x, y)
            }

            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(path, 0, 50)
            )

            val gesture = gestureBuilder.build()

            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                dispatchGesture(gesture, object : GestureResultCallback() {

                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Tap success at ($x,$y) | attempt $attempt")
                        continuation.resume(true) {}
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.e(TAG, "Tap cancelled at ($x,$y) | attempt $attempt")
                        continuation.resume(false) {}
                    }

                }, null)
            }

            if (result) return@withContext true   // success → exit

            Log.e(TAG, "Tap failed attempt $attempt at ($x,$y)")

            delay(150) // retry delay

        } catch (e: Exception) {
            Log.e(TAG, "Tap exception attempt $attempt: ${e.message}")
        }
    }

    Log.e(TAG, "Tap failed permanently after 3 attempts at ($x,$y)")
    return@withContext false
}    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        Log.d(TAG, "Chess Move Accessibility Service Destroyed")
    }
}
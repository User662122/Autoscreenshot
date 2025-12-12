package com.example.autoscreenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class ChessMoveAccessibilityService : AccessibilityService() {
    
    companion object {
        private var instance: ChessMoveAccessibilityService? = null
        
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
        
        // Function to restart polling from outside
        fun restartPolling() {
            instance?.restartPollingInternal()
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var isRunning = false
    private var pollingJob: Job? = null
    private val TAG = "ChessMoveAccessibility"
    
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
        instance = this
        isRunning = true
        showToast("🟢 Accessibility Service Connected")
        startPollingForMoves()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    
    override fun onInterrupt() {}

    private fun restartPollingInternal() {
        showToast("🔄 Restarting polling...")
        pollingJob?.cancel()
        pollingJob = null
        startPollingForMoves()
    }

    private fun startPollingForMoves() {
        // Cancel any existing polling job
        pollingJob?.cancel()
        
        pollingJob = CoroutineManager.launchIO {
            showToast("⏳ Waiting 19 seconds for board detection...")
            
            // Wait for 19 seconds first
            delay(19000)
            
            // Wait for bottom_color to be set
            var waitCounter = 0
            while (isRunning && isActive) {
                val bottomColor = Prefs.getString(this@ChessMoveAccessibilityService, "bottom_color", "")
                
                if (bottomColor.isNotEmpty()) {
                    showToast("✅ Board detected! Color: $bottomColor")
                    break
                }
                
                waitCounter++
                if (waitCounter % 5 == 0) {
                    showToast("⏳ Still waiting... (color: ${if(bottomColor.isEmpty()) "none" else bottomColor})")
                }
                
                delay(3000)
            }
            
            if (!isActive) {
                showToast("⚠️ Polling cancelled during wait")
                return@launchIO
            }
            
            showToast("🚀 Starting move polling loop")
            
            while (isRunning && isActive) {
                try {
                    val pendingMove = Prefs.getString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
                    
                    if (pendingMove.isNotEmpty()) {
                        showToast("🎯 Executing move: $pendingMove")
                        Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, true)
                        val success = executeMove(pendingMove)
                        
                        if (success) {
                            showToast("✅ Move executed: $pendingMove")
                            Prefs.setString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
                        } else {
                            showToast("❌ Move execution failed: $pendingMove")
                        }
                        
                        Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, false)
                    } else {
                        showToast("📡 Checking for AI move...")
                        fetchAndStoreAIMove()
                    }
                    
                    delay(2000)
                } catch (e: Exception) {
                    showToast("❌ Polling error: ${e.message}")
                    Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, false)
                    delay(5000)
                }
            }
            
            showToast("🛑 Polling stopped")
        }
    }
    
    private suspend fun fetchAndStoreAIMove() {
        try {
            val ngrokUrl = MainActivity.getNgrokUrl(this@ChessMoveAccessibilityService)
            val url = "$ngrokUrl/getmove"
            
            showToast("📡 Fetching from backend...")
            
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: ""
            response.close()
            
            showToast("📨 Backend response: '$body'")
            
            if (body.isNotEmpty() && body != "None" && body != "Invalid" && body != "Game Over") {
                Prefs.setString(this@ChessMoveAccessibilityService, "pending_ai_move", body)
                showToast("✅ AI Move: $body")
            } else if (body == "None") {
                showToast("⏸️ No move available yet")
            } else if (body == "Invalid") {
                showToast("⚠️ Invalid board state")
            } else if (body == "Game Over") {
                showToast("🏁 Game Over")
            } else {
                showToast("⚠️ Empty response from backend")
            }
        } catch (e: Exception) {
            showToast("❌ Fetch error: ${e.message}")
        }
    }
    
    private suspend fun executeMove(move: String): Boolean {
        if (move.length < 4) {
            showToast("❌ Invalid move format: $move")
            return false
        }

        try {
            val fromSquare = move.substring(0, 2)
            val toSquare = move.substring(2, 4)

            showToast("🎯 Moving from $fromSquare to $toSquare")

            val from = squareToScreenCoordinates(fromSquare)
            val to = squareToScreenCoordinates(toSquare)

            if (from == null || to == null) {
                showToast("❌ Invalid coordinates for move")
                return false
            }

            showToast("👆 Tapping: ${fromSquare} (${from.first.toInt()}, ${from.second.toInt()})")
            val tap1 = performTap(from.first, from.second)
            if (!tap1) {
                showToast("❌ First tap failed")
                return false
            }

            delay(300)

            showToast("👆 Tapping: ${toSquare} (${to.first.toInt()}, ${to.second.toInt()})")
            val tap2 = performTap(to.first, to.second)
            if (!tap2) {
                showToast("❌ Second tap failed")
                return false
            }

            return true

        } catch (e: Exception) {
            showToast("❌ Execute error: ${e.message}")
            return false
        }
    }
    
    private fun squareToScreenCoordinates(square: String): Pair<Float, Float>? {
        if (square.length != 2) return null
        
        val file = square[0]
        val rank = square[1]
        
        val bottomColor = Prefs.getString(this, "bottom_color", "White")
        
        val col: Int
        val row: Int
        
        if (bottomColor == "White") {
            col = file - 'a'
            row = 7 - (rank - '1')
        } else {
            col = 'h' - file
            row = rank - '1'
        }
        
        val x = BOARD_LEFT + (col * CELL_WIDTH) + (CELL_WIDTH / 2)
        val y = BOARD_TOP + (row * CELL_HEIGHT) + (CELL_HEIGHT / 2)
        
        return Pair(x.toFloat(), y.toFloat())
    }
    
    private suspend fun performTap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val path = Path().apply { moveTo(x, y) }
                val gestureBuilder = GestureDescription.Builder()
                gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                val gesture = gestureBuilder.build()

                val result = suspendCancellableCoroutine<Boolean> { continuation ->
                    dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            continuation.resume(true) {}
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            continuation.resume(false) {}
                        }
                    }, null)
                }

                if (result) return@withContext true
                delay(150)

            } catch (e: Exception) {
                if (attempt == 3) {
                    showToast("❌ Tap failed after 3 attempts")
                }
            }
        }
        return@withContext false
    }
    
    private fun showToast(message: String) {
        CoroutineManager.launchMain {
            Toast.makeText(this@ChessMoveAccessibilityService, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingJob?.cancel()
        instance = null
        showToast("🔴 Accessibility Service Destroyed")
    }
}
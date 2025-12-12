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
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var isRunning = false
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
        isRunning = true
        startPollingForMoves()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    
    override fun onInterrupt() {}

    private fun startPollingForMoves() {
        CoroutineManager.launchIO {
            while (isRunning) {
                val bottomColor = Prefs.getString(this@ChessMoveAccessibilityService, "bottom_color", "")
                if (bottomColor.isNotEmpty()) break
                delay(3000)
            }
            
            while (isRunning) {
                try {
                    val isServiceActive = Prefs.getString(this@ChessMoveAccessibilityService, "screenshot_service_active", "false")
                    
                    if (isServiceActive != "true") {
                        delay(3000)
                        continue
                    }
                    
                    val pendingMove = Prefs.getString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
                    
                    if (pendingMove.isNotEmpty()) {
                        Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, true)
                        val success = executeMove(pendingMove)
                        
                        if (success) {
                            Prefs.setString(this@ChessMoveAccessibilityService, "pending_ai_move", "")
                        }
                        
                        Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, false)
                    } else {
                        fetchAndStoreAIMove()
                    }
                    
                    delay(2000)
                } catch (e: Exception) {
                    Prefs.setMoveExecuting(this@ChessMoveAccessibilityService, false)
                    delay(5000)
                }
            }
        }
    }
    
    private suspend fun fetchAndStoreAIMove() {
        try {
            val ngrokUrl = MainActivity.getNgrokUrl(this@ChessMoveAccessibilityService)
            val url = "$ngrokUrl/getmove"
            
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: ""
            response.close()
            
            if (body.isNotEmpty() && body != "None" && body != "Invalid" && body != "Game Over") {
                Prefs.setString(this@ChessMoveAccessibilityService, "pending_ai_move", body)
                showToast("AI: $body")
            }
        } catch (e: Exception) {}
    }
    
    private suspend fun executeMove(move: String): Boolean {
        if (move.length < 4) return false

        try {
            val fromSquare = move.substring(0, 2)
            val toSquare = move.substring(2, 4)

            val from = squareToScreenCoordinates(fromSquare)
            val to = squareToScreenCoordinates(toSquare)

            if (from == null || to == null) return false

            val tap1 = performTap(from.first, from.second)
            if (!tap1) return false

            delay(300)

            val tap2 = performTap(to.first, to.second)
            if (!tap2) return false

            return true

        } catch (e: Exception) {
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

            } catch (e: Exception) {}
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
        CoroutineManager.cancelAll()
    }
}
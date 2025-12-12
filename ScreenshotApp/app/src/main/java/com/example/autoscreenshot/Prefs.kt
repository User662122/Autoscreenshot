package com.example.autoscreenshot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object Prefs {
    private const val PREFS_NAME = "mydata"
    private const val TAG = "Prefs"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setString(context: Context, key: String, value: String) {
        with(getPreferences(context).edit()) {
            putString(key, value)
            apply()
        }
    }

    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return getPreferences(context).getString(key, defaultValue) ?: defaultValue
    }

    fun remove(context: Context, key: String) {
        with(getPreferences(context).edit()) {
            remove(key)
            apply()
        }
    }

    fun clear(context: Context) {
        with(getPreferences(context).edit()) {
            clear()
            apply()
        }
    }

    /**
     * Check if there's a pending AI move waiting to be executed
     */
    fun hasPendingMove(context: Context): Boolean {
        val move = getString(context, "pending_ai_move", "")
        return move.isNotEmpty()
    }

    /**
     * Mark that move execution has started
     */
    fun setMoveExecuting(context: Context, isExecuting: Boolean) {
        setString(context, "move_executing", if (isExecuting) "true" else "false")
    }

    /**
     * Check if a move is currently being executed
     */
    fun isMoveExecuting(context: Context): Boolean {
        return getString(context, "move_executing", "false") == "true"
    }

    fun resetAllGameData(context: Context) {
        Log.d(TAG, "Resetting all game data in SharedPreferences")
        
        val keysToRemove = listOf(
            "bottom_color",
            "uci_white",
            "uci_black",
            "uci_mapping",
            "uci",
            "pending_ai_move",
            "move_executing",
            "board_orientation_detected"
        )
        
        with(getPreferences(context).edit()) {
            keysToRemove.forEach { key ->
                remove(key)
                Log.d(TAG, "Removed key: $key")
            }
            apply()
        }
        
        Log.d(TAG, "All game data reset complete")
    }
} 
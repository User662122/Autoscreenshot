// File: ScreenshotApp/app/src/main/java/com/example/autoscreenshot/Prefs.kt
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

    fun resetAllGameData(context: Context) {
        Log.d(TAG, "Resetting all game data in SharedPreferences")
        
        val keysToRemove = listOf(
            "bottom_color",
            "uci_white",
            "uci_black",
            "uci_mapping",
            "uci",
            "pending_ai_move",
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
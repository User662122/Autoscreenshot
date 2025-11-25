// File: ScreenshotApp/app/src/main/java/com/example/autoscreenshot/Prefs.kt
package com.example.autoscreenshot

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREFS_NAME = "mydata"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // String value सेट करने के लिए
    fun setString(context: Context, key: String, value: String) {
        with(getPreferences(context).edit()) {
            putString(key, value)
            apply()
        }
    }

    // String value प्राप्त करने के लिए
    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return getPreferences(context).getString(key, defaultValue) ?: defaultValue
    }

    // Clear specific key
    fun remove(context: Context, key: String) {
        with(getPreferences(context).edit()) {
            remove(key)
            apply()
        }
    }

    // Clear all data
    fun clear(context: Context) {
        with(getPreferences(context).edit()) {
            clear()
            apply()
        }
    }
}
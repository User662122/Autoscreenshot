package com.example.autoscreenshot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object Prefs {
    private const val PREFS_NAME = "mydata"
    private const val TAG = "Prefs"
    
    // Global listener interface
    interface OnPreferenceChangeListener {
        fun onPreferenceChanged(key: String, value: String?)
    }
    
    // Store all registered listeners
    private val listeners = mutableMapOf<String, MutableList<OnPreferenceChangeListener>>()
    
    // SharedPreferences change listener
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var sharedPreferences: SharedPreferences? = null
    
    private fun getPreferences(context: Context): SharedPreferences {
        if (sharedPreferences == null) {
            sharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            setupGlobalListener(context.applicationContext)
        }
        return sharedPreferences!!
    }
    
    /**
     * Setup global SharedPreferences listener
     */
    private fun setupGlobalListener(context: Context) {
        if (sharedPrefsListener != null) return
        
        sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != null) {
                val value = prefs.getString(key, null)
                Log.d(TAG, "SharedPreference changed: $key = $value")
                
                // Notify all registered listeners for this key
                listeners[key]?.forEach { listener ->
                    try {
                        listener.onPreferenceChanged(key, value)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying listener for key $key: ${e.message}")
                    }
                }
                
                // Notify wildcard listeners (registered for all keys)
                listeners["*"]?.forEach { listener ->
                    try {
                        listener.onPreferenceChanged(key, value)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error notifying wildcard listener: ${e.message}")
                    }
                }
            }
        }
        
        getPreferences(context).registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        Log.d(TAG, "Global SharedPreferences listener registered")
    }
    
    /**
     * Register a listener for a specific key or all keys ("*")
     * @param key The preference key to listen to, or "*" for all keys
     * @param listener The listener callback
     */
    fun registerListener(context: Context, key: String, listener: OnPreferenceChangeListener) {
        // Ensure global listener is set up
        getPreferences(context)
        
        if (!listeners.containsKey(key)) {
            listeners[key] = mutableListOf()
        }
        listeners[key]?.add(listener)
        Log.d(TAG, "Listener registered for key: $key (Total listeners for this key: ${listeners[key]?.size})")
    }
    
    /**
     * Unregister a specific listener for a key
     */
    fun unregisterListener(key: String, listener: OnPreferenceChangeListener) {
        listeners[key]?.remove(listener)
        if (listeners[key]?.isEmpty() == true) {
            listeners.remove(key)
        }
        Log.d(TAG, "Listener unregistered for key: $key")
    }
    
    /**
     * Unregister all listeners for a specific key
     */
    fun unregisterAllListeners(key: String) {
        listeners.remove(key)
        Log.d(TAG, "All listeners unregistered for key: $key")
    }
    
    /**
     * Clear all listeners
     */
    fun clearAllListeners() {
        listeners.clear()
        Log.d(TAG, "All listeners cleared")
    }

    fun setString(context: Context, key: String, value: String) {
        with(getPreferences(context).edit()) {
            putString(key, value)
            apply() // This will trigger the listener automatically
        }
    }

    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return getPreferences(context).getString(key, defaultValue) ?: defaultValue
    }

    fun remove(context: Context, key: String) {
        with(getPreferences(context).edit()) {
            remove(key)
            apply() // This will trigger the listener automatically
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
    
    /**
     * Clean up when no longer needed
     */
    fun cleanup(context: Context) {
        sharedPrefsListener?.let {
            getPreferences(context).unregisterOnSharedPreferenceChangeListener(it)
            sharedPrefsListener = null
        }
        listeners.clear()
        sharedPreferences = null
        Log.d(TAG, "Prefs cleanup complete")
    }
}
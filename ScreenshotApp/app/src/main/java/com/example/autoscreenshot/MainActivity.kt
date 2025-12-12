package com.example.autoscreenshot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.autoscreenshot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var sharedPreferences: SharedPreferences
    
    // Constants for SharedPreferences
    private val PREFS_NAME = "AutoScreenshotPrefs"
    private val NGROK_URL_KEY = "ngrok_url"
    private val DEFAULT_NGROK_URL = "https://your-ngrok-url.ngrok.io"
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startMediaProjection()
        } else {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = Intent(this, ScreenshotService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            binding.statusText.text = "Screenshot service started"
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = true
            Toast.makeText(this, "Screenshot service started successfully", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Media projection permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // SharedPreferences initialize करें
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // App खुलते ही saved Ngrok URL को load करें
        loadSavedNgrokUrl()
        
        // Check accessibility service status
        updateAccessibilityStatus()
        
        binding.startButton.setOnClickListener {
            checkPermissionsAndStart()
        }
        
        binding.stopButton.setOnClickListener {
            // 1. सभी कोरोटीन को कैंसल करें
            CoroutineManager.cancelAll()
            
            // 2. सभी गेम डेटा को रीसेट करें
            Prefs.resetAllGameData(this)
            
            // 3. Screenshot सर्विस को स्टॉप करें
            stopService(Intent(this, ScreenshotService::class.java))
            
            // 4. स्टेटस अपडेट करें
            binding.statusText.text = "Screenshot service stopped"
            binding.startButton.isEnabled = true
            binding.stopButton.isEnabled = false
            
            Toast.makeText(this, "All coroutines cancelled and game data reset", Toast.LENGTH_SHORT).show()
        }
        
        // Set Ngrok URL button
        binding.setUrlButton.setOnClickListener {
            setNgrokUrl()
        }
        
        // Clear Ngrok URL button (optional)
        binding.clearUrlButton.setOnClickListener {
            clearNgrokUrl()
        }
        
        // Enable Accessibility Service button
        binding.enableAccessibilityButton.setOnClickListener {
            ChessMoveAccessibilityService.openAccessibilitySettings(this)
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }
    
    private fun updateAccessibilityStatus() {
        val isEnabled = ChessMoveAccessibilityService.isAccessibilityServiceEnabled(this)
        binding.accessibilityStatusText.text = if (isEnabled) {
            "Accessibility Service: Enabled ✓"
        } else {
            "Accessibility Service: Disabled"
        }
        binding.enableAccessibilityButton.isEnabled = !isEnabled
    }
    
    private fun loadSavedNgrokUrl() {
        val savedUrl = sharedPreferences.getString(NGROK_URL_KEY, DEFAULT_NGROK_URL)
        binding.urlEditText.setText(savedUrl)
        binding.currentUrlText.text = "Current URL: $savedUrl"
    }
    
    private fun setNgrokUrl() {
        val newUrl = binding.urlEditText.text.toString().trim()
        
        if (newUrl.isEmpty()) {
            Toast.makeText(this, "Please enter a valid Ngrok URL", Toast.LENGTH_SHORT).show()
            return
        }
        
        // URL validate करें (basic validation)
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            Toast.makeText(this, "Please enter a valid URL starting with http:// or https://", Toast.LENGTH_SHORT).show()
            return
        }
        
        // SharedPreferences में save करें
        with(sharedPreferences.edit()) {
            putString(NGROK_URL_KEY, newUrl)
            apply()
        }
        
        binding.currentUrlText.text = "Current URL: $newUrl"
        Toast.makeText(this, "Ngrok URL updated successfully", Toast.LENGTH_SHORT).show()
        
        // Log for debugging
        Log.d("NgrokURL", "New URL set: $newUrl")
    }
    
    private fun clearNgrokUrl() {
        with(sharedPreferences.edit()) {
            remove(NGROK_URL_KEY)
            apply()
        }
        
        binding.urlEditText.setText("")
        binding.currentUrlText.text = "Current URL: Not Set"
        Toast.makeText(this, "Ngrok URL cleared", Toast.LENGTH_SHORT).show()
    }
    
    // अन्य classes में Ngrok URL access करने के लिए function
    fun getNgrokUrl(): String {
        return sharedPreferences.getString(NGROK_URL_KEY, DEFAULT_NGROK_URL) ?: DEFAULT_NGROK_URL
    }
    
    // Static function जिसे कहीं भी access कर सकते हैं
    companion object {
        fun getNgrokUrl(context: Context): String {
            val prefs = context.getSharedPreferences("AutoScreenshotPrefs", Context.MODE_PRIVATE)
            return prefs.getString("ngrok_url", "https://your-ngrok-url.ngrok.io") ?: "https://your-ngrok-url.ngrok.io"
        }
    }
    
    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check storage permissions for Android 10 and below
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // Check notification permission for Android 13 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startMediaProjection()
        }
    }
    
    private fun startMediaProjection() {
        try {
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting media projection: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
}
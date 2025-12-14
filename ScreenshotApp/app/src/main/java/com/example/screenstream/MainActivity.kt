package com.example.screenstream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.screenstream.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    
    private val TAG = "MainActivity"
    
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
            val intent = Intent(this, ScreenStreamService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Get device IP address
            val ipAddress = getDeviceIpAddress()
            val serverUrl = if (ipAddress != null) {
                "http://$ipAddress:8080"
            } else {
                "http://YOUR_DEVICE_IP:8080"
            }
            
            binding.statusText.text = "Screen streaming active"
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = true
            binding.urlText.text = "Open in browser: $serverUrl"
            
            Toast.makeText(this, "Server: $serverUrl", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Media projection permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Display device IP on startup
        val ipAddress = getDeviceIpAddress()
        if (ipAddress != null) {
            binding.urlText.text = "Your device IP: $ipAddress"
        }
        
        binding.startButton.setOnClickListener {
            checkPermissionsAndStart()
        }
        
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, ScreenStreamService::class.java))
            binding.statusText.text = "Screen streaming stopped"
            binding.startButton.isEnabled = true
            binding.stopButton.isEnabled = false
            binding.urlText.text = if (ipAddress != null) "Your device IP: $ipAddress" else ""
            Toast.makeText(this, "Screen streaming stopped", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
    }
    
    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Check storage permission for recording
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
            Log.e(TAG, "Error starting media projection", e)
        }
    }
}

package com.example.autoscreenshot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.autoscreenshot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

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

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, ScreenshotService::class.java))
            binding.statusText.text = "Screenshot service stopped"
            binding.startButton.isEnabled = true
            binding.stopButton.isEnabled = false
            Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
        }

        binding.ngrokButton.setOnClickListener {
            showNgrokDialog()
        }
    }

    private fun showNgrokDialog() {
        val editText = EditText(this)
        editText.hint = "Enter public ngrok URL"

        val existing = getSharedPreferences("global", MODE_PRIVATE)
            .getString("ngrok_url", "")

        editText.setText(existing)

        AlertDialog.Builder(this)
            .setTitle("Set Ngrok URL")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    getSharedPreferences("global", MODE_PRIVATE)
                        .edit()
                        .putString("ngrok_url", url)
                        .apply()

                    Toast.makeText(this, "Ngrok URL Saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun getNgrokUrl(): String {
        return getSharedPreferences("global", MODE_PRIVATE)
            .getString("ngrok_url", "") ?: ""
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
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
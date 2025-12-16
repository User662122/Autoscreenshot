package com.example.screenstream

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit

class NgrokManager(private val context: Context) {
    
    private val TAG = "NgrokManager"
    
    companion object {
        const val NGROK_AUTH_TOKEN = "2vMT6zkluhyDdvKpqWGGTRHPxjK_7kQALRF8wAYLJ2HdUKbT8"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var ngrokProcess: Process? = null
    private var httpTunnelUrl: String? = null
    private var wsTunnelUrl: String? = null
    private var isRunning = false
    
    interface TunnelCallback {
        fun onTunnelCreated(httpUrl: String, wsUrl: String)
        fun onTunnelProgress(message: String)
        fun onError(error: String)
    }
    
    fun startTunnels(httpPort: Int, wsPort: Int, callback: TunnelCallback) {
        if (isRunning) {
            callback.onError("Tunnels already running")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    callback.onTunnelProgress("Preparing ngrok binary...")
                }
                
                val ngrokBinary = findNgrokBinary()
                if (ngrokBinary == null) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Failed to find executable ngrok binary. Your device may not support running native binaries.")
                    }
                    return@launch
                }
                
                Log.d(TAG, "Using ngrok binary at: ${ngrokBinary.absolutePath}")
                
                withContext(Dispatchers.Main) {
                    callback.onTunnelProgress("Configuring ngrok...")
                }
                
                configureNgrok(ngrokBinary)
                
                withContext(Dispatchers.Main) {
                    callback.onTunnelProgress("Starting HTTP tunnel on port $httpPort...")
                }
                
                startNgrokProcess(ngrokBinary, httpPort)
                
                delay(3000)
                
                withContext(Dispatchers.Main) {
                    callback.onTunnelProgress("Fetching tunnel URLs...")
                }
                
                var retries = 0
                var tunnelUrl: String? = null
                
                while (retries < 10 && tunnelUrl == null) {
                    tunnelUrl = fetchTunnelUrl()
                    if (tunnelUrl == null) {
                        delay(1000)
                        retries++
                    }
                }
                
                if (tunnelUrl != null) {
                    httpTunnelUrl = tunnelUrl
                    wsTunnelUrl = tunnelUrl.replace("https://", "wss://").replace("http://", "ws://")
                    isRunning = true
                    
                    withContext(Dispatchers.Main) {
                        callback.onTunnelCreated(httpTunnelUrl!!, wsTunnelUrl!!)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback.onError("Failed to get tunnel URL after $retries retries")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tunnels", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Error: ${e.message}")
                }
            }
        }
    }
    
    private fun findNgrokBinary(): File? {
        // Priority 1: Check native library directory (jniLibs - has execute permissions)
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeNgrok = File(nativeLibDir, "libngrok.so")
        
        Log.d(TAG, "Checking native lib dir: ${nativeLibDir.absolutePath}")
        Log.d(TAG, "libngrok.so exists: ${nativeNgrok.exists()}, canExecute: ${nativeNgrok.canExecute()}")
        
        if (nativeNgrok.exists()) {
            // The .so file in native lib dir should be executable by default
            if (testExecutable(nativeNgrok)) {
                Log.d(TAG, "Using ngrok from native library directory")
                return nativeNgrok
            }
        }
        
        // Priority 2: Try to extract from assets to various directories
        val possibleDirs = listOf(
            nativeLibDir,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.codeCacheDir else null,
            context.cacheDir,
            context.filesDir
        ).filterNotNull()
        
        for (dir in possibleDirs) {
            Log.d(TAG, "Trying to extract ngrok to: ${dir.absolutePath}")
            val result = tryExtractToDirectory(dir)
            if (result != null) {
                return result
            }
        }
        
        return null
    }
    
    private fun tryExtractToDirectory(targetDir: File): File? {
        return try {
            val ngrokFile = File(targetDir, "ngrok_exec")
            
            // Check if binary already exists and is executable
            if (ngrokFile.exists()) {
                if (testExecutable(ngrokFile)) {
                    return ngrokFile
                } else {
                    ngrokFile.delete()
                }
            }
            
            // Determine the correct architecture binary
            val arch = Build.SUPPORTED_ABIS[0]
            val assetName = when {
                arch.contains("arm64") || arch.contains("aarch64") -> "ngrok-arm64"
                arch.contains("arm") -> "ngrok-arm"
                else -> "ngrok-arm64"
            }
            
            Log.d(TAG, "Device architecture: $arch, using asset: $assetName")
            
            // Extract the binary
            val assetManager = context.assets
            val inputStream = try {
                assetManager.open(assetName)
            } catch (e: IOException) {
                Log.e(TAG, "ngrok binary '$assetName' not found in assets", e)
                return null
            }
            
            FileOutputStream(ngrokFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()
            
            // Set all possible permissions
            ngrokFile.setReadable(true, false)
            ngrokFile.setWritable(true, false)
            ngrokFile.setExecutable(true, false)
            
            // Try chmod as well
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", ngrokFile.absolutePath)).waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "chmod failed: ${e.message}")
            }
            
            // Test if we can actually execute it
            if (testExecutable(ngrokFile)) {
                return ngrokFile
            } else {
                ngrokFile.delete()
                return null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting to ${targetDir.absolutePath}: ${e.message}")
            null
        }
    }
    
    private fun testExecutable(file: File): Boolean {
        if (!file.exists()) {
            Log.d(TAG, "File does not exist: ${file.absolutePath}")
            return false
        }
        
        // Try to run the binary with version command
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(file.absolutePath, "version"))
            
            // Read output in background to prevent blocking
            val output = StringBuilder()
            val error = StringBuilder()
            
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.append(it) }
                } catch (e: Exception) { }
            }.start()
            
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { error.append(it) }
                } catch (e: Exception) { }
            }.start()
            
            val exitCode = process.waitFor()
            Log.d(TAG, "ngrok test - exit: $exitCode, output: $output, error: $error")
            
            // Consider it executable if it ran without permission denied error
            exitCode == 0 || output.contains("ngrok") || error.contains("ngrok")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute ngrok test at ${file.absolutePath}: ${e.message}")
            // Check if it's specifically a permission error
            val isPermissionError = e.message?.contains("Permission denied") == true || 
                                    e.message?.contains("error=13") == true
            if (isPermissionError) {
                Log.e(TAG, "Permission denied for ${file.absolutePath}")
            }
            false
        }
    }
    
    private fun configureNgrok(ngrokBinary: File) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                ngrokBinary.absolutePath, 
                "config", 
                "add-authtoken", 
                NGROK_AUTH_TOKEN
            ))
            
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            
            Log.d(TAG, "ngrok config output: $output")
            if (error.isNotEmpty()) {
                Log.d(TAG, "ngrok config error: $error")
            }
            
            process.waitFor(10, TimeUnit.SECONDS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring ngrok", e)
        }
    }
    
    private fun startNgrokProcess(ngrokBinary: File, port: Int) {
        try {
            ngrokProcess?.destroy()
            
            ngrokProcess = Runtime.getRuntime().exec(arrayOf(
                ngrokBinary.absolutePath,
                "http",
                port.toString()
            ))
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ngrokProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        Log.d(TAG, "ngrok: $line")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading ngrok output", e)
                }
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ngrokProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                        Log.e(TAG, "ngrok stderr: $line")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading ngrok error output", e)
                }
            }
            
            Log.d(TAG, "ngrok process started for port $port")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ngrok process", e)
            throw e
        }
    }
    
    private fun fetchTunnelUrl(): String? {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:4040/api/tunnels")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val tunnels = json.getJSONArray("tunnels")
                
                if (tunnels.length() > 0) {
                    for (i in 0 until tunnels.length()) {
                        val tunnel = tunnels.getJSONObject(i)
                        val publicUrl = tunnel.getString("public_url")
                        if (publicUrl.startsWith("https://")) {
                            Log.d(TAG, "Found tunnel URL: $publicUrl")
                            return publicUrl
                        }
                    }
                    val firstTunnel = tunnels.getJSONObject(0)
                    return firstTunnel.getString("public_url")
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tunnel URL", e)
            null
        }
    }
    
    fun getHttpTunnelUrl(): String? = httpTunnelUrl
    fun getWsTunnelUrl(): String? = wsTunnelUrl
    
    fun stopTunnels() {
        try {
            ngrokProcess?.destroy()
            ngrokProcess = null
            httpTunnelUrl = null
            wsTunnelUrl = null
            isRunning = false
            Log.d(TAG, "ngrok tunnels stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnels", e)
        }
    }
    
    fun isRunning(): Boolean = isRunning
}
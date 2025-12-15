package com.example.screenstream

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
                
                val ngrokBinary = extractNgrokBinary()
                if (ngrokBinary == null) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Failed to extract ngrok binary. Please ensure ngrok is in assets folder.")
                    }
                    return@launch
                }
                
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
    
    private fun extractNgrokBinary(): File? {
        return try {
            val ngrokFile = File(context.filesDir, "ngrok")
            
            if (ngrokFile.exists() && ngrokFile.canExecute()) {
                Log.d(TAG, "ngrok binary already exists")
                return ngrokFile
            }
            
            val assetManager = context.assets
            val inputStream: InputStream
            
            val arch = Build.SUPPORTED_ABIS[0]
            val assetName = when {
                arch.contains("arm64") || arch.contains("aarch64") -> "ngrok-arm64"
                arch.contains("arm") -> "ngrok-arm"
                else -> "ngrok-arm64"
            }
            
            Log.d(TAG, "Device architecture: $arch, using asset: $assetName")
            
            try {
                inputStream = assetManager.open(assetName)
            } catch (e: IOException) {
                Log.e(TAG, "ngrok binary '$assetName' not found in assets", e)
                return null
            }
            
            val outputStream = FileOutputStream(ngrokFile)
            
            inputStream.copyTo(outputStream)
            
            inputStream.close()
            outputStream.close()
            
            ngrokFile.setExecutable(true)
            
            Log.d(TAG, "ngrok binary extracted to ${ngrokFile.absolutePath}")
            ngrokFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ngrok binary", e)
            null
        }
    }
    
    private fun configureNgrok(ngrokBinary: File) {
        try {
            val configProcess = ProcessBuilder(
                ngrokBinary.absolutePath,
                "config",
                "add-authtoken",
                NGROK_AUTH_TOKEN
            )
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            
            val output = configProcess.inputStream.bufferedReader().readText()
            Log.d(TAG, "ngrok config output: $output")
            
            configProcess.waitFor(10, TimeUnit.SECONDS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring ngrok", e)
        }
    }
    
    private fun startNgrokProcess(ngrokBinary: File, port: Int) {
        try {
            ngrokProcess?.destroy()
            
            ngrokProcess = ProcessBuilder(
                ngrokBinary.absolutePath,
                "http",
                port.toString()
            )
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = ngrokProcess?.inputStream?.bufferedReader()
                    var line: String?
                    while (reader?.readLine().also { line = it } != null) {
                        Log.d(TAG, "ngrok: $line")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading ngrok output", e)
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
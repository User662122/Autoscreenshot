package com.example.screenstream

import android.content.Context
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Timer
import java.util.TimerTask

/**
 * FIXED VERSION v4 - Fixes Cloudflare Tunnel timeout (~100 seconds)
 * 
 * Issue: Cloudflare tunnels have idle connection timeout (~100 seconds)
 * WebSocket ping frames may not count as traffic for Cloudflare.
 * 
 * Fix: Send actual JSON text messages as keep-alive that Cloudflare recognizes as traffic
 */
class CombinedServer(port: Int, private val context: Context? = null) : NanoWSD(port) {

    private val TAG = "CombinedServer"
    private val clients = mutableListOf<WebSocketClient>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Keep-alive must be frequent enough to beat Cloudflare's ~100s timeout
    // Using 30 seconds to be safe
    private var keepAliveTimer: Timer? = null
    private val KEEP_ALIVE_INTERVAL = 30000L // 30 seconds
    private val KEEP_ALIVE_INITIAL_DELAY = 5000L // Start after 5 seconds

    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    private fun showToast(message: String) {
        context?.let {
            mainHandler.post {
                Toast.makeText(it, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * CRITICAL: Override to set infinite socket timeout
     */
    override fun createClientHandler(finalAccept: Socket, inputStream: java.io.InputStream): ClientHandler {
        try {
            finalAccept.soTimeout = 0 // Infinite timeout
            finalAccept.keepAlive = true // Enable TCP keep-alive
            Log.d(TAG, "Socket configured: timeout=infinite, keepAlive=true")
        } catch (e: Exception) {
            Log.e(TAG, "Socket config error", e)
        }
        return super.createClientHandler(finalAccept, inputStream)
    }

    fun startKeepAliveTimer() {
        stopKeepAliveTimer()
        keepAliveTimer = Timer("WebSocketKeepAlive", true)
        keepAliveTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                sendKeepAliveToAllClients()
            }
        }, KEEP_ALIVE_INITIAL_DELAY, KEEP_ALIVE_INTERVAL)
        Log.d(TAG, "Keep-alive timer started: interval=${KEEP_ALIVE_INTERVAL}ms")
    }

    fun stopKeepAliveTimer() {
        try {
            keepAliveTimer?.cancel()
            keepAliveTimer = null
        } catch (e: Exception) {
            Log.e(TAG, "Timer stop error", e)
        }
    }

    /**
     * Send actual JSON text message as keep-alive
     * This ensures Cloudflare sees traffic and doesn't timeout
     */
    private fun sendKeepAliveToAllClients() {
        val keepAliveMessage = JSONObject().apply {
            put("type", "ping")
            put("timestamp", System.currentTimeMillis())
        }.toString()
        
        synchronized(clients) {
            val disconnected = mutableListOf<WebSocketClient>()
            clients.forEach { client ->
                try {
                    if (client.isOpen) {
                        // Send both: WebSocket ping frame AND JSON text message
                        client.ping(byteArrayOf(0x01))
                        client.send(keepAliveMessage)
                        Log.v(TAG, "Keep-alive sent")
                    } else {
                        disconnected.add(client)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-alive failed: ${e.message}")
                    disconnected.add(client)
                }
            }
            if (disconnected.isNotEmpty()) {
                clients.removeAll(disconnected.toSet())
                Log.d(TAG, "Removed ${disconnected.size} dead clients")
            }
        }
    }

    private fun loadHtmlPage(): String {
        context?.let {
            try {
                return it.assets.open("viewer.html").bufferedReader().use { reader ->
                    reader.readText()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load viewer.html", e)
            }
        }
        return getEmbeddedHtml()
    }

    private fun getEmbeddedHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android Screen Stream</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .container {
            background: white;
            border-radius: 20px;
            padding: 30px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
            max-width: 1200px;
            width: 100%;
        }
        h1 { color: #333; margin-bottom: 10px; text-align: center; font-size: 28px; }
        .status {
            text-align: center;
            padding: 15px;
            border-radius: 10px;
            margin-bottom: 20px;
            font-weight: 600;
        }
        .status.waiting { background: #fff3cd; color: #856404; }
        .status.connected { background: #d4edda; color: #155724; }
        .status.streaming { background: #d1ecf1; color: #0c5460; }
        .status.error { background: #f8d7da; color: #721c24; }
        .video-container {
            position: relative;
            background: #000;
            border-radius: 10px;
            overflow: hidden;
            aspect-ratio: 9 / 19.5;
            max-height: 75vh;
            margin: 0 auto;
        }
        video { width: 100%; height: 100%; object-fit: contain; }
        .controls {
            display: flex;
            gap: 10px;
            justify-content: center;
            margin-top: 20px;
        }
        button {
            padding: 12px 24px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
        }
        button.danger { background: #dc3545; color: white; }
        button:disabled { opacity: 0.5; cursor: not-allowed; }
        .info { margin-top: 20px; padding: 15px; background: #f8f9fa; border-radius: 10px; }
        .stats { display: flex; justify-content: space-around; margin-top: 15px; }
        .stat-item { text-align: center; }
        .stat-value { font-size: 18px; font-weight: bold; }
        .stat-label { font-size: 12px; color: #666; }
        .debug {
            margin-top: 10px;
            padding: 10px;
            background: #f0f0f0;
            border-radius: 5px;
            font-size: 12px;
            font-family: monospace;
            max-height: 150px;
            overflow-y: auto;
        }
        .play-overlay {
            position: absolute;
            top: 0; left: 0; width: 100%; height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            background: rgba(0,0,0,0.5);
            cursor: pointer;
        }
        .play-overlay.hidden { display: none; }
        .play-btn {
            width: 80px; height: 80px;
            background: rgba(255,255,255,0.9);
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .play-btn::after {
            content: '';
            border-style: solid;
            border-width: 20px 0 20px 35px;
            border-color: transparent transparent transparent #667eea;
            margin-left: 8px;
        }
    </style>
</head>
<body>
<div class="container">
    <h1>Android Screen Stream</h1>
    <div id="status" class="status waiting">Connecting...</div>
    <div class="video-container">
        <video id="remoteVideo" autoplay playsinline muted></video>
        <div id="playOverlay" class="play-overlay hidden">
            <div class="play-btn"></div>
        </div>
    </div>
    <div class="controls">
        <button id="stopBtn" class="danger" disabled>Stop Stream</button>
    </div>
    <div class="info">
        <p><strong>Status:</strong> <span id="statusText">Initializing...</span></p>
        <p><strong>Connection Time:</strong> <span id="connTime">0s</span></p>
        <div class="stats">
            <div class="stat-item">
                <div class="stat-value" id="connectionStatus">Connecting</div>
                <div class="stat-label">Connection</div>
            </div>
            <div class="stat-item">
                <div class="stat-value" id="resolution">-</div>
                <div class="stat-label">Resolution</div>
            </div>
            <div class="stat-item">
                <div class="stat-value" id="pingCount">0</div>
                <div class="stat-label">Pings</div>
            </div>
        </div>
        <div class="debug" id="debugLog"></div>
    </div>
</div>
<script>
var video = document.getElementById('remoteVideo');
var status = document.getElementById('status');
var statusText = document.getElementById('statusText');
var stopBtn = document.getElementById('stopBtn');
var connectionStatus = document.getElementById('connectionStatus');
var resolution = document.getElementById('resolution');
var pingCountEl = document.getElementById('pingCount');
var connTimeEl = document.getElementById('connTime');
var debugLog = document.getElementById('debugLog');
var playOverlay = document.getElementById('playOverlay');

var config = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
var ws = null, pc = null, pendingIceCandidates = [], reconnectAttempts = 0;
var pingCount = 0;
var connStartTime = 0;
var connTimer = null;

// Client-side keep-alive: send pong every 25 seconds
var clientKeepAliveInterval = null;

function log(msg) {
    console.log(msg);
    var line = document.createElement('div');
    line.textContent = new Date().toLocaleTimeString() + ': ' + msg;
    debugLog.appendChild(line);
    debugLog.scrollTop = debugLog.scrollHeight;
    // Keep only last 50 lines
    while (debugLog.children.length > 50) {
        debugLog.removeChild(debugLog.firstChild);
    }
}

function updateStatus(msg, type) {
    status.textContent = msg;
    status.className = 'status ' + (type || 'waiting');
    statusText.textContent = msg;
}

function startConnTimer() {
    connStartTime = Date.now();
    if (connTimer) clearInterval(connTimer);
    connTimer = setInterval(function() {
        var secs = Math.floor((Date.now() - connStartTime) / 1000);
        var mins = Math.floor(secs / 60);
        secs = secs % 60;
        connTimeEl.textContent = mins + 'm ' + secs + 's';
    }, 1000);
}

function stopConnTimer() {
    if (connTimer) {
        clearInterval(connTimer);
        connTimer = null;
    }
}

function startClientKeepAlive() {
    stopClientKeepAlive();
    clientKeepAliveInterval = setInterval(function() {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
            log('Client keep-alive sent');
        }
    }, 25000); // Every 25 seconds
}

function stopClientKeepAlive() {
    if (clientKeepAliveInterval) {
        clearInterval(clientKeepAliveInterval);
        clientKeepAliveInterval = null;
    }
}

video.onplaying = function() { 
    log('Video playing'); 
    playOverlay.classList.add('hidden');
    updateStatus('Streaming!', 'streaming');
};

playOverlay.onclick = function() {
    video.play().then(function() { playOverlay.classList.add('hidden'); });
};

function connect() {
    var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var port = location.port || (location.protocol === 'https:' ? '443' : '80');
    var url = protocol + '//' + location.hostname + ':' + port;
    log('Connecting: ' + url);

    ws = new WebSocket(url);
    
    ws.onopen = function() {
        log('Connected');
        reconnectAttempts = 0;
        pingCount = 0;
        pingCountEl.textContent = '0';
        connectionStatus.textContent = 'Connected';
        startConnTimer();
        startClientKeepAlive();
        ws.send(JSON.stringify({ type: 'client-connected' }));
    };
    
    ws.onmessage = function(e) {
        try {
            var data = JSON.parse(e.data);
            
            // Handle ping/pong silently
            if (data.type === 'ping') {
                pingCount++;
                pingCountEl.textContent = pingCount;
                // Respond with pong
                ws.send(JSON.stringify({ type: 'pong', timestamp: data.timestamp }));
                return;
            }
            
            log('Received: ' + data.type);
            if (data.type === 'offer') handleOffer(data);
            else if (data.type === 'ice-candidate') handleIce(data);
        } catch (err) { log('Parse error: ' + err); }
    };
    
    ws.onclose = function(e) {
        log('Closed: code=' + e.code + ', reason=' + (e.reason || 'none'));
        stopConnTimer();
        stopClientKeepAlive();
        connectionStatus.textContent = 'Disconnected';
        
        if (!e.wasClean && reconnectAttempts < 5) {
            reconnectAttempts++;
            updateStatus('Reconnecting (' + reconnectAttempts + '/5)...', 'waiting');
            setTimeout(connect, 2000);
        } else {
            updateStatus('Disconnected (code: ' + e.code + ')', 'error');
        }
    };
    
    ws.onerror = function() { log('WebSocket error'); };
}

function handleOffer(data) {
    log('Processing offer');
    pc = new RTCPeerConnection(config);
    
    pc.onicecandidate = function(e) {
        if (e.candidate && ws.readyState === 1) {
            ws.send(JSON.stringify({
                type: 'ice-candidate',
                sdpMid: e.candidate.sdpMid,
                sdpMLineIndex: e.candidate.sdpMLineIndex,
                candidate: e.candidate.candidate
            }));
        }
    };
    
    pc.ontrack = function(e) {
        log('Got track: ' + e.track.kind);
        video.srcObject = e.streams[0] || new MediaStream([e.track]);
        connectionStatus.textContent = 'Streaming';
        stopBtn.disabled = false;
        setTimeout(function() { 
            video.play().catch(function() { 
                playOverlay.classList.remove('hidden'); 
            }); 
        }, 100);
        
        if (e.track.kind === 'video') {
            setTimeout(function() {
                var s = e.track.getSettings();
                if (s.width) resolution.textContent = s.width + 'x' + s.height;
            }, 1000);
        }
    };
    
    pc.onconnectionstatechange = function() {
        log('WebRTC state: ' + pc.connectionState);
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
            updateStatus('WebRTC ' + pc.connectionState, 'error');
        }
    };
    
    pc.setRemoteDescription({ type: 'offer', sdp: data.sdp })
        .then(function() {
            pendingIceCandidates.forEach(function(c) {
                pc.addIceCandidate({ sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex, candidate: c.candidate });
            });
            pendingIceCandidates = [];
            return pc.createAnswer();
        })
        .then(function(answer) { return pc.setLocalDescription(answer); })
        .then(function() {
            ws.send(JSON.stringify({ type: 'answer', sdp: pc.localDescription.sdp }));
            log('Answer sent');
        })
        .catch(function(err) { log('Error: ' + err); });
}

function handleIce(data) {
    if (!data.candidate) return;
    if (!pc || !pc.remoteDescription) {
        pendingIceCandidates.push(data);
        return;
    }
    pc.addIceCandidate({ sdpMid: data.sdpMid, sdpMLineIndex: data.sdpMLineIndex, candidate: data.candidate });
}

stopBtn.onclick = function() {
    if (pc) { pc.close(); pc = null; }
    if (video.srcObject) { 
        video.srcObject.getTracks().forEach(function(t) { t.stop(); }); 
        video.srcObject = null; 
    }
    stopBtn.disabled = true;
    stopConnTimer();
    stopClientKeepAlive();
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'stop' }));
    updateStatus('Stopped', 'waiting');
};

window.onload = connect;
window.onbeforeunload = function() {
    stopConnTimer();
    stopClientKeepAlive();
    if (ws) ws.close();
};
</script>
</body>
</html>
        """.trimIndent()
    }

    inner class WebSocketClient(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        
        private var connectionTime = System.currentTimeMillis()
        private var lastPongTime = System.currentTimeMillis()
        
        override fun onOpen() {
            try {
                synchronized(clients) { clients.add(this) }
                connectionTime = System.currentTimeMillis()
                lastPongTime = connectionTime
                Log.d(TAG, "Client connected. Total: ${clients.size}")
                
                mainHandler.post {
                    try { onClientConnected?.invoke() }
                    catch (e: Exception) { Log.e(TAG, "onClientConnected error", e) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onOpen error", e)
            }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            try {
                synchronized(clients) { clients.remove(this) }
                val duration = (System.currentTimeMillis() - connectionTime) / 1000
                Log.w(TAG, "CLOSED - Code: ${code?.name}, Duration: ${duration}s, Reason: $reason, Remote: $initiatedByRemote")
                showToast("WebSocket closed after ${duration}s - ${code?.name}")
            } catch (e: Exception) {
                Log.e(TAG, "onClose error", e)
            }
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            try {
                val text = message.textPayload ?: return
                val json = try { JSONObject(text) } catch (e: Exception) { return }
                val type = json.optString("type", "")
                
                when (type) {
                    "offer" -> {
                        Log.d(TAG, "Offer received")
                        mainHandler.post {
                            try { onOfferReceived?.invoke(text) }
                            catch (e: Exception) { Log.e(TAG, "offer error", e) }
                        }
                    }
                    "answer" -> {
                        Log.d(TAG, "Answer received")
                        mainHandler.post {
                            try { onAnswerReceived?.invoke(text) }
                            catch (e: Exception) { Log.e(TAG, "answer error", e) }
                        }
                    }
                    "ice-candidate" -> {
                        Log.d(TAG, "ICE candidate received")
                        mainHandler.post {
                            try { onIceCandidateReceived?.invoke(text) }
                            catch (e: Exception) { Log.e(TAG, "ice error", e) }
                        }
                    }
                    "client-connected" -> Log.d(TAG, "Client connected msg")
                    "pong" -> {
                        lastPongTime = System.currentTimeMillis()
                        Log.v(TAG, "Pong received from client")
                    }
                    "stop" -> Log.d(TAG, "Stop requested")
                    else -> Log.d(TAG, "Unknown type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMessage error", e)
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {
            lastPongTime = System.currentTimeMillis()
            Log.v(TAG, "WebSocket pong frame received")
        }

        override fun onException(exception: IOException?) {
            val duration = (System.currentTimeMillis() - connectionTime) / 1000
            if (exception is SocketTimeoutException) {
                Log.w(TAG, "Socket timeout after ${duration}s (should not happen)")
            } else {
                Log.e(TAG, "Exception after ${duration}s: ${exception?.message}")
            }
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        return WebSocketClient(handshake)
    }

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", loadHtmlPage())
    }

    fun broadcast(message: String) {
        synchronized(clients) {
            val dead = mutableListOf<WebSocketClient>()
            clients.forEach { c ->
                try { if (c.isOpen) c.send(message) else dead.add(c) }
                catch (e: Exception) { dead.add(c) }
            }
            clients.removeAll(dead.toSet())
        }
    }

    override fun start() {
        super.start()
        startKeepAliveTimer()
        Log.d(TAG, "Server started on port $listeningPort with keep-alive")
    }

    override fun stop() {
        stopKeepAliveTimer()
        super.stop()
    }
}

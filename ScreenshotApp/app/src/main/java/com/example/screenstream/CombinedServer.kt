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

class CombinedServer(port: Int, private val context: Context? = null) : NanoWSD(port) {

    private val TAG = "CombinedServer"
    private val clients = mutableListOf<WebSocketClient>()

    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    private fun showToast(message: String) {
        context?.let {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(it, message, Toast.LENGTH_LONG).show()
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
                Log.e(TAG, "Failed to load viewer.html from assets", e)
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
            transition: all 0.3s;
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
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
        }
        video { width: 100%; height: 100%; object-fit: contain; }
        .controls {
            display: flex;
            gap: 10px;
            justify-content: center;
            margin-top: 20px;
            flex-wrap: wrap;
        }
        button {
            padding: 12px 24px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s;
            min-width: 120px;
        }
        button.danger { background: #dc3545; color: white; }
        button.danger:hover:not(:disabled) { background: #c82333; transform: translateY(-2px); }
        button:disabled { opacity: 0.5; cursor: not-allowed; }
        .info {
            margin-top: 20px;
            padding: 15px;
            background: #f8f9fa;
            border-radius: 10px;
            font-size: 14px;
            color: #666;
        }
        .info p { margin: 5px 0; }
        .stats {
            display: flex;
            justify-content: space-around;
            margin-top: 15px;
            padding-top: 15px;
            border-top: 1px solid #eee;
        }
        .stat-item { text-align: center; }
        .stat-value { font-size: 18px; font-weight: bold; color: #333; }
        .stat-label { font-size: 12px; color: #666; text-transform: uppercase; margin-top: 4px; }
        .loading {
            display: inline-block;
            width: 20px;
            height: 20px;
            border: 3px solid rgba(0, 0, 0, 0.1);
            border-radius: 50%;
            border-top-color: #667eea;
            animation: spin 1s ease-in-out infinite;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
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
        .debug-line { margin: 2px 0; color: #333; }
        .play-overlay {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            background: rgba(0,0,0,0.5);
            cursor: pointer;
            z-index: 10;
        }
        .play-overlay.hidden { display: none; }
        .play-btn {
            width: 80px;
            height: 80px;
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
        <div id="status" class="status waiting">
            <span class="loading"></span> Connecting...
        </div>
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
            <p><strong>WebSocket:</strong> <span id="wsUrl">-</span></p>
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
                    <div class="stat-value" id="frameRate">-</div>
                    <div class="stat-label">Frame Rate</div>
                </div>
            </div>
            <div class="debug" id="debugLog"></div>
        </div>
    </div>

    <script type="text/javascript">
        var video = document.getElementById('remoteVideo');
        var status = document.getElementById('status');
        var statusText = document.getElementById('statusText');
        var stopBtn = document.getElementById('stopBtn');
        var connectionStatus = document.getElementById('connectionStatus');
        var resolution = document.getElementById('resolution');
        var frameRate = document.getElementById('frameRate');
        var wsUrlElement = document.getElementById('wsUrl');
        var debugLog = document.getElementById('debugLog');
        var playOverlay = document.getElementById('playOverlay');

        var config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };

        var ws = null;
        var pc = null;
        var pendingIceCandidates = [];
        var reconnectAttempts = 0;
        var MAX_RECONNECT_ATTEMPTS = 5;

        function debugMessage(msg) {
            console.log(msg);
            var line = document.createElement('div');
            line.className = 'debug-line';
            line.textContent = new Date().toLocaleTimeString() + ': ' + msg;
            debugLog.appendChild(line);
            debugLog.scrollTop = debugLog.scrollHeight;
        }

        function updateStatus(msg, statusType) {
            statusType = statusType || 'waiting';
            status.innerHTML = msg;
            status.className = 'status ' + statusType;
            statusText.textContent = msg.replace(/<[^>]*>/g, '');
        }

        video.onloadedmetadata = function() {
            debugMessage('Video metadata: ' + video.videoWidth + 'x' + video.videoHeight);
        };

        video.onplaying = function() {
            debugMessage('Video is playing');
            playOverlay.classList.add('hidden');
            updateStatus('Streaming Active!', 'streaming');
        };

        video.onpause = function() {
            debugMessage('Video paused');
        };

        video.onerror = function(e) {
            debugMessage('Video error: ' + (video.error ? video.error.message : 'unknown'));
        };

        video.onstalled = function() {
            debugMessage('Video stalled');
        };

        video.onwaiting = function() {
            debugMessage('Video waiting for data');
        };

        playOverlay.onclick = function() {
            video.play().then(function() {
                debugMessage('Manual play started');
                playOverlay.classList.add('hidden');
            }).catch(function(e) {
                debugMessage('Manual play failed: ' + e.message);
            });
        };

        video.onclick = function() {
            if (video.paused && video.srcObject) {
                video.play().catch(function(e) {
                    debugMessage('Play on click failed: ' + e.message);
                });
            }
        };

        function tryPlayVideo() {
            if (!video.srcObject) return;
            
            var playPromise = video.play();
            if (playPromise !== undefined) {
                playPromise.then(function() {
                    debugMessage('Video autoplay started');
                    playOverlay.classList.add('hidden');
                    updateStatus('Streaming Active!', 'streaming');
                }).catch(function(error) {
                    debugMessage('Autoplay blocked: ' + error.message);
                    playOverlay.classList.remove('hidden');
                    updateStatus('Tap to play video', 'connected');
                });
            }
        }

        function connectWebSocket() {
            var host = window.location.hostname;
            var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            var port = window.location.port || (window.location.protocol === 'https:' ? '443' : '80');
            var wsUrl = protocol + '//' + host + ':' + port;

            wsUrlElement.textContent = wsUrl;
            debugMessage('Connecting to: ' + wsUrl);

            try {
                ws = new WebSocket(wsUrl);

                ws.onopen = function() {
                    debugMessage('WebSocket connected');
                    reconnectAttempts = 0;
                    pendingIceCandidates = [];
                    connectionStatus.textContent = 'Connected';
                    ws.send(JSON.stringify({ type: 'client-connected' }));
                };

                ws.onmessage = function(event) {
                    try {
                        var data = JSON.parse(event.data);
                        debugMessage('Received: ' + data.type);
                        handleMessage(data);
                    } catch (error) {
                        debugMessage('Parse error: ' + error.message);
                    }
                };

                ws.onerror = function(error) {
                    debugMessage('WebSocket error');
                    updateStatus('Connection error', 'error');
                    connectionStatus.textContent = 'Error';
                };

                ws.onclose = function(event) {
                    var closeReason = getCloseReason(event.code);
                    debugMessage('WebSocket closed: Code=' + event.code + ', Reason=' + closeReason);
                    
                    if (!event.wasClean && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++;
                        updateStatus('Reconnecting (' + reconnectAttempts + '/' + MAX_RECONNECT_ATTEMPTS + ')...', 'waiting');
                        setTimeout(connectWebSocket, 2000);
                        return;
                    }
                    
                    updateStatus('Disconnected: ' + closeReason, 'error');
                    connectionStatus.textContent = 'Disconnected';
                    cleanup();
                };
            } catch (error) {
                debugMessage('Connection failed: ' + error.message);
                updateStatus('Failed to connect: ' + error.message, 'error');
            }
        }

        function getCloseReason(code) {
            var reasons = {
                1000: 'Normal closure',
                1001: 'Going away',
                1002: 'Protocol error',
                1003: 'Unsupported data',
                1005: 'No status received',
                1006: 'Abnormal closure - Connection lost unexpectedly',
                1007: 'Invalid frame payload',
                1008: 'Policy violation',
                1009: 'Message too big',
                1010: 'Missing extension',
                1011: 'Internal server error',
                1012: 'Service restart',
                1013: 'Try again later',
                1014: 'Bad gateway',
                1015: 'TLS handshake failure'
            };
            return reasons[code] || 'Unknown error (code: ' + code + ')';
        }

        function handleMessage(data) {
            switch (data.type) {
                case 'offer':
                    handleOffer(data);
                    break;
                case 'ice-candidate':
                    handleIceCandidate(data);
                    break;
            }
        }

        function handleOffer(data) {
            debugMessage('Processing offer...');

            pc = new RTCPeerConnection(config);

            pc.onicecandidate = function(event) {
                if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        type: 'ice-candidate',
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    }));
                    debugMessage('Sent ICE candidate');
                }
            };

            pc.ontrack = function(event) {
                debugMessage('Got track: ' + event.track.kind);
                
                if (event.streams && event.streams[0]) {
                    video.srcObject = event.streams[0];
                    debugMessage('Stream attached to video');
                } else if (event.track) {
                    var stream = new MediaStream();
                    stream.addTrack(event.track);
                    video.srcObject = stream;
                    debugMessage('Track attached to new stream');
                }

                connectionStatus.textContent = 'Streaming';
                stopBtn.disabled = false;

                setTimeout(tryPlayVideo, 100);

                if (event.track.kind === 'video') {
                    event.track.onended = function() {
                        debugMessage('Video track ended');
                    };
                    
                    event.track.onmute = function() {
                        debugMessage('Video track muted');
                    };
                    
                    event.track.onunmute = function() {
                        debugMessage('Video track unmuted');
                        tryPlayVideo();
                    };

                    setTimeout(function() {
                        var settings = event.track.getSettings();
                        if (settings.width && settings.height) {
                            resolution.textContent = settings.width + 'x' + settings.height;
                            debugMessage('Resolution: ' + settings.width + 'x' + settings.height);
                        }
                        if (settings.frameRate) {
                            frameRate.textContent = Math.round(settings.frameRate) + ' fps';
                        }
                    }, 1000);
                }
            };

            pc.onconnectionstatechange = function() {
                debugMessage('Connection state: ' + pc.connectionState);
                switch (pc.connectionState) {
                    case 'connected':
                        connectionStatus.textContent = 'Connected';
                        setTimeout(tryPlayVideo, 500);
                        break;
                    case 'failed':
                    case 'disconnected':
                        connectionStatus.textContent = 'Failed';
                        updateStatus('Connection lost', 'error');
                        break;
                }
            };

            pc.oniceconnectionstatechange = function() {
                debugMessage('ICE state: ' + pc.iceConnectionState);
            };

            pc.setRemoteDescription(new RTCSessionDescription({
                type: 'offer',
                sdp: data.sdp
            })).then(function() {
                debugMessage('Remote description set');

                if (pendingIceCandidates.length > 0) {
                    debugMessage('Processing ' + pendingIceCandidates.length + ' queued candidates');
                    var promises = pendingIceCandidates.map(function(c) {
                        return pc.addIceCandidate(new RTCIceCandidate({
                            sdpMid: c.sdpMid,
                            sdpMLineIndex: c.sdpMLineIndex,
                            candidate: c.candidate
                        })).catch(function(e) {
                            debugMessage('Queued ICE error: ' + e.message);
                        });
                    });
                    pendingIceCandidates = [];
                    return Promise.all(promises);
                }
            }).then(function() {
                return pc.createAnswer();
            }).then(function(answer) {
                debugMessage('Answer created');
                return pc.setLocalDescription(answer);
            }).then(function() {
                debugMessage('Local description set');
                ws.send(JSON.stringify({
                    type: 'answer',
                    sdp: pc.localDescription.sdp
                }));
                debugMessage('Answer sent');
                updateStatus('Setting up stream...', 'waiting');
            }).catch(function(error) {
                debugMessage('Offer handling error: ' + error.message);
                updateStatus('Setup failed: ' + error.message, 'error');
            });
        }

        function handleIceCandidate(data) {
            if (!data.candidate) return;

            if (!pc || !pc.remoteDescription) {
                debugMessage('Queuing ICE candidate');
                pendingIceCandidates.push(data);
                return;
            }

            pc.addIceCandidate(new RTCIceCandidate({
                sdpMid: data.sdpMid,
                sdpMLineIndex: data.sdpMLineIndex,
                candidate: data.candidate
            })).then(function() {
                debugMessage('Added ICE candidate');
            }).catch(function(error) {
                debugMessage('ICE error: ' + error.message);
            });
        }

        function stopStream() {
            cleanup();
            updateStatus('Stream stopped', 'waiting');
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'stop' }));
            }
        }

        function cleanup() {
            if (pc) {
                pc.close();
                pc = null;
            }
            if (video.srcObject) {
                var tracks = video.srcObject.getTracks();
                tracks.forEach(function(track) { track.stop(); });
                video.srcObject = null;
            }
            pendingIceCandidates = [];
            stopBtn.disabled = true;
            resolution.textContent = '-';
            frameRate.textContent = '-';
            playOverlay.classList.add('hidden');
        }

        stopBtn.onclick = stopStream;

        window.onload = function() {
            debugMessage('Page loaded');
            connectWebSocket();
        };

        window.onbeforeunload = function() {
            cleanup();
            if (ws) ws.close();
        };
    </script>
</body>
</html>
        """.trimIndent()
    }

    inner class WebSocketClient(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        
        private var connectionTime: Long = 0
        
        override fun onOpen() {
            try {
                synchronized(clients) {
                    clients.add(this)
                }
                connectionTime = System.currentTimeMillis()
                Log.d(TAG, "WebSocket client connected. Total: ${clients.size}")
                
                // CRITICAL FIX: Safely invoke callback with null check
                try {
                    onClientConnected?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onClientConnected callback", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Exception in onOpen()", e)
                showToast("Connection failed: ${e.message}")
                // Don't rethrow - handle gracefully
            }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            try {
                synchronized(clients) {
                    clients.remove(this)
                }
                
                val connectionDuration = System.currentTimeMillis() - connectionTime
                val durationSec = connectionDuration / 1000
                val wasActive = connectionDuration > 1000
                
                val closeReason = reason ?: "No reason provided"
                val closeCodeName = code?.name ?: "NULL"
                
                val closeCodeValue = when (code) {
                    NanoWSD.WebSocketFrame.CloseCode.NormalClosure -> 1000
                    NanoWSD.WebSocketFrame.CloseCode.GoingAway -> 1001
                    NanoWSD.WebSocketFrame.CloseCode.ProtocolError -> 1002
                    NanoWSD.WebSocketFrame.CloseCode.UnsupportedData -> 1003
                    NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure -> 1006
                    NanoWSD.WebSocketFrame.CloseCode.InvalidFramePayloadData -> 1007
                    NanoWSD.WebSocketFrame.CloseCode.PolicyViolation -> 1008
                    NanoWSD.WebSocketFrame.CloseCode.MessageTooBig -> 1009
                    NanoWSD.WebSocketFrame.CloseCode.InternalServerError -> 1011
                    else -> -1
                }
                
                Log.w(TAG, """
                    ═══════════════════════════════════════════════════
                    WebSocket CLOSED - Detailed Analysis
                    ═══════════════════════════════════════════════════
                    Close Code: $closeCodeValue ($closeCodeName)
                    Reason: "$closeReason"
                    Initiated By: ${if (initiatedByRemote) "REMOTE (Client/Cloudflare)" else "LOCAL (Server)"}
                    Connection Duration: ${durationSec}s (${if (wasActive) "Active" else "Brief"})
                    Was Active: $wasActive
                    Remaining Clients: ${clients.size}
                    ═══════════════════════════════════════════════════
                """.trimIndent())
                
                if (closeCodeValue == 1011) {
                    val detailedError = buildString {
                        append("🔴 WebSocket Error 1011 - INTERNAL SERVER ERROR\n")
                        append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
                        append("Duration: ${durationSec}s\n")
                        append("Reason: $closeReason\n")
                        append("Initiated: ${if (initiatedByRemote) "Remote" else "Server"}\n")
                        append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
                        append("CAUSE: Handler terminated without proper closure\n")
                        append("This means:\n")
                        append("• Message handler crashed unexpectedly\n")
                        append("• Exception in onMessage() or onOpen()\n")
                        append("• Server thread died prematurely\n")
                        append("• Critical: Check for null pointer exceptions\n")
                        append("• Critical: Check for unhandled exceptions in handlers\n")
                        append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
                        append("ACTION: Check logcat for exceptions immediately before this")
                    }
                    
                    Log.e(TAG, detailedError)
                    showToast(detailedError)
                    return
                }
                
                when (closeCodeValue) {
                    1006 -> {
                        val msg = buildString {
                            append("⚠️ WebSocket 1006 - ABNORMAL CLOSURE\n")
                            append("Duration: ${durationSec}s\n")
                            append("Reason: $closeReason\n")
                            if (!wasActive) {
                                append("CAUSE: Connection failed immediately\n")
                                append("• Cloudflare tunnel may be down\n")
                                append("• Port 8080 not reachable\n")
                                append("• Server not responding to Cloudflare\n")
                            } else {
                                append("CAUSE: Network interruption\n")
                                append("• Connection lost unexpectedly\n")
                                append("• Tunnel disconnected mid-stream\n")
                                append("• Client network dropped\n")
                            }
                        }
                        Log.w(TAG, msg)
                        showToast(msg)
                    }
                    
                    1002 -> {
                        val msg = "⚠️ WebSocket 1002 - PROTOCOL ERROR\n" +
                                "Reason: $closeReason\n" +
                                "CAUSE: Invalid WebSocket frame received\n" +
                                "• Malformed message from client\n" +
                                "• Protocol mismatch"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                    
                    1003 -> {
                        val msg = "⚠️ WebSocket 1003 - UNSUPPORTED DATA\n" +
                                "Reason: $closeReason\n" +
                                "CAUSE: Received unsupported data type\n" +
                                "• Binary data sent when text expected\n" +
                                "• Wrong message format"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                    
                    1007 -> {
                        val msg = "⚠️ WebSocket 1007 - INVALID PAYLOAD\n" +
                                "Reason: $closeReason\n" +
                                "CAUSE: Frame payload data was invalid\n" +
                                "• Corrupted message data\n" +
                                "• UTF-8 encoding error"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                    
                    1009 -> {
                        val msg = "⚠️ WebSocket 1009 - MESSAGE TOO BIG\n" +
                                "Reason: $closeReason\n" +
                                "CAUSE: Message exceeded size limit\n" +
                                "• SDP or ICE candidate too large\n" +
                                "• Consider message chunking"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                    
                    -1 -> {
                        val msg = "⚠️ WebSocket CLOSED - NULL CODE\n" +
                                "CodeName: $closeCodeName\n" +
                                "Reason: $closeReason\n" +
                                "Duration: ${durationSec}s\n" +
                                "CAUSE: No close code received\n" +
                                "• Abnormal termination\n" +
                                "• Handler may have crashed"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                    
                    1000, 1001 -> {
                        Log.i(TAG, "WebSocket closed normally (Code: $closeCodeValue)")
                    }
                    
                    else -> {
                        val msg = "⚠️ WebSocket $closeCodeValue - UNEXPECTED CODE\n" +
                                "Reason: $closeReason\n" +
                                "Duration: ${durationSec}s\n" +
                                "CAUSE: Rare or custom close code"
                        Log.w(TAG, msg)
                        if (wasActive) showToast(msg)
                    }
                }
                
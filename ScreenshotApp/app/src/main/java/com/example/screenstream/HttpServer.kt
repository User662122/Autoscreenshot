package com.example.screenstream

import android.util.Log
import fi.iki.elonen.NanoHTTPD

class HttpServer(port: Int) : NanoHTTPD(port) {

    private val TAG = "HttpServer"

    private val htmlPage = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android Screen Stream</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
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

        h1 {
            color: #333;
            margin-bottom: 10px;
            text-align: center;
            font-size: 28px;
        }

        .status {
            text-align: center;
            padding: 15px;
            border-radius: 10px;
            margin-bottom: 20px;
            font-weight: 600;
            transition: all 0.3s;
        }

        .status.waiting {
            background: #fff3cd;
            color: #856404;
        }

        .status.connected {
            background: #d4edda;
            color: #155724;
        }

        .status.streaming {
            background: #d1ecf1;
            color: #0c5460;
        }

        .status.error {
            background: #f8d7da;
            color: #721c24;
        }

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

        video {
            width: 100%;
            height: 100%;
            object-fit: contain;
        }

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

        button.danger {
            background: #dc3545;
            color: white;
        }

        button.danger:hover:not(:disabled) {
            background: #c82333;
            transform: translateY(-2px);
        }

        button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }

        .info {
            margin-top: 20px;
            padding: 15px;
            background: #f8f9fa;
            border-radius: 10px;
            font-size: 14px;
            color: #666;
        }

        .info p {
            margin: 5px 0;
        }

        .stats {
            display: flex;
            justify-content: space-around;
            margin-top: 15px;
            padding-top: 15px;
            border-top: 1px solid #eee;
        }

        .stat-item {
            text-align: center;
        }

        .stat-value {
            font-size: 18px;
            font-weight: bold;
            color: #333;
        }

        .stat-label {
            font-size: 12px;
            color: #666;
            text-transform: uppercase;
            margin-top: 4px;
        }

        .loading {
            display: inline-block;
            width: 20px;
            height: 20px;
            border: 3px solid rgba(0, 0, 0, 0.1);
            border-radius: 50%;
            border-top-color: #667eea;
            animation: spin 1s ease-in-out infinite;
        }

        @keyframes spin {
            to { transform: rotate(360deg); }
        }

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

        .debug-line {
            margin: 2px 0;
            color: #333;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>📱 Android Screen Stream</h1>

        <div id="status" class="status waiting">
            <span class="loading"></span> Connecting...
        </div>

        <div class="video-container">
            <video id="remoteVideo" autoplay playsinline muted></video>
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

    <script>
        // DOM Elements
        const video = document.getElementById('remoteVideo');
        const status = document.getElementById('status');
        const statusText = document.getElementById('statusText');
        const stopBtn = document.getElementById('stopBtn');
        const connectionStatus = document.getElementById('connectionStatus');
        const resolution = document.getElementById('resolution');
        const frameRate = document.getElementById('frameRate');
        const wsUrlElement = document.getElementById('wsUrl');
        const debugLog = document.getElementById('debugLog');

        // WebRTC Configuration
        const config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };

        let ws = null;
        let pc = null;
        let reconnectAttempts = 0;
        const MAX_RECONNECT_ATTEMPTS = 5;

        // Debug logging
        function debugMessage(msg) {
            console.log(msg);
            const line = document.createElement('div');
            line.className = 'debug-line';
            line.textContent = new Date().toLocaleTimeString() + ': ' + msg;
            debugLog.appendChild(line);
            debugLog.scrollTop = debugLog.scrollHeight;
        }

        // Update status display
        function updateStatus(msg, statusType) {
            statusType = statusType || 'waiting';
            status.innerHTML = msg;
            status.className = 'status ' + statusType;
            statusText.textContent = msg.replace(/<[^>]*>/g, '');
        }

        // Initialize WebSocket connection
        function connectWebSocket() {
            const host = window.location.hostname;
            const wsPort = '8081'; // WebSocket on separate port
            const wsUrl = 'ws://' + host + ':' + wsPort;
            
            wsUrlElement.textContent = wsUrl;
            debugMessage('Attempting WebSocket connection to: ' + wsUrl);
            
            try {
                ws = new WebSocket(wsUrl);
                
                ws.onopen = function() {
                    debugMessage('✓ WebSocket connected successfully');
                    reconnectAttempts = 0;
                    updateStatus('✓ Connected - Waiting for stream...', 'connected');
                    connectionStatus.textContent = 'Connected';
                    
                    // Send connection message
                    ws.send(JSON.stringify({ type: 'client-connected' }));
                    debugMessage('Sent client-connected message');
                };
                
                ws.onmessage = async function(event) {
                    try {
                        const data = JSON.parse(event.data);
                        debugMessage('Received: ' + data.type);
                        await handleMessage(data);
                    } catch (error) {
                        debugMessage('Error parsing message: ' + error.message);
                        console.error('Message error:', error);
                    }
                };
                
                ws.onerror = function(error) {
                    debugMessage('WebSocket error occurred');
                    console.error('WebSocket error:', error);
                    updateStatus('Connection error - Check Android app', 'error');
                    connectionStatus.textContent = 'Error';
                };
                
                ws.onclose = function(event) {
                    debugMessage('WebSocket closed. Code: ' + event.code + ', Clean: ' + event.wasClean);
                    
                    if (event.wasClean) {
                        updateStatus('Disconnected', 'waiting');
                    } else {
                        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                            reconnectAttempts++;
                            updateStatus('Reconnecting... (' + reconnectAttempts + '/' + MAX_RECONNECT_ATTEMPTS + ')', 'waiting');
                            setTimeout(connectWebSocket, 2000);
                            return;
                        }
                        updateStatus('Connection lost - Refresh to retry', 'error');
                    }
                    
                    connectionStatus.textContent = 'Disconnected';
                    cleanup();
                };
            } catch (error) {
                debugMessage('Failed to create WebSocket: ' + error.message);
                updateStatus('Failed to connect - Check network', 'error');
            }
        }

        // Handle signaling messages
        async function handleMessage(data) {
            switch (data.type) {
                case 'offer':
                    await handleOffer(data);
                    break;
                case 'ice-candidate':
                    await handleIceCandidate(data);
                    break;
            }
        }

        // Handle incoming offer
        async function handleOffer(data) {
            debugMessage('Processing offer from Android');
            
            // Create peer connection
            pc = new RTCPeerConnection(config);
            
            // Handle ICE candidates
            pc.onicecandidate = function(event) {
                if (event.candidate && ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        type: 'ice-candidate',
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    }));
                    debugMessage('Sent ICE candidate');
                }
            };
            
            // Handle incoming track
            pc.ontrack = function(event) {
                debugMessage('Received video track!');
                video.srcObject = event.streams[0];
                updateStatus('🎥 Streaming Active!', 'streaming');
                connectionStatus.textContent = 'Streaming';
                stopBtn.disabled = false;
                
                // Update video stats
                const track = event.track;
                if (track.kind === 'video') {
                    setTimeout(function() {
                        const settings = track.getSettings();
                        if (settings.width && settings.height) {
                            resolution.textContent = settings.width + '×' + settings.height;
                            debugMessage('Resolution: ' + settings.width + 'x' + settings.height);
                        }
                        if (settings.frameRate) {
                            frameRate.textContent = Math.round(settings.frameRate) + ' fps';
                            debugMessage('Frame rate: ' + settings.frameRate);
                        }
                    }, 1000);
                }
            };
            
            // Handle connection state
            pc.onconnectionstatechange = function() {
                debugMessage('WebRTC state: ' + pc.connectionState);
                switch (pc.connectionState) {
                    case 'connected':
                        connectionStatus.textContent = 'Streaming';
                        break;
                    case 'disconnected':
                    case 'failed':
                        connectionStatus.textContent = 'Failed';
                        updateStatus('Connection lost', 'error');
                        cleanup();
                        break;
                }
            };
            
            try {
                // Set remote description
                await pc.setRemoteDescription(new RTCSessionDescription({
                    type: 'offer',
                    sdp: data.sdp
                }));
                debugMessage('Remote description set');
                
                // Create answer
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                debugMessage('Local description set');
                
                // Send answer
                ws.send(JSON.stringify({
                    type: 'answer',
                    sdp: answer.sdp
                }));
                debugMessage('Answer sent to Android');
                
                updateStatus('Setting up stream...', 'waiting');
            } catch (error) {
                debugMessage('Error in offer handling: ' + error.message);
                console.error('Error handling offer:', error);
                updateStatus('Failed to set up stream', 'error');
            }
        }

        // Handle ICE candidate
        async function handleIceCandidate(data) {
            if (pc && data.candidate) {
                try {
                    await pc.addIceCandidate(new RTCIceCandidate({
                        sdpMid: data.sdpMid,
                        sdpMLineIndex: data.sdpMLineIndex,
                        candidate: data.candidate
                    }));
                    debugMessage('Added ICE candidate');
                } catch (error) {
                    debugMessage('Error adding ICE: ' + error.message);
                    console.error('Error adding ICE candidate:', error);
                }
            }
        }

        // Stop streaming
        function stopStream() {
            debugMessage('Stopping stream');
            cleanup();
            updateStatus('Stream stopped', 'waiting');
            
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'stop' }));
            }
        }

        // Cleanup resources
        function cleanup() {
            if (pc) {
                pc.close();
                pc = null;
                debugMessage('Peer connection closed');
            }
            
            if (video.srcObject) {
                video.srcObject.getTracks().forEach(function(track) {
                    track.stop();
                });
                video.srcObject = null;
            }
            
            stopBtn.disabled = true;
            resolution.textContent = '-';
            frameRate.textContent = '-';
        }

        // Event listeners
        stopBtn.addEventListener('click', stopStream);

        // Initialize on page load
        window.addEventListener('load', function() {
            debugMessage('Page loaded');
            debugMessage('Browser: ' + navigator.userAgent);
            setTimeout(connectWebSocket, 500);
        });

        // Cleanup on page unload
        window.addEventListener('beforeunload', function() {
            cleanup();
            if (ws) ws.close();
        });
    </script>
</body>
</html>
    """.trimIndent()

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "HTTP request: ${session.method} ${session.uri} from ${session.remoteIpAddress}")
        
        return when (session.uri) {
            "/" -> {
                Log.d(TAG, "Serving HTML page")
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage)
            }
            else -> {
                Log.d(TAG, "404 - Not found: ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }
    }

    override fun start() {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.d(TAG, "✓ HTTP server started on port $listeningPort")
    }
}
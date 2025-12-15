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
            <p><strong>Status:</strong> Waiting for stream...</p>
            <p><strong>Server:</strong> <span id="serverUrl">-</span></p>
            
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
        </div>
    </div>

    <script>
        // DOM Elements
        const video = document.getElementById('remoteVideo');
        const status = document.getElementById('status');
        const stopBtn = document.getElementById('stopBtn');
        const connectionStatus = document.getElementById('connectionStatus');
        const resolution = document.getElementById('resolution');
        const frameRate = document.getElementById('frameRate');
        const serverUrl = document.getElementById('serverUrl');

        // WebRTC Configuration
        const config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };

        let ws = null;
        let pc = null;

        // Update status display
        function updateStatus(msg, statusType) {
            statusType = statusType || 'waiting';
            status.innerHTML = msg;
            status.className = 'status ' + statusType;
        }

        // Initialize WebSocket connection
        function connectWebSocket() {
            const host = window.location.hostname;
            const port = window.location.port || '8080';
            const wsUrl = 'ws://' + host + ':' + port;
            
            serverUrl.textContent = host + ':' + port;
            console.log('Connecting to WebSocket:', wsUrl);
            
            ws = new WebSocket(wsUrl);
            
            ws.onopen = function() {
                console.log('WebSocket connected');
                updateStatus('✓ Connected - Waiting for stream...', 'connected');
                connectionStatus.textContent = 'Connected';
            };
            
            ws.onmessage = async function(event) {
                try {
                    const data = JSON.parse(event.data);
                    console.log('Received:', data.type);
                    await handleMessage(data);
                } catch (error) {
                    console.error('Message error:', error);
                }
            };
            
            ws.onerror = function(error) {
                console.error('WebSocket error:', error);
                updateStatus('Connection error - Check if Android app is running', 'error');
                connectionStatus.textContent = 'Error';
            };
            
            ws.onclose = function() {
                console.log('WebSocket closed');
                updateStatus('Disconnected from Android device', 'error');
                connectionStatus.textContent = 'Disconnected';
                cleanup();
            };
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
            console.log('Received offer, creating peer connection');
            
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
                    console.log('Sent ICE candidate');
                }
            };
            
            // Handle incoming track
            pc.ontrack = function(event) {
                console.log('Received track:', event.track.kind);
                video.srcObject = event.streams[0];
                updateStatus('🎥 Streaming Active!', 'streaming');
                connectionStatus.textContent = 'Streaming';
                stopBtn.disabled = false;
                
                // Update video stats
                const track = event.track;
                if (track.kind === 'video') {
                    track.onended = function() {
                        console.log('Track ended');
                        updateStatus('Stream ended', 'waiting');
                    };
                    
                    // Get video settings
                    setTimeout(function() {
                        const settings = track.getSettings();
                        if (settings.width && settings.height) {
                            resolution.textContent = settings.width + '×' + settings.height;
                        }
                        if (settings.frameRate) {
                            frameRate.textContent = Math.round(settings.frameRate) + ' fps';
                        }
                    }, 1000);
                }
            };
            
            // Handle connection state
            pc.onconnectionstatechange = function() {
                console.log('Connection state:', pc.connectionState);
                switch (pc.connectionState) {
                    case 'connected':
                        connectionStatus.textContent = 'Connected';
                        break;
                    case 'disconnected':
                    case 'failed':
                        connectionStatus.textContent = 'Failed';
                        updateStatus('Connection lost', 'error');
                        cleanup();
                        break;
                    case 'closed':
                        connectionStatus.textContent = 'Closed';
                        break;
                }
            };
            
            pc.oniceconnectionstatechange = function() {
                console.log('ICE connection state:', pc.iceConnectionState);
            };
            
            try {
                // Set remote description
                await pc.setRemoteDescription(new RTCSessionDescription({
                    type: 'offer',
                    sdp: data.sdp
                }));
                console.log('Remote description set');
                
                // Create answer
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                console.log('Local description set');
                
                // Send answer
                ws.send(JSON.stringify({
                    type: 'answer',
                    sdp: answer.sdp
                }));
                console.log('Answer sent');
                
                updateStatus('Setting up stream...', 'waiting');
            } catch (error) {
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
                    console.log('Added ICE candidate');
                } catch (error) {
                    console.error('Error adding ICE candidate:', error);
                }
            }
        }

        // Stop streaming
        function stopStream() {
            cleanup();
            updateStatus('Stream stopped', 'waiting');
            
            // Notify server
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'stop' }));
            }
        }

        // Cleanup resources
        function cleanup() {
            if (pc) {
                pc.close();
                pc = null;
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
            connectionStatus.textContent = ws && ws.readyState === WebSocket.OPEN ? 'Connected' : 'Disconnected';
        }

        // Event listeners
        stopBtn.addEventListener('click', stopStream);

        // Handle page visibility
        document.addEventListener('visibilitychange', function() {
            if (document.hidden) {
                console.log('Page hidden');
            } else {
                console.log('Page visible');
            }
        });

        // Initialize on page load
        window.addEventListener('load', function() {
            console.log('Page loaded, connecting...');
            connectWebSocket();
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
        Log.d(TAG, "HTTP request: ${session.method} ${session.uri}")
        
        return when (session.uri) {
            "/" -> {
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage)
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }
    }

    override fun start() {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.d(TAG, "HTTP server started on port $listeningPort")
    }
}
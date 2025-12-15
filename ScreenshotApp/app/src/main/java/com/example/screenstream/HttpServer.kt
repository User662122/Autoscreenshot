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
        const video = document.getElementById('remoteVideo');
        const status = document.getElementById('status');
        const statusText = document.getElementById('statusText');
        const stopBtn = document.getElementById('stopBtn');
        const connectionStatus = document.getElementById('connectionStatus');
        const resolution = document.getElementById('resolution');
        const frameRate = document.getElementById('frameRate');
        const wsUrlElement = document.getElementById('wsUrl');
        const debugLog = document.getElementById('debugLog');

        const config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };

        let ws = null;
        let pc = null;
        let pendingIceCandidates = [];  // FIX: Queue for early ICE candidates
        let reconnectAttempts = 0;
        const MAX_RECONNECT_ATTEMPTS = 5;

        function debugMessage(msg) {
            console.log(msg);
            const line = document.createElement('div');
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

        function connectWebSocket() {
            const host = window.location.hostname;
            const wsPort = '8081';
            const wsUrl = 'ws://' + host + ':' + wsPort;

            wsUrlElement.textContent = wsUrl;
            debugMessage('Connecting to: ' + wsUrl);

            try {
                ws = new WebSocket(wsUrl);

                ws.onopen = function() {
                    debugMessage('WebSocket connected');
                    reconnectAttempts = 0;
                    pendingIceCandidates = [];  // FIX: Clear pending candidates on new connection
                    updateStatus('Connected - Waiting for stream...', 'connected');
                    connectionStatus.textContent = 'Connected';
                    ws.send(JSON.stringify({ type: 'client-connected' }));
                };

                ws.onmessage = async function(event) {
                    try {
                        const data = JSON.parse(event.data);
                        debugMessage('Received: ' + data.type);
                        await handleMessage(data);
                    } catch (error) {
                        debugMessage('Error: ' + error.message);
                    }
                };

                ws.onerror = function(error) {
                    debugMessage('WebSocket error');
                    updateStatus('Connection error', 'error');
                    connectionStatus.textContent = 'Error';
                };

                ws.onclose = function(event) {
                    debugMessage('WebSocket closed. Code: ' + event.code);
                    if (!event.wasClean && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++;
                        updateStatus('Reconnecting... (' + reconnectAttempts + '/' + MAX_RECONNECT_ATTEMPTS + ')', 'waiting');
                        setTimeout(connectWebSocket, 2000);
                        return;
                    }
                    updateStatus('Disconnected', 'error');
                    connectionStatus.textContent = 'Disconnected';
                    cleanup();
                };
            } catch (error) {
                debugMessage('Failed to connect: ' + error.message);
                updateStatus('Failed to connect', 'error');
            }
        }

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

        async function handleOffer(data) {
            debugMessage('Processing offer...');

            pc = new RTCPeerConnection(config);

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

            pc.ontrack = function(event) {
                debugMessage('Received video track!');
                video.srcObject = event.streams[0];
                updateStatus('Streaming Active!', 'streaming');
                connectionStatus.textContent = 'Streaming';
                stopBtn.disabled = false;

                if (event.track.kind === 'video') {
                    setTimeout(function() {
                        const settings = event.track.getSettings();
                        if (settings.width && settings.height) {
                            resolution.textContent = settings.width + 'x' + settings.height;
                        }
                        if (settings.frameRate) {
                            frameRate.textContent = Math.round(settings.frameRate) + ' fps';
                        }
                    }, 1000);
                }
            };

            pc.onconnectionstatechange = function() {
                debugMessage('Connection state: ' + pc.connectionState);
                if (pc.connectionState === 'connected') {
                    connectionStatus.textContent = 'Streaming';
                } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
                    connectionStatus.textContent = 'Failed';
                    updateStatus('Connection lost', 'error');
                    cleanup();
                }
            };

            pc.oniceconnectionstatechange = function() {
                debugMessage('ICE state: ' + pc.iceConnectionState);
            };

            try {
                await pc.setRemoteDescription(new RTCSessionDescription({
                    type: 'offer',
                    sdp: data.sdp
                }));
                debugMessage('Remote description set');

                // FIX: Process any queued ICE candidates now that remote description is set
                if (pendingIceCandidates.length > 0) {
                    debugMessage('Processing ' + pendingIceCandidates.length + ' queued ICE candidates');
                    for (const candidate of pendingIceCandidates) {
                        try {
                            await pc.addIceCandidate(new RTCIceCandidate({
                                sdpMid: candidate.sdpMid,
                                sdpMLineIndex: candidate.sdpMLineIndex,
                                candidate: candidate.candidate
                            }));
                            debugMessage('Added queued ICE candidate');
                        } catch (e) {
                            debugMessage('Failed to add queued candidate: ' + e.message);
                        }
                    }
                    pendingIceCandidates = [];
                }

                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                debugMessage('Local description set');

                ws.send(JSON.stringify({
                    type: 'answer',
                    sdp: answer.sdp
                }));
                debugMessage('Answer sent');

                updateStatus('Setting up stream...', 'waiting');
            } catch (error) {
                debugMessage('Error: ' + error.message);
                updateStatus('Failed to set up stream', 'error');
            }
        }

        // FIX: Updated ICE candidate handler with queuing
        async function handleIceCandidate(data) {
            if (!data.candidate) return;

            // FIX: Queue candidates if PC not ready or remote description not set
            if (!pc || !pc.remoteDescription) {
                debugMessage('Queuing ICE candidate (PC not ready)');
                pendingIceCandidates.push(data);
                return;
            }

            try {
                await pc.addIceCandidate(new RTCIceCandidate({
                    sdpMid: data.sdpMid,
                    sdpMLineIndex: data.sdpMLineIndex,
                    candidate: data.candidate
                }));
                debugMessage('Added ICE candidate');
            } catch (error) {
                debugMessage('ICE error: ' + error.message);
            }
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
                video.srcObject.getTracks().forEach(function(track) { track.stop(); });
                video.srcObject = null;
            }
            pendingIceCandidates = [];
            stopBtn.disabled = true;
            resolution.textContent = '-';
            frameRate.textContent = '-';
        }

        stopBtn.addEventListener('click', stopStream);

        window.addEventListener('load', function() {
            debugMessage('Page loaded');
            connectWebSocket();
        });

        window.addEventListener('beforeunload', function() {
            cleanup();
            if (ws) ws.close();
        });
    </script>
</body>
</html>
    """.trimIndent()

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "HTTP request: ${session.uri}")
        return newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage)
    }
}
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
    <title>Screen Stream Viewer</title>
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
        }

        .status {
            text-align: center;
            padding: 15px;
            border-radius: 10px;
            margin-bottom: 20px;
            font-weight: 600;
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
            max-height: 80vh;
            margin: 0 auto;
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

        button.primary {
            background: #667eea;
            color: white;
        }

        button.primary:hover:not(:disabled) {
            background: #5568d3;
            transform: translateY(-2px);
        }

        button.success {
            background: #28a745;
            color: white;
        }

        button.success:hover:not(:disabled) {
            background: #218838;
            transform: translateY(-2px);
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
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>📱 Android Screen Stream Viewer</h1>

        <div id="status" class="status waiting">
            Connecting to Android device...
        </div>

        <div class="video-container">
            <video id="remoteVideo" autoplay playsinline muted></video>
        </div>

        <div class="controls">
            <button id="connectBtn" class="primary">Connect WebSocket</button>
            <button id="startStreamBtn" class="success" disabled>Start Stream</button>
            <button id="stopStreamBtn" class="danger" disabled>Stop Stream</button>
        </div>

        <div class="info">
            <p><strong>Instructions:</strong></p>
            <p>1. Click "Connect WebSocket" to establish connection</p>
            <p>2. Click "Start Stream" to begin viewing</p>
            <p>3. The stream will start automatically</p>
            
            <div class="stats">
                <div class="stat-item">
                    <div class="stat-value" id="streamStatus">Offline</div>
                    <div class="stat-label">Status</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value" id="resolution">-</div>
                    <div class="stat-label">Resolution</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value" id="fps">-</div>
                    <div class="stat-label">FPS</div>
                </div>
            </div>
        </div>
    </div>

    <script>
        const remoteVideo = document.getElementById('remoteVideo');
        const status = document.getElementById('status');
        const connectBtn = document.getElementById('connectBtn');
        const startStreamBtn = document.getElementById('startStreamBtn');
        const stopStreamBtn = document.getElementById('stopStreamBtn');
        const streamStatus = document.getElementById('streamStatus');
        const resolution = document.getElementById('resolution');
        const fps = document.getElementById('fps');

        let ws = null;
        let peerConnection = null;
        let streamActive = false;

        const rtcConfig = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };

        function updateStatus(message, type = 'waiting') {
            status.textContent = message;
            status.className = `status $${type}`;
            streamStatus.textContent = type === 'streaming' ? 'Streaming' : 'Offline';
        }

        function connectWebSocket() {
            const ip = window.location.hostname;
            const port = window.location.port || '8080';
            const wsUrl = `ws://$${ip}:$${port}`;
            
            console.log('Connecting to:', wsUrl);
            updateStatus(`Connecting to WebSocket...`, 'waiting');
            
            ws = new WebSocket(wsUrl);
            
            ws.onopen = () => {
                console.log('WebSocket connected');
                updateStatus('Connected! Ready to stream', 'connected');
                connectBtn.disabled = true;
                connectBtn.textContent = 'Connected';
                startStreamBtn.disabled = false;
            };
            
            ws.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    console.log('Received:', data.type);
                    handleSignalingMessage(data);
                } catch (error) {
                    console.error('Error:', error);
                }
            };
            
            ws.onerror = (error) => {
                console.error('WebSocket error:', error);
                updateStatus('Connection error. Check if app is running.', 'error');
            };
            
            ws.onclose = () => {
                console.log('WebSocket closed');
                updateStatus('Disconnected', 'error');
                resetConnection();
            };
        }

        function handleSignalingMessage(data) {
            switch (data.type) {
                case 'offer':
                    handleOffer(data);
                    break;
                case 'ice-candidate':
                    handleIceCandidate(data);
                    break;
            }
        }

        function createPeerConnection() {
            if (peerConnection) {
                peerConnection.close();
            }

            peerConnection = new RTCPeerConnection(rtcConfig);
            
            peerConnection.onicecandidate = (event) => {
                if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        type: 'ice-candidate',
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    }));
                }
            };
            
            peerConnection.ontrack = (event) => {
                console.log('Received track:', event.track.kind);
                remoteVideo.srcObject = event.streams[0];
                streamActive = true;
                
                startStreamBtn.disabled = true;
                stopStreamBtn.disabled = false;
                updateStatus('✓ Screen stream active!', 'streaming');
                
                const stream = event.streams[0];
                const videoTrack = stream.getVideoTracks()[0];
                if (videoTrack) {
                    const settings = videoTrack.getSettings();
                    resolution.textContent = `$${settings.width || '?'}x$${settings.height || '?'}`;
                    fps.textContent = `$${settings.frameRate || '30'} fps`;
                }
            };
            
            peerConnection.onconnectionstatechange = () => {
                console.log('Connection state:', peerConnection.connectionState);
                if (peerConnection.connectionState === 'failed') {
                    updateStatus('Connection failed', 'error');
                    stopStream();
                }
            };
        }

        async function handleOffer(offerData) {
            console.log('Handling offer');
            
            if (!peerConnection) {
                createPeerConnection();
            }
            
            try {
                await peerConnection.setRemoteDescription(
                    new RTCSessionDescription({
                        type: 'offer',
                        sdp: offerData.sdp
                    })
                );
                
                const answer = await peerConnection.createAnswer();
                await peerConnection.setLocalDescription(answer);
                
                ws.send(JSON.stringify({
                    type: 'answer',
                    sdp: answer.sdp
                }));
                
                console.log('Answer sent');
            } catch (error) {
                console.error('Error handling offer:', error);
                updateStatus('Error setting up stream', 'error');
            }
        }

        async function handleIceCandidate(candidateData) {
            if (peerConnection && candidateData.candidate) {
                try {
                    await peerConnection.addIceCandidate(
                        new RTCIceCandidate({
                            sdpMid: candidateData.sdpMid,
                            sdpMLineIndex: candidateData.sdpMLineIndex,
                            candidate: candidateData.candidate
                        })
                    );
                } catch (error) {
                    console.error('Error adding ICE candidate:', error);
                }
            }
        }

        function startStream() {
            if (!ws || ws.readyState !== WebSocket.OPEN) {
                updateStatus('Not connected', 'error');
                return;
            }
            
            updateStatus('Requesting stream...', 'waiting');
            ws.send(JSON.stringify({ type: 'request-stream' }));
        }

        function stopStream() {
            if (peerConnection) {
                peerConnection.close();
                peerConnection = null;
            }
            
            if (remoteVideo.srcObject) {
                remoteVideo.srcObject.getTracks().forEach(track => track.stop());
                remoteVideo.srcObject = null;
            }
            
            streamActive = false;
            startStreamBtn.disabled = false;
            stopStreamBtn.disabled = true;
            resolution.textContent = '-';
            fps.textContent = '-';
            updateStatus('Stream stopped', 'waiting');
        }

        function resetConnection() {
            stopStream();
            
            if (ws) {
                ws.close();
                ws = null;
            }
            
            connectBtn.disabled = false;
            connectBtn.textContent = 'Connect WebSocket';
            startStreamBtn.disabled = true;
            stopStreamBtn.disabled = true;
            streamStatus.textContent = 'Offline';
        }

        connectBtn.addEventListener('click', connectWebSocket);
        startStreamBtn.addEventListener('click', startStream);
        stopStreamBtn.addEventListener('click', stopStream);

        // Auto-connect on page load
        setTimeout(() => {
            connectBtn.click();
        }, 500);

        console.log('Screen Stream Viewer ready');
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
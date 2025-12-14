package com.example.screenstream

import android.content.Context
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class WebSocketSignalingServer(
    port: Int,
    private val context: Context
) : WebSocketServer(InetSocketAddress(port)) {
    
    private val TAG = "WebSocketServer"
    private val clients = mutableSetOf<WebSocket>()
    
    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            clients.add(it)
            Log.d(TAG, "Client connected: ${it.remoteSocketAddress}")
            
            // Send HTML page if requested
            if (handshake?.resourceDescriptor == "/" || handshake?.resourceDescriptor == "/index.html") {
                sendHtmlPage(it)
            } else {
                onClientConnected?.invoke()
            }
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            clients.remove(it)
            Log.d(TAG, "Client disconnected: ${it.remoteSocketAddress}")
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message?.let {
            try {
                val json = JSONObject(it)
                val type = json.getString("type")
                
                Log.d(TAG, "Received message type: $type")
                
                when (type) {
                    "offer" -> onOfferReceived?.invoke(it)
                    "answer" -> onAnswerReceived?.invoke(it)
                    "ice-candidate" -> onIceCandidateReceived?.invoke(it)
                    else -> {
                        // Handle unknown type or do nothing
                        Log.d(TAG, "Unknown message type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // Not used for signaling
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on port ${address.port}")
        connectionLostTimeout = 100
    }

    override fun broadcast(message: String) {
        clients.forEach { client ->
            try {
                client.send(message)
                Log.d(TAG, "Broadcast message to ${client.remoteSocketAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting to client", e)
            }
        }
    }

    private fun sendHtmlPage(conn: WebSocket) {
        try {
            val htmlContent = getHtmlContent()
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=UTF-8\r\n")
                append("Content-Length: ${htmlContent.length}\r\n")
                append("\r\n")
                append(htmlContent)
            }
            conn.send(response)
            Log.d(TAG, "Sent HTML page to client")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTML page", e)
        }
    }

    private fun getHtmlContent(): String {
        return """
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
        }
        
        button {
            padding: 12px 24px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s;
        }
        
        button.primary {
            background: #667eea;
            color: white;
        }
        
        button.primary:hover {
            background: #5568d3;
            transform: translateY(-2px);
        }
        
        button.danger {
            background: #dc3545;
            color: white;
        }
        
        button.danger:hover {
            background: #c82333;
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
    </style>
</head>
<body>
    <div class="container">
        <h1>📱 Screen Stream Viewer</h1>
        
        <div id="status" class="status waiting">
            Connecting...
        </div>
        
        <div class="video-container">
            <video id="remoteVideo" autoplay playsinline muted></video>
        </div>
        
        <div class="controls">
            <button id="connectBtn" class="primary">Connect</button>
            <button id="disconnectBtn" class="danger" disabled>Disconnect</button>
        </div>
        
        <div class="info">
            <p><strong>Built-in WebRTC Server</strong></p>
            <p>✓ No external server needed</p>
            <p>✓ Direct device-to-browser connection</p>
            <p id="streamInfo"></p>
        </div>
    </div>

    <script>
        let ws = null;
        let peerConnection = null;
        const remoteVideo = document.getElementById('remoteVideo');
        const status = document.getElementById('status');
        const connectBtn = document.getElementById('connectBtn');
        const disconnectBtn = document.getElementById('disconnectBtn');
        const streamInfo = document.getElementById('streamInfo');
        
        const config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' }
            ]
        };
        
        function updateStatus(message, type = 'waiting') {
            status.textContent = message;
            status.className = `status ${type}`;
        }
        
        function connectWebSocket() {
            const wsUrl = 'ws://' + window.location.host;
            ws = new WebSocket(wsUrl);
            
            ws.onopen = () => {
                console.log('WebSocket connected');
                updateStatus('Connected! Waiting for stream...', 'waiting');
            };
            
            ws.onmessage = async (event) => {
                try {
                    const data = JSON.parse(event.data);
                    console.log('Received message:', data.type);
                    
                    if (data.type === 'offer') {
                        await handleOffer(data);
                    } else if (data.type === 'ice-candidate') {
                        await handleIceCandidate(data);
                    }
                } catch (e) {
                    console.error('Error handling message:', e);
                }
            };
            
            ws.onerror = (error) => {
                console.error('WebSocket error:', error);
                updateStatus('Connection error', 'error');
            };
            
            ws.onclose = () => {
                console.log('WebSocket disconnected');
                updateStatus('Disconnected', 'error');
                setTimeout(() => {
                    if (ws && ws.readyState === WebSocket.CLOSED) {
                        connectWebSocket();
                    }
                }, 3000);
            };
        }
        
        function createPeerConnection() {
            peerConnection = new RTCPeerConnection(config);
            
            peerConnection.onicecandidate = (event) => {
                if (event.candidate && ws && ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({
                        type: 'ice-candidate',
                        sdpMid: event.candidate.sdpMid,
                        sdpMLineIndex: event.candidate.sdpMLineIndex,
                        candidate: event.candidate.candidate
                    }));
                    console.log('ICE candidate sent');
                }
            };
            
            peerConnection.ontrack = (event) => {
                console.log('Remote track received:', event.track.kind);
                remoteVideo.srcObject = event.streams[0];
                updateStatus('✓ Stream connected!', 'connected');
                
                const stream = event.streams[0];
                const videoTrack = stream.getVideoTracks()[0];
                if (videoTrack) {
                    const videoSettings = videoTrack.getSettings();
                    streamInfo.innerHTML = `<strong>Stream:</strong> ${videoSettings.width}x${videoSettings.height} @ ${videoSettings.frameRate}fps`;
                }
            };
            
            peerConnection.onconnectionstatechange = () => {
                console.log('Connection state:', peerConnection.connectionState);
                if (peerConnection.connectionState === 'failed') {
                    updateStatus('Connection failed. Reconnecting...', 'error');
                    setTimeout(() => location.reload(), 2000);
                }
            };
        }
        
        async function handleOffer(data) {
            console.log('Received offer');
            updateStatus('Stream found! Connecting...', 'waiting');
            
            createPeerConnection();
            
            await peerConnection.setRemoteDescription(new RTCSessionDescription({
                type: 'offer',
                sdp: data.sdp
            }));
            
            const answer = await peerConnection.createAnswer();
            await peerConnection.setLocalDescription(answer);
            
            ws.send(JSON.stringify({
                type: 'answer',
                sdp: answer.sdp
            }));
            
            console.log('Answer sent');
        }
        
        async function handleIceCandidate(data) {
            if (peerConnection) {
                await peerConnection.addIceCandidate(new RTCIceCandidate({
                    sdpMid: data.sdpMid,
                    sdpMLineIndex: data.sdpMLineIndex,
                    candidate: data.candidate
                }));
                console.log('ICE candidate added');
            }
        }
        
        connectBtn.onclick = () => {
            connectWebSocket();
            connectBtn.disabled = true;
            disconnectBtn.disabled = false;
        };
        
        disconnectBtn.onclick = () => {
            if (peerConnection) {
                peerConnection.close();
                peerConnection = null;
            }
            if (ws) {
                ws.close();
                ws = null;
            }
            remoteVideo.srcObject = null;
            updateStatus('Disconnected', 'waiting');
            connectBtn.disabled = false;
            disconnectBtn.disabled = true;
            streamInfo.innerHTML = '';
        };
        
        // Auto-connect on page load
        window.onload = () => {
            connectBtn.click();
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}

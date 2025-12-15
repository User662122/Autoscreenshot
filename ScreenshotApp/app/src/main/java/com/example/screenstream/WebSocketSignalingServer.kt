package com.example.screenstream

import android.content.Context
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class WebSocketSignalingServer(
    port: Int,
    private val context: Context
) : WebSocketServer(InetSocketAddress(port)) {

    private val TAG = "WebSocketServer"
    private val clients = mutableSetOf<WebSocket>()
    
    // HTML page content
    private val htmlPage = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android Screen Stream Viewer</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #333; }
        .status { padding: 15px; border-radius: 5px; margin: 20px 0; font-weight: bold; }
        .waiting { background: #fff3cd; color: #856404; }
        .connected { background: #d4edda; color: #155724; }
        .error { background: #f8d7da; color: #721c24; }
        video { width: 100%; max-height: 70vh; border: 1px solid #ddd; border-radius: 5px; }
        button { padding: 10px 20px; margin: 5px; border: none; border-radius: 5px; cursor: pointer; }
        .connect { background: #007bff; color: white; }
        .start { background: #28a745; color: white; }
        .stop { background: #dc3545; color: white; }
    </style>
</head>
<body>
    <div class="container">
        <h1>📱 Android Screen Stream Viewer</h1>
        
        <div id="status" class="status waiting">
            Connect to start streaming
        </div>
        
        <div class="controls">
            <button id="connectBtn" class="connect">Connect WebSocket</button>
            <button id="startBtn" class="start" disabled>Start Stream</button>
            <button id="stopBtn" class="stop" disabled>Stop Stream</button>
        </div>
        
        <video id="remoteVideo" autoplay playsinline></video>
        
        <div id="info" style="margin-top: 20px; color: #666;">
            <p>Connect to WebSocket to receive screen stream from Android device.</p>
        </div>
    </div>
    
    <script>
        let ws = null;
        let peerConnection = null;
        const config = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
        
        const status = document.getElementById('status');
        const video = document.getElementById('remoteVideo');
        const connectBtn = document.getElementById('connectBtn');
        const startBtn = document.getElementById('startBtn');
        const stopBtn = document.getElementById('stopBtn');
        
        function updateStatus(msg, type = 'waiting') {
            status.textContent = msg;
            status.className = 'status ' + type;
        }
        
        connectBtn.onclick = function() {
            const ip = window.location.hostname;
            ws = new WebSocket('ws://' + ip + ':8080');
            
            ws.onopen = function() {
                updateStatus('WebSocket Connected', 'connected');
                connectBtn.disabled = true;
                startBtn.disabled = false;
            };
            
            ws.onmessage = async function(event) {
                try {
                    const data = JSON.parse(event.data);
                    console.log('Received:', data.type);
                    
                    if (data.type === 'offer') {
                        if (!peerConnection) {
                            peerConnection = new RTCPeerConnection(config);
                            peerConnection.ontrack = function(e) {
                                video.srcObject = e.streams[0];
                                updateStatus('Streaming Active', 'connected');
                                stopBtn.disabled = false;
                            };
                            peerConnection.onicecandidate = function(e) {
                                if (e.candidate && ws.readyState === WebSocket.OPEN) {
                                    ws.send(JSON.stringify({
                                        type: 'ice-candidate',
                                        sdpMid: e.candidate.sdpMid,
                                        sdpMLineIndex: e.candidate.sdpMLineIndex,
                                        candidate: e.candidate.candidate
                                    }));
                                }
                            };
                        }
                        
                        await peerConnection.setRemoteDescription(
                            new RTCSessionDescription({ type: 'offer', sdp: data.sdp })
                        );
                        
                        const answer = await peerConnection.createAnswer();
                        await peerConnection.setLocalDescription(answer);
                        
                        ws.send(JSON.stringify({
                            type: 'answer',
                            sdp: answer.sdp
                        }));
                    }
                    else if (data.type === 'ice-candidate') {
                        if (peerConnection && data.candidate) {
                            await peerConnection.addIceCandidate(
                                new RTCIceCandidate({
                                    sdpMid: data.sdpMid,
                                    sdpMLineIndex: data.sdpMLineIndex,
                                    candidate: data.candidate
                                })
                            );
                        }
                    }
                } catch (error) {
                    console.error('Error:', error);
                }
            };
            
            ws.onerror = function(error) {
                updateStatus('Connection Error', 'error');
                console.error('WebSocket error:', error);
            };
            
            ws.onclose = function() {
                updateStatus('Disconnected', 'waiting');
                connectBtn.disabled = false;
                startBtn.disabled = true;
                stopBtn.disabled = true;
                if (peerConnection) {
                    peerConnection.close();
                    peerConnection = null;
                }
            };
        };
        
        startBtn.onclick = function() {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: 'request-stream' }));
            }
        };
        
        stopBtn.onclick = function() {
            if (peerConnection) {
                peerConnection.close();
                peerConnection = null;
            }
            video.srcObject = null;
            stopBtn.disabled = true;
            startBtn.disabled = false;
            updateStatus('Stream Stopped', 'waiting');
        };
        
        // Auto-connect
        setTimeout(function() {
            if (window.location.hostname !== 'localhost') {
                connectBtn.click();
            }
        }, 1000);
    </script>
</body>
</html>
    """.trimIndent()

    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // Check if it's a WebSocket upgrade request
        if (handshake.getFieldValue("Upgrade")?.lowercase() == "websocket") {
            clients.add(conn)
            Log.d(TAG, "WebSocket client connected: ${conn.remoteSocketAddress}")
            onClientConnected?.invoke()
        } else {
            // It's an HTTP request - send HTML page
            try {
                val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html\r\n" +
                        "Content-Length: ${htmlPage.length}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        htmlPage
                
                conn.send(response)
                conn.close()
                Log.d(TAG, "Served HTML page to HTTP client")
            } catch (e: Exception) {
                Log.e(TAG, "Error serving HTML", e)
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "offer" -> onOfferReceived?.invoke(message)
                "answer" -> onAnswerReceived?.invoke(message)
                "ice-candidate" -> onIceCandidateReceived?.invoke(message)
                else -> Log.d(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "Server started on port ${address.port}")
        Log.d(TAG, "Open in browser: http://${getLocalIpAddress()}:${address.port}")
    }

    override fun broadcast(message: String) {
        val disconnectedClients = mutableListOf<WebSocket>()
        
        clients.forEach { client ->
            try {
                if (client.isOpen) {
                    client.send(message)
                } else {
                    disconnectedClients.add(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to client", e)
                disconnectedClients.add(client)
            }
        }
        
        // Remove disconnected clients
        disconnectedClients.forEach { clients.remove(it) }
    }

    private fun getLocalIpAddress(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "localhost"
        } catch (e: Exception) {
            "localhost"
        }
    }
}
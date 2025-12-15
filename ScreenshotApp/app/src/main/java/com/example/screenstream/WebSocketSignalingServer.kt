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

    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients.add(conn)
        Log.d(TAG, "WebSocket client connected: ${conn.remoteSocketAddress}")
        Log.d(TAG, "Total clients: ${clients.size}")
        onClientConnected?.invoke()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        clients.remove(conn)
        Log.d(TAG, "Client disconnected. Code: $code, Reason: $reason")
        Log.d(TAG, "Remaining clients: ${clients.size}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            Log.d(TAG, "Received message: ${message.take(100)}...")
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "offer" -> onOfferReceived?.invoke(message)
                "answer" -> onAnswerReceived?.invoke(message)
                "ice-candidate" -> onIceCandidateReceived?.invoke(message)
                "client-connected" -> Log.d(TAG, "Client announced connection")
                else -> Log.d(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error on ${conn?.remoteSocketAddress}", ex)
    }

    override fun onStart() {
        Log.d(TAG, "✓ WebSocket server started successfully on port ${address.port}")
        Log.d(TAG, "Waiting for client connections...")
    }

    override fun broadcast(message: String) {
        val disconnectedClients = mutableListOf<WebSocket>()
        
        clients.forEach { client ->
            try {
                if (client.isOpen) {
                    client.send(message)
                    Log.d(TAG, "Broadcasted to client: ${client.remoteSocketAddress}")
                } else {
                    disconnectedClients.add(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to client: ${client.remoteSocketAddress}", e)
                disconnectedClients.add(client)
            }
        }
        
        disconnectedClients.forEach { clients.remove(it) }
        
        if (disconnectedClients.isNotEmpty()) {
            Log.d(TAG, "Removed ${disconnectedClients.size} disconnected clients")
        }
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
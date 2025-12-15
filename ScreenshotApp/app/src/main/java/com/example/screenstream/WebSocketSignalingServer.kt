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

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn ?: return
        clients.add(conn)
        Log.d(TAG, "Client connected")

        onClientConnected?.invoke()
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let { clients.remove(it) }
        Log.d(TAG, "Client disconnected")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        message ?: return
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "offer" -> onOfferReceived?.invoke(message)
                "answer" -> onAnswerReceived?.invoke(message)
                "ice-candidate" -> onIceCandidateReceived?.invoke(message)
                else -> Log.d(TAG, "Unknown type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message parse error", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on port ${address.port}")
    }

    override fun broadcast(message: String) {
        clients.forEach {
            try {
                it.send(message)
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast failed", e)
            }
        }
    }
}
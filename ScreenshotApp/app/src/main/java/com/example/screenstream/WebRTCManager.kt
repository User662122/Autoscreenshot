package com.example.screenstream

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

class WebRTCManager(
    private val context: Context,
    private val screenEncoder: ScreenEncoder
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webSocketServer: WebSocketSignalingServer? = null
    
    private val TAG = "WebRTCManager"
    
    // Built-in WebSocket server port
    private val WEBSOCKET_PORT = 8080

    init {
        initializeWebRTC()
        startSignalingServer()
        setupEncoderCallback()
    }

    private fun initializeWebRTC() {
        try {
            // Initialize PeerConnectionFactory
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            
            PeerConnectionFactory.initialize(initializationOptions)
            
            val options = PeerConnectionFactory.Options()
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "WebRTC initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebRTC", e)
        }
    }

    private fun startSignalingServer() {
        try {
            webSocketServer = WebSocketSignalingServer(WEBSOCKET_PORT, context)
            webSocketServer?.start()
            
            // Set up message handlers
            webSocketServer?.onOfferReceived = { offer ->
                Log.d(TAG, "Received offer from web client")
                handleOffer(offer)
            }
            
            webSocketServer?.onAnswerReceived = { answer ->
                Log.d(TAG, "Received answer from web client")
                handleAnswer(answer)
            }
            
            webSocketServer?.onIceCandidateReceived = { candidate ->
                Log.d(TAG, "Received ICE candidate from web client")
                handleIceCandidate(candidate)
            }
            
            webSocketServer?.onClientConnected = {
                Log.d(TAG, "Web client connected, creating offer")
                createOffer()
            }
            
            Log.d(TAG, "WebSocket signaling server started on port $WEBSOCKET_PORT")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting signaling server", e)
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = JSONObject().apply {
                        put("type", "ice-candidate")
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                        put("candidate", it.sdp)
                    }
                    webSocketServer?.broadcast(json.toString())
                    Log.d(TAG, "ICE candidate sent to web client")
                }
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
            }
            
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }
            
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $state")
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            
            // Added missing abstract method
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE connection receiving change: $receiving")
            }
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "PeerConnection created")
    }

    private fun createOffer() {
        createPeerConnection()
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("sdp", sdp?.description)
                        }
                        webSocketServer?.broadcast(json.toString())
                        Log.d(TAG, "Offer sent to web client")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }
            
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun setupEncoderCallback() {
        screenEncoder.onEncodedFrame = { encodedData, bufferInfo ->
            // Here you would send the encoded H.264 data through WebRTC
            // This requires custom RTP packetization or using a custom video source
            // For simplicity, this is a placeholder
            
            // In a production app, you'd create a custom VideoTrack that feeds
            // the H.264 frames into WebRTC's video pipeline
            
            Log.v(TAG, "Encoded frame: ${bufferInfo.size} bytes")
        }
    }

    private fun handleOffer(offerData: String) {
        // Not needed - Android is the broadcaster
    }
    
    private fun handleAnswer(answerData: String) {
        try {
            val data = JSONObject(answerData)
            val sdp = SessionDescription(
                SessionDescription.Type.ANSWER,
                data.getString("sdp")
            )
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully")
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {
                    Log.e(TAG, "Failed to set remote description: $p0")
                }
            }, sdp)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling answer", e)
        }
    }
    
    private fun handleIceCandidate(candidateData: String) {
        try {
            val data = JSONObject(candidateData)
            val candidate = IceCandidate(
                data.getString("sdpMid"),
                data.getInt("sdpMLineIndex"),
                data.getString("candidate")
            )
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "Added ICE candidate from web client")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate", e)
        }
    }

    fun getServerUrl(): String {
        return "http://YOUR_DEVICE_IP:$WEBSOCKET_PORT"
    }

    fun release() {
        Log.d(TAG, "Releasing WebRTC")
        
        webSocketServer?.stop()
        webSocketServer = null
        
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        Log.d(TAG, "WebRTC released")
    }
}

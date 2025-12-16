package com.example.screenstream

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val mediaProjectionPermissionIntent: Intent
) {

    private val TAG = "WebRTCManager"

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    // Combined HTTP + WebSocket server on single port 8080 - pass context for assets
    private val server = CombinedServer(8080, context)

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        startServer()
    }

    private fun startServer() {
        // Setup callbacks
        server.onClientConnected = {
            Log.d(TAG, "Client connected, initializing stream...")
            // Cleanup any existing peer connection before creating new one
            cleanupPeerConnection()
            createPeer()
            startCapture()
            createOffer()
        }

        server.onAnswerReceived = { handleAnswer(it) }
        server.onIceCandidateReceived = { handleIce(it) }
        
        // Start combined server
        try {
            server.start()
            Log.d(TAG, "Combined HTTP+WebSocket server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun cleanupPeerConnection() {
        Log.d(TAG, "Cleaning up existing peer connection...")
        
        try {
            // Stop and dispose video track
            videoTrack?.setEnabled(false)
            videoTrack?.dispose()
            videoTrack = null
            
            // Stop screen capture
            try {
                capturer?.stopCapture()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping capture: ${e.message}")
            }
            capturer?.dispose()
            capturer = null
            
            // Dispose video source
            videoSource?.dispose()
            videoSource = null
            
            // Dispose surface helper
            surfaceHelper?.dispose()
            surfaceHelper = null
            
            // Close peer connection
            peerConnection?.close()
            peerConnection = null
            
            Log.d(TAG, "Peer connection cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    private fun createPeer() {
        Log.d(TAG, "Creating peer connection...")
        
        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer(),
            PeerConnection.IceServer
                .builder("stun:stun1.l.google.com:19302")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {

                override fun onIceCandidate(c: IceCandidate) {
                    Log.d(TAG, "Sending ICE candidate: ${c.sdpMid}")
                    server.broadcast(
                        JSONObject().apply {
                            put("type", "ice-candidate")
                            put("sdpMid", c.sdpMid)
                            put("sdpMLineIndex", c.sdpMLineIndex)
                            put("candidate", c.sdp)
                        }.toString()
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                    Log.d(TAG, "ICE candidates removed: ${candidates.size}")
                }
                
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE Connection State: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.d(TAG, "ICE Connected - Stream should be active!")
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE Connection Failed")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "ICE Disconnected")
                        }
                        else -> {}
                    }
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "ICE Connection Receiving: $receiving")
                }
                
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE Gathering State: $state")
                }
                
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "Signaling State: $state")
                }
                
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "Connection State: $state")
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            Log.d(TAG, "Peer Connected - Streaming!")
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.e(TAG, "Peer Connection Failed")
                        }
                        else -> {}
                    }
                }
                
                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "Stream added: ${stream.id}")
                }
                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(TAG, "Stream removed: ${stream.id}")
                }
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }
                override fun onAddTrack(
                    receiver: RtpReceiver,
                    streams: Array<MediaStream>
                ) {
                    Log.d(TAG, "Track added: ${receiver.track()?.kind()}")
                }
            }
        )
        
        Log.d(TAG, "Peer connection created")
    }

    private fun startCapture() {
        Log.d(TAG, "Starting screen capture...")
        
        try {
            surfaceHelper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
            videoSource = factory.createVideoSource(true) // isScreencast = true

            capturer = ScreenCapturerAndroid(
                mediaProjectionPermissionIntent,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.e(TAG, "MediaProjection stopped")
                    }
                }
            )

            capturer!!.initialize(
                surfaceHelper,
                context,
                videoSource!!.capturerObserver
            )

            // Start capture at reasonable resolution
            capturer!!.startCapture(720, 1280, 5)

            videoTrack = factory.createVideoTrack("screen_track", videoSource)
            videoTrack!!.setEnabled(true)
            
            // Add track to peer connection
            peerConnection!!.addTrack(videoTrack)
            
            Log.d(TAG, "Screen capture started: 720x1280 @ 30fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}", e)
        }
    }

    private fun createOffer() {
        Log.d(TAG, "Creating offer...")
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set")
                        server.broadcast(
                            JSONObject().apply {
                                put("type", "offer")
                                put("sdp", sdp.description)
                            }.toString()
                        )
                        Log.d(TAG, "Offer sent to client")
                    }
                    
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                    
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun handleAnswer(msg: String) {
        Log.d(TAG, "Received answer from client")
        
        try {
            val json = JSONObject(msg)
            val sdp = SessionDescription(
                SessionDescription.Type.ANSWER,
                json.getString("sdp")
            )
            
            peerConnection?.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote description set - Connection should be established")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set remote description: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                },
                sdp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling answer: ${e.message}", e)
        }
    }

    private fun handleIce(msg: String) {
        Log.d(TAG, "Received ICE candidate from client")
        
        try {
            val j = JSONObject(msg)
            val candidate = IceCandidate(
                j.getString("sdpMid"),
                j.getInt("sdpMLineIndex"),
                j.getString("candidate")
            )
            
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "ICE candidate added")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate: ${e.message}", e)
        }
    }

    fun release() {
        Log.d(TAG, "Releasing WebRTC resources...")
        
        cleanupPeerConnection()
        server.stop()
        factory.dispose()
        eglBase.release()
        
        Log.d(TAG, "All resources released")
    }
}
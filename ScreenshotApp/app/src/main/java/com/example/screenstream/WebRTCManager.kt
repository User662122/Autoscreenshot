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

    private val wsServer = WebSocketSignalingServer(8080, context)
    private val httpServer = HttpServer(8080)

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

        startServers()
    }

    private fun startServers() {
        // Start HTTP server for web page
        try {
            httpServer.start()
            Log.d(TAG, "HTTP server started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }

        // Start WebSocket server for signaling
        wsServer.onClientConnected = {
            Log.d(TAG, "Client connected, creating peer and starting capture")
            createPeer()
            startCapture()
            createOffer()
        }

        wsServer.onAnswerReceived = { handleAnswer(it) }
        wsServer.onIceCandidateReceived = { handleIce(it) }
        
        try {
            wsServer.start()
            Log.d(TAG, "WebSocket server started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket server", e)
        }
    }

    private fun createPeer() {
        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )

        peerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            object : PeerConnection.Observer {

                override fun onIceCandidate(c: IceCandidate) {
                    wsServer.broadcast(
                        JSONObject().apply {
                            put("type", "ice-candidate")
                            put("sdpMid", c.sdpMid)
                            put("sdpMLineIndex", c.sdpMLineIndex)
                            put("candidate", c.sdp)
                        }.toString()
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE Connection State: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE Gathering State: $state")
                }
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "Signaling State: $state")
                }
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "Connection State: $state")
                }
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver,
                    streams: Array<MediaStream>
                ) {}
            }
        )
    }

    private fun startCapture() {
        surfaceHelper =
            SurfaceTextureHelper.create("Screen", eglBase.eglBaseContext)

        videoSource = factory.createVideoSource(false)

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

        capturer!!.startCapture(720, 1280, 30)

        peerConnection!!.addTrack(
            factory.createVideoTrack("screen", videoSource)
        )
        
        Log.d(TAG, "Screen capture started: 720x1280 @ 30fps")
    }

    private fun createOffer() {
        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection!!.setLocalDescription(this, sdp)
                wsServer.broadcast(
                    JSONObject().apply {
                        put("type", "offer")
                        put("sdp", sdp.description)
                    }.toString()
                )
                Log.d(TAG, "Offer created and sent")
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetSuccess() {
                Log.d(TAG, "Local description set successfully")
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set local description: $error")
            }
        }, MediaConstraints())
    }

    private fun handleAnswer(msg: String) {
        val json = JSONObject(msg)
        peerConnection!!.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set successfully")
                }
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote description: $error")
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            },
            SessionDescription(
                SessionDescription.Type.ANSWER,
                json.getString("sdp")
            )
        )
    }

    private fun handleIce(msg: String) {
        val j = JSONObject(msg)
        peerConnection!!.addIceCandidate(
            IceCandidate(
                j.getString("sdpMid"),
                j.getInt("sdpMLineIndex"),
                j.getString("candidate")
            )
        )
        Log.d(TAG, "ICE candidate added")
    }

    fun release() {
        capturer?.stopCapture()
        capturer?.dispose()
        surfaceHelper?.dispose()
        videoSource?.dispose()
        peerConnection?.close()
        wsServer.stop()
        httpServer.stop()
        Log.d(TAG, "WebRTC resources released")
    }
}
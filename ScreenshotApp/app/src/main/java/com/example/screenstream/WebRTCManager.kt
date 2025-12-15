package com.example.screenstream

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val mediaProjectionPermissionIntent: Intent
) {

    private val TAG = "WebRTCManager"

    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var webSocketServer: WebSocketSignalingServer? = null

    private val WEBSOCKET_PORT = 8080

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(
            EglBase.create().eglBaseContext
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        startSignalingServer()
    }

    // ----------------------------------------------------
    // SIGNALING SERVER
    // ----------------------------------------------------
    private fun startSignalingServer() {
        webSocketServer = WebSocketSignalingServer(WEBSOCKET_PORT, context)

        webSocketServer?.onClientConnected = {
            createPeerConnection()
            startScreenCapture()
            createOffer()
        }

        webSocketServer?.onAnswerReceived = { handleAnswer(it) }
        webSocketServer?.onIceCandidateReceived = { handleRemoteIce(it) }

        webSocketServer?.start()
    }

    // ----------------------------------------------------
    // PEER CONNECTION
    // ----------------------------------------------------
    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    val json = JSONObject().apply {
                        put("type", "ice-candidate")
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    }
                    webSocketServer?.broadcast(json.toString())
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {}
            }
        )
    }

    // ----------------------------------------------------
    // SCREEN CAPTURE (WHATSAPP STYLE)
    // ----------------------------------------------------
    private fun startScreenCapture() {
        val eglBase = EglBase.create()

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "ScreenThread",
            eglBase.eglBaseContext
        )

        videoSource = peerConnectionFactory.createVideoSource(false)

        capturer = ScreenCapturerAndroid(
            mediaProjectionPermissionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.e(TAG, "MediaProjection stopped")
                }
            }
        )

        capturer!!.initialize(
            surfaceTextureHelper,
            context,
            videoSource!!.capturerObserver
        )

        capturer!!.startCapture(720, 1280, 30)

        videoTrack = peerConnectionFactory.createVideoTrack("SCREEN", videoSource)
        videoTrack!!.setEnabled(true)

        peerConnection!!.addTrack(videoTrack)

        Log.d(TAG, "Screen capture started")
    }

    // ----------------------------------------------------
    // OFFER / ANSWER
    // ----------------------------------------------------
    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {

            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("sdp", sdp?.description)
                        }
                        webSocketServer?.broadcast(json.toString())
                    }

                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun handleAnswer(message: String) {
        val json = JSONObject(message)
        val answer = SessionDescription(
            SessionDescription.Type.ANSWER,
            json.getString("sdp")
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answer)
    }

    private fun handleRemoteIce(message: String) {
        val json = JSONObject(message)
        val candidate = IceCandidate(
            json.getString("sdpMid"),
            json.getInt("sdpMLineIndex"),
            json.getString("candidate")
        )
        peerConnection?.addIceCandidate(candidate)
    }

    fun release() {
        capturer?.stopCapture()
        capturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        peerConnection?.close()
        webSocketServer?.stop()
    }
}
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

    private val server = WebSocketSignalingServer(8080, context)

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
        server.onClientConnected = {
            createPeer()
            startCapture()
            createOffer()
        }

        server.onAnswerReceived = { handleAnswer(it) }
        server.onIceCandidateReceived = { handleIce(it) }
        server.start()
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
                    server.broadcast(
                        JSONObject().apply {
                            put("type", "ice-candidate")
                            put("sdpMid", c.sdpMid)
                            put("sdpMLineIndex", c.sdpMLineIndex)
                            put("candidate", c.sdp)
                        }.toString()
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {}
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
    } // ✅ VERY IMPORTANT — function closed here

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
    }

    private fun createOffer() {
        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection!!.setLocalDescription(this, sdp)
                server.broadcast(
                    JSONObject().apply {
                        put("type", "offer")
                        put("sdp", sdp.description)
                    }.toString()
                )
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun handleAnswer(msg: String) {
        val json = JSONObject(msg)
        peerConnection!!.setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
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
    }

    fun release() {
        capturer?.stopCapture()
        capturer?.dispose()
        surfaceHelper?.dispose()
        videoSource?.dispose()
        peerConnection?.close()
        server.stop()
    }
}
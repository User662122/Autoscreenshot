package com.example.screenstream

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val screenEncoder: ScreenEncoder
) {

    private val TAG = "WebRTCManager"

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webSocketServer: WebSocketSignalingServer? = null

    private val WEBSOCKET_PORT = 8080

    init {
        initWebRTC()
        startSignalingServer()
    }

    // ----------------------------------------------------
    // WebRTC INIT
    // ----------------------------------------------------
    private fun initWebRTC() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized")
    }

    // ----------------------------------------------------
    // SIGNALING SERVER
    // ----------------------------------------------------
    private fun startSignalingServer() {
        webSocketServer = WebSocketSignalingServer(WEBSOCKET_PORT, context)

        webSocketServer?.onClientConnected = {
            Log.d(TAG, "Browser connected → creating offer")
            createOffer()
        }

        webSocketServer?.onAnswerReceived = { answer ->
            handleAnswer(answer)
        }

        webSocketServer?.onIceCandidateReceived = { candidate ->
            handleRemoteIce(candidate)
        }

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

        val observer = object : PeerConnection.Observer {

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

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {}
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, observer)
    }

    // ----------------------------------------------------
    // SDP OFFER
    // ----------------------------------------------------
    private fun createOffer() {
        createPeerConnection()

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

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

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // ----------------------------------------------------
    // SDP ANSWER
    // ----------------------------------------------------
    private fun handleAnswer(message: String) {
        val json = JSONObject(message)
        val sdp = json.getString("sdp")

        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Answer set successfully")
            }

            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answer)
    }

    // ----------------------------------------------------
    // ICE FROM BROWSER
    // ----------------------------------------------------
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
        webSocketServer?.stop()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
    }
}
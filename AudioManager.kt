package com.example.call_app

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class AudioManager(private val context: Context, private val signalingListener: SignalingListener) {

    interface SignalingListener {
        fun onOfferCreated(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        // DISABLE hardware echo cancellation so the phone mic picks up the
        // caller's voice from the speaker and relays it to Mac via WebRTC.
        // With echo cancellation ON, it removes the caller's voice (since
        // it plays through the speaker) from the mic input — exactly the
        // audio we WANT to capture.
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        adm.release()

        createAudioTrack()
        createPeerConnection()
    }

    private fun createAudioTrack() {
        // Disable echo cancellation and noise suppression on the WebRTC
        // constraints too — we WANT the phone mic to pick up speaker audio
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
        }
        audioSource = peerConnectionFactory?.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
        Log.d("WebRTC", "🎤 Audio track ready (echo cancel OFF)")
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d("WebRTC", "📍 ICE candidate found")
                signalingListener.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d("WebRTC", "🔊 Mac audio stream received (${stream.audioTracks.size} tracks)")
                if (stream.audioTracks.isNotEmpty()) {
                    val remoteTrack = stream.audioTracks[0]
                    remoteTrack.setEnabled(true)
                    remoteTrack.setVolume(10.0)  // Boost Mac audio volume
                    Log.d("WebRTC", "🔊 Remote audio track enabled + volume boosted")
                }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.d("WebRTC", "📶 ICE connection: $s")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })

        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(this, desc)
                Log.d("WebRTC", "🤝 Offer created, sending to Mac")
                signalingListener.onOfferCreated(desc.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String) { Log.e("WebRTC", "Offer failed: $err") }
            override fun onSetFailure(err: String) {}
        }, constraints)
    }

    fun setRemoteDescription(sdpAnswer: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { Log.d("WebRTC", "✅ Mac answer applied") }
            override fun onSetFailure(err: String) { Log.e("WebRTC", "❌ Answer failed: $err") }
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String) {}
        }, answer)
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun dispose() {
        try {
            localAudioTrack?.dispose()
            audioSource?.dispose()
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
        } catch (e: Exception) {}
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        peerConnectionFactory = null
        Log.d("WebRTC", "🔇 WebRTC cleaned up")
    }
}
package com.call.callserver;

import java.util.List;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

public class DesktopWebRTCManager {
    private AudioDeviceModule adm;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private AudioTrack localAudioTrack;
    private AudioTrackSource audioSource;

    public interface WebSignalingListener {
        void onAnswerCreated(String sdp);
        void onIceCandidate(String candidate, String sdpMid, int sdpMLineIndex);
    }
    private WebSignalingListener listener;
    private boolean alive = false;
    private boolean audioStarted = false;

    public DesktopWebRTCManager(WebSignalingListener listener) {
        this.listener = listener;
        initWebRTC();
    }

    private void initWebRTC() {
        adm = new AudioDeviceModule();

        AudioDevice mic = MediaDevices.getDefaultAudioCaptureDevice();
        AudioDevice speaker = MediaDevices.getDefaultAudioRenderDevice();

        if (mic != null) {
            adm.setRecordingDevice(mic);
            System.out.println("🎤 Recording device: " + mic.getName());
        } else {
            System.err.println("⚠️ No recording device found!");
        }

        if (speaker != null) {
            adm.setPlayoutDevice(speaker);
            System.out.println("🔊 Playout device: " + speaker.getName());
        } else {
            System.err.println("⚠️ No playout device found!");
        }

        try {
            adm.initRecording();
            System.out.println("✅ Recording initialized");
        } catch (Throwable e) {
            System.err.println("❌ initRecording failed: " + e.getMessage());
        }

        try {
            adm.initPlayout();
            System.out.println("✅ Playout initialized");
        } catch (Throwable e) {
            System.err.println("❌ initPlayout failed: " + e.getMessage());
        }

        factory = new PeerConnectionFactory(adm);

        AudioOptions audioOptions = new AudioOptions();
        audioOptions.echoCancellation = true;
        audioOptions.noiseSuppression = true;
        audioOptions.autoGainControl = true;

        audioSource = factory.createAudioSource(audioOptions);
        localAudioTrack = factory.createAudioTrack("mac_audio", audioSource);
        System.out.println("🎤 Mac audio track created.");

        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls = List.of("stun:stun.l.google.com:19302");
        config.iceServers.add(iceServer);
        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                listener.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex);
            }

            @Override
            public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
                System.out.println("🔊 Remote audio track received from Android.");
            }
        });

        peerConnection.addTrack(localAudioTrack, List.of("MAC_STREAM"));
        alive = true;
        audioStarted = false;
    }

  
    private void startAudio() {
        if (audioStarted) return;
        audioStarted = true;

        try {
            adm.startPlayout();
            System.out.println("▶️ Playout started — Mac speakers active!");
        } catch (Throwable e) {
            System.err.println("❌ startPlayout failed: " + e.getMessage());
        }

        try {
            adm.startRecording();
            System.out.println("▶️ Recording started — Mac mic active!");
        } catch (Throwable e) {
            System.err.println("❌ startRecording failed: " + e.getMessage());
        }
    }

    public void receiveOfferAndAnswer(String remoteSdpOffer) {
   
        if (!alive) {
            System.out.println("🔄 Re-initializing WebRTC for new call...");
            initWebRTC();
        }

        startAudio();

        RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, remoteSdpOffer);
        peerConnection.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription sessionDescription) {
                        peerConnection.setLocalDescription(sessionDescription, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                System.out.println("🤝 WebRTC Answer generated! Sending to Phone...");
                                listener.onAnswerCreated(sessionDescription.sdp);
                            }
                            @Override
                            public void onFailure(String s) {
                                System.err.println("❌ setLocalDescription failed: " + s);
                            }
                        });
                    }
                    @Override
                    public void onFailure(String s) {
                        System.err.println("❌ createAnswer failed: " + s);
                    }
                });
            }
            @Override
            public void onFailure(String s) {
                System.err.println("❌ setRemoteDescription failed: " + s);
            }
        });
    }

    public void addRemoteIceCandidate(String sdp, String sdpMid, int sdpMLineIndex) {
        if (!alive) return;
        RTCIceCandidate candidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, sdp);
        peerConnection.addIceCandidate(candidate);
        System.out.println("📍 Saved Android IP Address route.");
    }

    public void dispose() {
        if (!alive) return;
        alive = false;

        try {
            if (adm != null) {
                try { adm.stopRecording(); } catch (Throwable ignored) {}
                try { adm.stopPlayout(); } catch (Throwable ignored) {}
            }

            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }

            if (factory != null) {
                factory.dispose();
                factory = null;
            }

            if (adm != null) {
                adm.dispose();
                adm = null;
            }

            localAudioTrack = null;
            audioSource = null;
            audioStarted = false;

            System.out.println("🔇 Mac WebRTC cleaned up — mic released.");
        } catch (Exception e) {
            System.err.println("⚠️ Error during WebRTC cleanup: " + e.getMessage());
        }
    }
}

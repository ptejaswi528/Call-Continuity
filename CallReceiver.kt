package com.example.call_app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import org.json.JSONObject

class CallReceiver : BroadcastReceiver() {

    companion object {
        const val MAC_IP = "192.168.1.12"
        var rtcManager: AudioManager? = null
        val client = OkHttpClient()
        var isListening = false
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext

        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val numberString = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val incomingNumber = if (numberString.isNullOrEmpty()) "Private Number" else numberString

                sendEventToMac(incomingNumber)
                listenForMacCommand(appContext)
            }
        }
    }

    private fun sendEventToMac(number: String) {
        val url = "http://$MAC_IP:8080/api/calls/event/incoming.call"
        val payload = """{"contact": "Incoming Call", "number": "$number"}"""

        try {
            val body = RequestBody.create("application/json".toMediaType(), payload)
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {}
            })
        } catch (e: Exception) {}
    }

    private fun listenForMacCommand(appContext: Context) {
        if (isListening) return
        isListening = true

        Thread {
            try {
                val factory = ConnectionFactory()
                factory.host = MAC_IP
                factory.username = "guest"
                factory.password = "guest"

                val connection = factory.newConnection()
                val channel = connection.createChannel()

                // Clear any stale reject/end messages from previous sessions
                channel.queuePurge("phone_queue_clean")

                val callback = DeliverCallback { _, delivery ->
                    val message = String(delivery.body, Charsets.UTF_8)

                    if (message.startsWith("{") && message.contains("\"type\"")) {
                        try {
                            val json = JSONObject(message)
                            val type = json.getString("type")

                            if (type == "ANSWER") {
                                rtcManager?.setRemoteDescription(json.getString("sdp"))
                            } else if (type == "ICE") {
                                rtcManager?.addIceCandidate(
                                    json.getString("candidate"),
                                    json.getString("sdpMid"),
                                    json.getInt("sdpMLineIndex")
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("CallReceiver", "WebRTC signal parse error: ${e.message}")
                        }
                    }
                    else if (message.contains("accept.call")) {
                        answerCall(appContext)

                        // let telephony fully connect before we touch audio routing
                        Thread.sleep(1000)

                        // force speaker so the phone mic can pick up the caller's voice
                        // and relay it to Mac via WebRTC
                        forceCallToSpeaker(appContext)

                        try {
                            rtcManager = AudioManager(appContext, object : AudioManager.SignalingListener {
                                override fun onOfferCreated(sdp: String) {
                                    try {
                                        val json = JSONObject().apply {
                                            put("type", "OFFER")
                                            put("sdp", sdp)
                                        }
                                        sendWebRTCPayload("webrtc.offer", json)
                                    } catch (e: Exception) {}
                                }

                                override fun onIceCandidate(candidate: String, mid: String, index: Int) {
                                    try {
                                        val json = JSONObject().apply {
                                            put("type", "ICE")
                                            put("candidate", candidate)
                                            put("sdpMid", mid)
                                            put("sdpMLineIndex", index)
                                        }
                                        sendWebRTCPayload("webrtc.ice.mac", json)
                                    } catch (e: Exception) {}
                                }
                            })
                            rtcManager?.createOffer()
                        } catch (e: Exception) {
                            Log.e("CallReceiver", "WebRTC init failed: ${e.message}")
                        }
                    }
                    else if (message.contains("reject.call") || message.contains("end.call")) {
                        rtcManager?.dispose()
                        rtcManager = null
                        rejectCall(appContext)
                        try {
                            channel.close()
                            connection.close()
                        } catch (e: Exception) {}
                        isListening = false
                    }
                }

                channel.basicConsume("phone_queue_clean", true, callback, { _ -> })

            } catch (e: Exception) {
                Log.e("CallReceiver", "RabbitMQ error: ${e.message}")
                isListening = false
            }
        }.start()
    }

    // routes the call audio to the loudspeaker so the mic picks up the caller's voice
    // supports both old setSpeakerphoneOn and new Android 12+ setCommunicationDevice
    private fun forceCallToSpeaker(appContext: Context) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                am.setCommunicationDevice(speaker)
                Log.d("CallReceiver", "🔊 Speaker routed via setCommunicationDevice (Android 12+)")
            } else {
                am.isSpeakerphoneOn = true
                Log.d("CallReceiver", "🔊 Speaker fallback via setSpeakerphoneOn")
            }
        } else {
            am.isSpeakerphoneOn = true
            Log.d("CallReceiver", "🔊 Speaker routed via setSpeakerphoneOn")
        }
    }

    @SuppressLint("MissingPermission")
    private fun answerCall(appContext: Context) {
        try {
            val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            Log.d("CallReceiver", "📞 Call answered via TelecomManager")
        } catch (e: Exception) {
            Log.e("CallReceiver", "❌ Failed to answer call: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun rejectCall(appContext: Context) {
        try {
            val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.endCall()

            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            Log.d("CallReceiver", "📞 Call ended, audio restored")
        } catch (e: Exception) {}
    }

    private fun sendWebRTCPayload(routingKey: String, payload: JSONObject) {
        val url = "http://$MAC_IP:8080/api/calls/event/$routingKey"

        try {
            val body = RequestBody.create("application/json".toMediaType(), payload.toString())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {}
            })
        } catch (e: Exception) {}
    }
}

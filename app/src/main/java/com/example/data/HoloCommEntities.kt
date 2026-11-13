package com.example.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

/**
 * Global operator presence document stored at `/global_presence/{userId}`.
 * Enables multiple devices (e.g., Phone A and Phone B on different Google accounts)
 * to discover each other online in real time.
 */
@IgnoreExtraProperties
data class GlobalOperatorPresenceDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val callSign: String = "",
    val presence: String = "ONLINE", // ONLINE, OFFLINE, TYPING, BUSY
    val statusMessage: String? = null,
    val e2eeFingerprint: String = "",
    val deviceModel: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Shared multi-device communication message or control signal stored at
 * `/global_comms_rooms/{roomId}/messages/{messageId}`.
 */
@IgnoreExtraProperties
data class GlobalRoomMessageDoc(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val senderLabel: String = "",
    val text: String = "",
    val messageType: String = "TEXT", // TEXT, VOICE_NOTE, IMAGE, FILE, CONTROL_SIGNAL, WEBRTC_OFFER, WEBRTC_ANSWER, WEBRTC_ICE
    val status: String = "SENT",      // SENT, DELIVERED, READ
    val isEncrypted: Boolean = true,
    val mediaUri: String? = null,
    val audioDurationMs: Long? = null,
    val cipherHex: String? = null,
    val modelBadge: String? = null,
    val signalPayload: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Shared multi-device WebRTC signaling packet stored at
 * `/global_comms_rooms/{roomId}/rtc_signals/{signalId}`.
 */
@IgnoreExtraProperties
data class GlobalRtcSignalDoc(
    @DocumentId val id: String = "",
    val roomId: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val callType: String = "VIDEO",        // AUDIO, VIDEO
    val signalType: String = "SDP_OFFER",  // RINGING, SDP_OFFER, SDP_ANSWER, ICE_CANDIDATE, CONNECTED, ENDED
    val sdpPayload: String = "",
    val iceCandidate: String = "",
    val iceCandidatesCount: Long = 0L,
    val isEncrypted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class HoloMessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val peerId: String = "",
    val senderId: String = "",
    val senderLabel: String = "",
    val text: String = "",
    val messageType: String = "TEXT", // TEXT, VOICE_NOTE, IMAGE, FILE
    val status: String = "SENT",      // SENT, DELIVERED, READ
    val isFromMe: Boolean = true,
    val isEncrypted: Boolean = true,
    val mediaUri: String? = null,
    val audioDurationMs: Long? = null,
    val cipherHex: String? = null,
    val modelBadge: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class HoloPeerPresenceDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val peerId: String = "",
    val displayName: String = "",
    val presence: String = "ONLINE", // ONLINE, OFFLINE, TYPING
    val unreadCount: Long = 0L,
    val lastMessagePreview: String = "",
    val isAiFallbackEnabled: Boolean = true,
    val e2eeFingerprint: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class HoloRtcSessionDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val peerId: String = "",
    val callType: String = "AUDIO",      // AUDIO, VIDEO
    val callState: String = "OFFER",     // RINGING, OFFER, ANSWER, CONNECTED, ENDED
    val sdpOffer: String = "",
    val sdpAnswer: String = "",
    val iceCandidatesCount: Long = 0L,
    val latestIceCandidate: String = "",
    val isEncrypted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

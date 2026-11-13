package com.example.data

import android.content.Context
import android.os.Build
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class HoloCommRepository(private val db: FirebaseFirestore) {

    // Secondary constructor resolving the custom named Firestore database ID from firebase_applet_config.xml
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Operator must be signed in with Google before accessing Firestore.")
    }

    // =========================================================================
    // 1. DETERMINISTIC SHARED ROOM ID GENERATOR
    // =========================================================================

    /**
     * Generates a consistent, deterministic `roomId` for any two operators (e.g., Phone A & Phone B)
     * by sanitizing and alphabetically sorting their `userId`s, then joining with `_`.
     * Guaranteed that `buildDeterministicRoomId(uidA, uidB) == buildDeterministicRoomId(uidB, uidA)`.
     */
    fun buildDeterministicRoomId(userIdA: String, userIdB: String): String {
        return Companion.buildDeterministicRoomId(userIdA, userIdB)
    }

    companion object {
        fun buildDeterministicRoomId(userIdA: String, userIdB: String): String {
            val cleanA = userIdA.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val cleanB = userIdB.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            require(cleanA.isNotBlank() && cleanB.isNotBlank()) {
                "Both operator IDs must be non-blank to build a shared roomId."
            }
            val combined = listOf(cleanA, cleanB).sorted().joinToString("_")
            return combined.take(128)
        }
    }

    // =========================================================================
    // 2. GLOBAL OPERATOR PRESENCE (/global_presence/{userId})
    // =========================================================================

    /**
     * Publishes or updates the local authenticated operator's presence in `/global_presence/{userId}`
     * so other logged-in devices (Phone B, Tablet C, etc.) see this device online in real time.
     */
    suspend fun updateGlobalOperatorPresence(
        presence: String = "ONLINE", // ONLINE, OFFLINE, TYPING, BUSY
        displayName: String? = null,
        callSign: String? = null,
        e2eeFingerprint: String = "A4F9-99C1-7B2E-00D4",
        statusMessage: String? = "ORBITAL LINK READY",
        deviceModel: String? = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val currentUser = auth.currentUser
        val resolvedName = (displayName ?: currentUser?.displayName ?: "OPERATOR-${uid.take(5).uppercase()}").take(80)
        val resolvedCallSign = (callSign ?: "OPR // ${resolvedName.uppercase().take(12)}-${uid.take(4).uppercase()}").take(80)
        val docRef = db.collection("global_presence").document(uid)

        val payload = mapOf<String, Any?>(
            "userId" to uid,
            "displayName" to resolvedName,
            "callSign" to resolvedCallSign,
            "presence" to presence,
            "statusMessage" to statusMessage,
            "e2eeFingerprint" to e2eeFingerprint,
            "deviceModel" to deviceModel,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    /**
     * Observes `/global_presence` in real time using `callbackFlow` + `addSnapshotListener`
     * so all logged-in devices immediately see who is online, typing, or available.
     */
    fun observeGlobalActiveOperators(): Flow<List<GlobalOperatorPresenceDoc>> = callbackFlow {
        val uid = requireUserId()
        val path = "global_presence"
        val registration = db.collection(path)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val operators = snapshot.toObjects(
                        GlobalOperatorPresenceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                    trySend(operators)
                }
            }
        awaitClose { registration.remove() }
    }

    // =========================================================================
    // 3. SHARED MULTI-DEVICE COMMS ROOMS (/global_comms_rooms/{roomId}/messages)
    // =========================================================================

    /**
     * Sends a text message, voice note, media attachment, or control signal to the shared room
     * `/global_comms_rooms/{roomId}/messages` between the current operator and `recipientId`.
     */
    suspend fun sendSharedRoomMessage(
        roomId: String,
        recipientId: String,
        senderLabel: String,
        text: String,
        messageType: String = "TEXT", // TEXT, VOICE_NOTE, IMAGE, FILE, CONTROL_SIGNAL, WEBRTC_OFFER, WEBRTC_ANSWER, WEBRTC_ICE
        status: String = "SENT",
        isEncrypted: Boolean = true,
        mediaUri: String? = null,
        audioDurationMs: Long? = null,
        cipherHex: String? = null,
        modelBadge: String? = null,
        signalPayload: String? = null,
        explicitSenderId: String? = null
    ): Result<String> = runCatching {
        val uid = explicitSenderId ?: requireUserId()
        val docRef = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("messages")
            .document()

        val payload = mapOf<String, Any?>(
            "roomId" to roomId,
            "senderId" to uid,
            "recipientId" to recipientId,
            "senderLabel" to senderLabel,
            "text" to text,
            "messageType" to messageType,
            "status" to status,
            "isEncrypted" to isEncrypted,
            "mediaUri" to mediaUri,
            "audioDurationMs" to audioDurationMs,
            "cipherHex" to cipherHex,
            "modelBadge" to modelBadge,
            "signalPayload" to signalPayload,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    /**
     * Updates the delivery/read receipt status (`SENT` -> `DELIVERED` -> `READ`) of a message
     * inside `/global_comms_rooms/{roomId}/messages/{messageId}`.
     */
    suspend fun updateSharedRoomMessageStatus(
        roomId: String,
        messageId: String,
        newStatus: String
    ): Result<Unit> = runCatching {
        requireUserId()
        require(roomId.isNotBlank() && messageId.isNotBlank()) { "roomId and messageId must not be blank" }
        val docRef = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("messages")
            .document(messageId)
        try {
            docRef.update(
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    /**
     * Observes messages and control signals in `/global_comms_rooms/{roomId}/messages` in real time
     * using `callbackFlow` + `addSnapshotListener`.
     */
    fun observeSharedRoomMessages(roomId: String): Flow<List<GlobalRoomMessageDoc>> = callbackFlow {
        val uid = requireUserId()
        val path = "global_comms_rooms/$roomId/messages"
        val registration = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val docs = snapshot.toObjects(
                        GlobalRoomMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt?.toDate()?.time ?: 0L }
                    trySend(docs)
                }
            }
        awaitClose { registration.remove() }
    }

    // =========================================================================
    // 4. SHARED WEBRTC SIGNALING (/global_comms_rooms/{roomId}/rtc_signals)
    // =========================================================================

    /**
     * Publishes a WebRTC signaling packet (SDP Offer, SDP Answer, ICE Candidate) to
     * `/global_comms_rooms/{roomId}/rtc_signals` so the remote device can establish P2P video/audio.
     */
    suspend fun sendSharedRtcSignal(
        roomId: String,
        recipientId: String,
        callType: String = "VIDEO", // AUDIO, VIDEO
        signalType: String = "SDP_OFFER", // RINGING, SDP_OFFER, SDP_ANSWER, ICE_CANDIDATE, CONNECTED, ENDED
        sdpPayload: String,
        iceCandidate: String = "candidate:1 1 UDP 2122252543 192.168.1.10 54321 typ host",
        iceCandidatesCount: Long = 1L,
        isEncrypted: Boolean = true
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("rtc_signals")
            .document()

        val payload = mapOf(
            "roomId" to roomId,
            "senderId" to uid,
            "recipientId" to recipientId,
            "callType" to callType,
            "signalType" to signalType,
            "sdpPayload" to sdpPayload,
            "iceCandidate" to iceCandidate,
            "iceCandidatesCount" to iceCandidatesCount,
            "isEncrypted" to isEncrypted,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    /**
     * Updates an active WebRTC signal state (e.g. Phone B answering Phone A's SDP_OFFER with SDP_ANSWER / CONNECTED).
     */
    suspend fun updateSharedRtcSignalState(
        roomId: String,
        signalId: String,
        signalType: String,
        sdpPayload: String? = null,
        iceCandidate: String? = null,
        iceCandidatesCount: Long? = null
    ): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("rtc_signals")
            .document(signalId)

        val updates = mutableMapOf<String, Any>(
            "signalType" to signalType,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (sdpPayload != null) updates["sdpPayload"] = sdpPayload
        if (iceCandidate != null) updates["iceCandidate"] = iceCandidate
        if (iceCandidatesCount != null) updates["iceCandidatesCount"] = iceCandidatesCount

        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    /**
     * Observes WebRTC signaling events in `/global_comms_rooms/{roomId}/rtc_signals` in real time
     * using `callbackFlow` + `addSnapshotListener`.
     */
    fun observeSharedRtcSignals(roomId: String): Flow<List<GlobalRtcSignalDoc>> = callbackFlow {
        val uid = requireUserId()
        val path = "global_comms_rooms/$roomId/rtc_signals"
        val registration = db.collection("global_comms_rooms")
            .document(roomId)
            .collection("rtc_signals")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(error, OperationType.LIST, path)
                    }
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val signals = snapshot.toObjects(
                        GlobalRtcSignalDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }
                    trySend(signals)
                }
            }
        awaitClose { registration.remove() }
    }

    // =========================================================================
    // 5. USER-SCOPED ARCHIVE & AI FALLBACK (/users/{userId}/...)
    // =========================================================================

    fun observeMessages(): Flow<List<HoloMessageDoc>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/messages"
        emitAll(
            db.collection("users").document(uid).collection("messages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HoloMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getMessagesForUser(targetUserId: String = requireUserId()): Result<List<HoloMessageDoc>> = runCatching {
        val path = "users/$targetUserId/messages"
        try {
            val snapshot = db.collection("users").document(targetUserId).collection("messages")
                .get()
                .await()
            snapshot.toObjects(
                HoloMessageDoc::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            throw e
        }
    }

    suspend fun sendMessage(
        peerId: String,
        senderId: String,
        senderLabel: String,
        text: String,
        messageType: String = "TEXT",
        status: String = "SENT",
        isFromMe: Boolean = true,
        isEncrypted: Boolean = true,
        mediaUri: String? = null,
        audioDurationMs: Long? = null,
        cipherHex: String? = null,
        modelBadge: String? = null
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("messages").document()
        val payload = mapOf<String, Any?>(
            "userId" to uid,
            "peerId" to peerId,
            "senderId" to senderId,
            "senderLabel" to senderLabel,
            "text" to text,
            "messageType" to messageType,
            "status" to status,
            "isFromMe" to isFromMe,
            "isEncrypted" to isEncrypted,
            "mediaUri" to mediaUri,
            "audioDurationMs" to audioDurationMs,
            "cipherHex" to cipherHex,
            "modelBadge" to modelBadge,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun updateMessageStatus(messageId: String, newStatus: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        val docRef = db.collection("users").document(uid).collection("messages").document(messageId)
        try {
            docRef.update(
                mapOf(
                    "status" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    fun observePeers(): Flow<List<HoloPeerPresenceDoc>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/peers"
        emitAll(
            db.collection("users").document(uid).collection("peers")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HoloPeerPresenceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertPeerPresence(
        peerId: String,
        displayName: String,
        presence: String,
        unreadCount: Long,
        lastMessagePreview: String,
        isAiFallbackEnabled: Boolean,
        e2eeFingerprint: String
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("peers").document(peerId)
        try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "peerId" to peerId,
                    "displayName" to displayName,
                    "presence" to presence,
                    "unreadCount" to unreadCount,
                    "lastMessagePreview" to lastMessagePreview,
                    "isAiFallbackEnabled" to isAiFallbackEnabled,
                    "e2eeFingerprint" to e2eeFingerprint,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "displayName" to displayName,
                    "presence" to presence,
                    "unreadCount" to unreadCount,
                    "lastMessagePreview" to lastMessagePreview,
                    "isAiFallbackEnabled" to isAiFallbackEnabled,
                    "e2eeFingerprint" to e2eeFingerprint,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    fun observeRtcSessions(): Flow<List<HoloRtcSessionDoc>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/rtc_sessions"
        emitAll(
            db.collection("users").document(uid).collection("rtc_sessions")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        HoloRtcSessionDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createRtcSession(
        peerId: String,
        callType: String,
        callState: String,
        sdpOffer: String,
        sdpAnswer: String = "",
        iceCandidatesCount: Long = 1L,
        latestIceCandidate: String = "candidate:1 1 UDP 2122252543 192.168.1.10 54321 typ host",
        isEncrypted: Boolean = true
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("rtc_sessions").document()
        val payload = mapOf(
            "userId" to uid,
            "peerId" to peerId,
            "callType" to callType,
            "callState" to callState,
            "sdpOffer" to sdpOffer,
            "sdpAnswer" to sdpAnswer,
            "iceCandidatesCount" to iceCandidatesCount,
            "latestIceCandidate" to latestIceCandidate,
            "isEncrypted" to isEncrypted,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun updateRtcSessionState(
        sessionId: String,
        callState: String,
        sdpAnswer: String? = null,
        iceCandidatesCount: Long? = null,
        latestIceCandidate: String? = null
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("rtc_sessions").document(sessionId)
        val updates = mutableMapOf<String, Any>(
            "callState" to callState,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (sdpAnswer != null) updates["sdpAnswer"] = sdpAnswer
        if (iceCandidatesCount != null) updates["iceCandidatesCount"] = iceCandidatesCount
        if (latestIceCandidate != null) updates["latestIceCandidate"] = latestIceCandidate

        try {
            docRef.update(updates).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }
}

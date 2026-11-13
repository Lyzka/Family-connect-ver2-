package com.example.ui.chat

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.GlobalOperatorPresenceDoc
import com.example.data.GlobalRoomMessageDoc
import com.example.data.GlobalRtcSignalDoc
import com.example.data.HoloCommRepository
import com.example.data.HoloMessageDoc
import com.example.model.PeerPresenceStatus
import com.example.util.E2eeSecurityVerification
import com.example.util.HoloE2eeCryptoEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.firestore.FirebaseFirestoreException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "HoloAiChatVM"

typealias HoloChatViewModel = HoloAiChatViewModel

enum class HoloAiModelTier(
    val modelId: String,
    val badgeLabel: String,
    val description: String
) {
    FAST(
        modelId = "gemini-3.5-flash-lite",
        badgeLabel = "FAST // 3.5-LITE",
        description = "Ultra-low latency tactical telemetry"
    ),
    GENERAL(
        modelId = "gemini-3.8-flash",
        badgeLabel = "GENERAL // 3.8-FLASH",
        description = "Balanced frontier holographic intelligence"
    ),
    COMPLEX(
        modelId = "gemini-4-argon",
        badgeLabel = "COMPLEX // 4-ARGON",
        description = "Deep STEM, orbital math & shader synthesis"
    )
}

enum class HoloMessageType {
    TEXT,
    VOICE_NOTE,
    IMAGE,
    FILE,
    CONTROL_SIGNAL,
    WEBRTC_OFFER,
    WEBRTC_ANSWER,
    WEBRTC_ICE
}

enum class HoloReceiptStatus {
    SENT,      // 1 cyan bar (|)
    DELIVERED, // 2 cyan bars (||)
    READ       // 2 glowing neon cyan bars (||)
}

data class HoloBotPersona(
    val contactId: String,
    val callSign: String,
    val roleTitle: String,
    val systemPrompt: String,
    val welcomeMessage: String,
    val quickPrompts: List<String>,
    val isRemoteHumanOperator: Boolean = false
)

data class HoloChatMessage(
    val id: String,
    val isUser: Boolean,
    val senderLabel: String,
    val text: String,
    val modelBadge: String,
    val timestampText: String = formatCurrentTime(),
    val messageType: HoloMessageType = HoloMessageType.TEXT,
    val receiptStatus: HoloReceiptStatus = HoloReceiptStatus.READ,
    val mediaUri: String? = null,
    val audioDurationMs: Long = 0L,
    val cipherHex: String? = null,
    val signalPayload: String? = null,
    val isEncrypted: Boolean = true,
    val isStreaming: Boolean = false,
    val isSyntheticGreeting: Boolean = false
)

private fun formatCurrentTime(epochMs: Long = System.currentTimeMillis()): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
}

sealed interface ChatGenerationState {
    data object Idle : ChatGenerationState
    data object Loading : ChatGenerationState
    data class Streaming(val partialText: String) : ChatGenerationState
    data class Error(val message: String) : ChatGenerationState
}

data class HoloAiChatUiState(
    val localOperatorUid: String = "local_operator",
    val activeContactId: String = "USER_01",
    val activeSharedRoomId: String = "USER_01_local_operator",
    val selectedTier: HoloAiModelTier = HoloAiModelTier.GENERAL,
    val isDirectP2pEncryptedMode: Boolean = false,
    val globalOnlineOperators: List<GlobalOperatorPresenceDoc> = emptyList(),
    val dynamicPersonas: Map<String, HoloBotPersona> = HOLO_BOT_PERSONAS,
    val threadsByContact: Map<String, List<HoloChatMessage>> = emptyMap(),
    val peerPresenceByContact: Map<String, PeerPresenceStatus> = mapOf(
        "USER_01" to PeerPresenceStatus.ONLINE,
        "USER_02" to PeerPresenceStatus.ONLINE,
        "USER_03" to PeerPresenceStatus.OFFLINE
    ),
    val unreadCountByContact: Map<String, Int> = mapOf(
        "USER_01" to 0,
        "USER_02" to 1,
        "USER_03" to 2
    ),
    val activeRtcSessionId: String? = null,
    val latestIncomingRtcSignal: GlobalRtcSignalDoc? = null,
    val latestRemoteCallState: String? = null,
    val latestRemoteCallType: String? = null,
    val remoteVideoFrameBase64: String? = null,
    val measuredPingMs: Int = 18,
    val generationState: ChatGenerationState = ChatGenerationState.Idle
) {
    val activePersona: HoloBotPersona
        get() = dynamicPersonas[activeContactId]
            ?: HOLO_BOT_PERSONAS[activeContactId]
            ?: HOLO_BOT_PERSONAS.values.first()

    val activeMessages: List<HoloChatMessage>
        get() = threadsByContact[activeContactId].orEmpty()

    val activePeerPresence: PeerPresenceStatus
        get() = peerPresenceByContact[activeContactId] ?: PeerPresenceStatus.ONLINE
}

val HOLO_BOT_PERSONAS: Map<String, HoloBotPersona> = mapOf(
    "USER_01" to HoloBotPersona(
        contactId = "USER_01",
        callSign = "USER_01 // VANGUARD",
        roleTitle = "ORBITAL RECON COMMANDER (SEC-09)",
        systemPrompt = """
            You are Commander Vanguard (USER_01), Orbital Reconnaissance & Tactical Navigation Officer aboard Deep-Space Relay Station Helios-9 in the year 2026.
            Your personality fuses crisp Star Trek LCARS analytical precision with gritty Cyberpunk orbital telemetry.
            Keep responses concise (under 100 words), structured, and immersive.
            Assist the operator with orbital ring trajectories, planetary point-cloud reconnaissance, comms link status, and tactical engineering.
        """.trimIndent(),
        welcomeMessage = "ORBITAL LINK ESTABLISHED [142.8 GHz]. Commander Vanguard (USER_01) online. Shared Firestore channel /global_comms_rooms active. Awaiting your tactical query, Operator.",
        quickPrompts = listOf(
            "Scan orbital ring stability",
            "Report Sector-09 anomalies",
            "Calculate geostationary lock"
        )
    ),
    "USER_02" to HoloBotPersona(
        contactId = "USER_02",
        callSign = "USER_02 // CIPHER",
        roleTitle = "DEEP-NET QUANTUM CRYPTOGRAPHER (SEC-14)",
        systemPrompt = """
            You are Specialist Cipher (USER_02), Chief Quantum Cryptographer & Neural-Grid Architect in Sector-14 Deep-Net.
            Your personality blends Matrix-style cyber-security authority with high-precision mathematical analysis.
            Assist the operator with quantum key distribution (AES-Q), signal waveform anomalies, zero-trust firewall defense, and neural-link diagnostics.
            Keep answers sharp, concise (under 100 words), and formatted for a holographic HUD.
        """.trimIndent(),
        welcomeMessage = "QUANTUM TUNNEL SYNCHRONIZED [288.4 GHz]. Specialist Cipher (USER_02) connected through hooded relay node. Signal waveform verified. Ready for cryptographic analysis.",
        quickPrompts = listOf(
            "Audit quantum encryption lock",
            "Analyze carrier frequency spikes",
            "Trace ghost packet origin"
        )
    ),
    "USER_03" to HoloBotPersona(
        contactId = "USER_03",
        callSign = "USER_03 // DR. KAELEN",
        roleTitle = "CITADEL CHIEF SCIENCE & SHADER ARCHITECT (SEC-03)",
        systemPrompt = """
            You are Dr. Kaelen (USER_03), Chief Science Officer & Holographic Physics Architect at Citadel Prime.
            You specialize in subspace telemetry, refractive Liquid Glass optics (IOR 1.52, chromatic aberration), Unreal Engine 6 Nanite/Lumen shaders, and Model Context Protocol (MCP) automation.
            Provide insightful, scientifically grounded, and concise (under 100 words) explanations formatted cleanly for a sci-fi holographic communicator.
        """.trimIndent(),
        welcomeMessage = "CITADEL SCIENCE CHANNEL OPEN [419.2 GHz]. Dr. Kaelen (USER_03) — AI Fallback active while Citadel node is in deep-space orbit. Refractive Liquid Glass matrices stable. How can I assist your research?",
        quickPrompts = listOf(
            "Explain Liquid Glass refraction",
            "Optimize icosahedron field mesh",
            "Generate MCP telemetry schema"
        )
    )
)

class HoloAiChatViewModel : ViewModel() {

    private var repository: HoloCommRepository? = null
    private var operatorUid: String = "local_operator"
    private var globalPresenceListenerJob: Job? = null
    private var sharedRoomMessagesJob: Job? = null
    private var sharedRoomRtcJob: Job? = null
    private val backgroundRemoteRoomJobs = mutableMapOf<String, Job>()
    private var hasAutoSelectedRemoteOperator: Boolean = false
    private var messagesListenerJob: Job? = null
    private var peersListenerJob: Job? = null

    private val _uiState = MutableStateFlow(
        HoloAiChatUiState(
            threadsByContact = HOLO_BOT_PERSONAS.mapValues { (contactId, persona) ->
                listOf(
                    HoloChatMessage(
                        id = "init_$contactId",
                        isUser = false,
                        senderLabel = persona.callSign,
                        text = persona.welcomeMessage,
                        modelBadge = "E2EE // AES-256-GCM",
                        receiptStatus = HoloReceiptStatus.READ,
                        isSyntheticGreeting = true
                    )
                )
            }
        )
    )
    val uiState: StateFlow<HoloAiChatUiState> = _uiState.asStateFlow()

    private val sharedGenConfig = generationConfig {
        temperature = 0.7f
        maxOutputTokens = 384
    }

    private val fastRequestOptions = RequestOptions(12_000L)

    private val fastModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = sharedGenConfig,
            requestOptions = fastRequestOptions
        )
    }

    private val generalModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = sharedGenConfig,
            requestOptions = fastRequestOptions
        )
    }

    private val complexModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-4-argon",
            generationConfig = sharedGenConfig,
            requestOptions = RequestOptions(18_000L)
        )
    }

    private fun modelForTier(tier: HoloAiModelTier): GenerativeModel = when (tier) {
        HoloAiModelTier.FAST -> fastModel
        HoloAiModelTier.GENERAL -> generalModel
        HoloAiModelTier.COMPLEX -> complexModel
    }

    /**
     * Generates a consistent deterministic `roomId` for any two operators or channels
     * by alphabetically sorting their IDs and joining with `_`.
     */
    fun computeRoomIdForPeer(peerId: String = _uiState.value.activeContactId): String {
        return HoloCommRepository.buildDeterministicRoomId(operatorUid, peerId)
    }

    /**
     * Measures real round-trip network latency (RTT in ms) against Cloud Firestore
     * by timing a live presence heartbeat update.
     */
    fun measureRealtimeFirestorePing(onMeasured: (Int) -> Unit = {}) {
        val repo = repository ?: return
        val uid = operatorUid
        if (uid == "local_operator" || uid.isBlank()) return
        val currentRoom = _uiState.value.activeSharedRoomId
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val selfVerification = HoloE2eeCryptoEngine.generateVerification(uid, "GLOBAL_MESH")
            val result = repo.updateGlobalOperatorPresence(
                presence = "ONLINE",
                e2eeFingerprint = selfVerification.keyFingerprintHex,
                statusMessage = "ROOM: $currentRoom"
            )
            if (result.isSuccess) {
                val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)
                _uiState.update { it.copy(measuredPingMs = rtt) }
                onMeasured(rtt)
            }
        }
    }

    /**
     * Attaches the authenticated Firestore repository, publishes the device's presence in
     * `/global_presence/{userId}`, observes all online devices in real time, and subscribes
     * to `/global_comms_rooms/{roomId}/messages` and `/rtc_signals`.
     */
    fun attachFirestoreRepository(repo: HoloCommRepository, uid: String) {
        if (this.repository === repo && this.operatorUid == uid && globalPresenceListenerJob?.isActive == true) {
            return
        }
        this.repository = repo
        this.operatorUid = uid

        val initialRoomId = HoloCommRepository.buildDeterministicRoomId(uid, _uiState.value.activeContactId)
        _uiState.update {
            it.copy(
                localOperatorUid = uid,
                activeSharedRoomId = initialRoomId
            )
        }

        globalPresenceListenerJob?.cancel()
        messagesListenerJob?.cancel()
        peersListenerJob?.cancel()
        backgroundRemoteRoomJobs.values.forEach { it.cancel() }
        backgroundRemoteRoomJobs.clear()

        // 1. Publish this device's ONLINE presence to /global_presence/{userId} and measure initial RTT
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val selfVerification = HoloE2eeCryptoEngine.generateVerification(uid, "GLOBAL_MESH")
            val res = repo.updateGlobalOperatorPresence(
                presence = "ONLINE",
                e2eeFingerprint = selfVerification.keyFingerprintHex,
                statusMessage = "ROOM: $initialRoomId"
            )
            if (res.isSuccess) {
                val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)
                _uiState.update { it.copy(measuredPingMs = rtt) }
            }

            // Seed user-scoped tactical peer docs for backwards compatibility
            HOLO_BOT_PERSONAS.forEach { (peerId, persona) ->
                val verification = HoloE2eeCryptoEngine.generateVerification(uid, peerId)
                val initialPresence = if (peerId == "USER_03") "OFFLINE" else "ONLINE"
                repo.upsertPeerPresence(
                    peerId = peerId,
                    displayName = persona.callSign,
                    presence = initialPresence,
                    unreadCount = (_uiState.value.unreadCountByContact[peerId] ?: 0).toLong(),
                    lastMessagePreview = persona.welcomeMessage.take(120),
                    isAiFallbackEnabled = true,
                    e2eeFingerprint = verification.keyFingerprintHex
                )
            }
        }

        // 2. Observe /global_presence in real time so Phone A and Phone B see each other online
        globalPresenceListenerJob = viewModelScope.launch {
            repo.observeGlobalActiveOperators()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Global presence stream warning: ${firestoreError?.message ?: error.message}")
                }
                .collect { operators ->
                    handleGlobalPresenceUpdate(operators)
                }
        }

        // 3. Subscribe to the active shared room /global_comms_rooms/{roomId}/messages & /rtc_signals
        subscribeToSharedCommsRoom(_uiState.value.activeContactId)

        // 4. Also observe user-scoped archive /users/{uid}/messages & /peers
        messagesListenerJob = viewModelScope.launch {
            repo.observeMessages()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Firestore message stream warning: ${firestoreError?.message ?: error.message}")
                }
                .collect { remoteDocs ->
                    if (remoteDocs.isEmpty()) return@collect
                    mergeRemoteFirestoreMessages(remoteDocs)
                }
        }

        peersListenerJob = viewModelScope.launch {
            repo.observePeers()
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Firestore peers stream warning: ${firestoreError?.message ?: error.message}")
                }
                .collect { peerDocs ->
                    if (peerDocs.isEmpty()) return@collect
                    _uiState.update { state ->
                        val nextPresence = state.peerPresenceByContact.toMutableMap()
                        val nextUnread = state.unreadCountByContact.toMutableMap()
                        peerDocs.forEach { doc ->
                            val status = when (doc.presence) {
                                "TYPING" -> PeerPresenceStatus.TYPING
                                "OFFLINE" -> PeerPresenceStatus.OFFLINE
                                else -> PeerPresenceStatus.ONLINE
                            }
                            nextPresence[doc.peerId] = status
                            if (doc.peerId != state.activeContactId) {
                                nextUnread[doc.peerId] = doc.unreadCount.toInt()
                            }
                        }
                        state.copy(
                            peerPresenceByContact = nextPresence,
                            unreadCountByContact = nextUnread
                        )
                    }
                }
        }
    }

    private fun handleGlobalPresenceUpdate(operators: List<GlobalOperatorPresenceDoc>) {
        val remoteOperators = operators.filter { it.userId.isNotBlank() && it.userId != operatorUid }

        _uiState.update { state ->
            val updatedPersonas = HOLO_BOT_PERSONAS.toMutableMap()
            val updatedPresence = state.peerPresenceByContact.toMutableMap()
            val updatedThreads = state.threadsByContact.toMutableMap()

            remoteOperators.forEach { remoteOp ->
                val remoteId = remoteOp.userId
                val remoteCallSign = remoteOp.callSign.ifBlank { "OPR // ${remoteOp.displayName.uppercase()}" }
                val sharedRoom = HoloCommRepository.buildDeterministicRoomId(operatorUid, remoteId)
                updatedPersonas[remoteId] = HoloBotPersona(
                    contactId = remoteId,
                    callSign = remoteCallSign,
                    roleTitle = "LIVE REMOTE OPERATOR • ROOM [$sharedRoom]",
                    systemPrompt = "You are tactical relay fallback for remote operator $remoteCallSign.",
                    welcomeMessage = "DIRECT P2P ROOM [$sharedRoom] ESTABLISHED WITH $remoteCallSign (${remoteOp.deviceModel ?: "REMOTE DEVICE"}). E2EE FINGERPRINT: ${remoteOp.e2eeFingerprint}",
                    quickPrompts = listOf(
                        "PING // CONFIRM P2P LINK",
                        "REQUEST HOLO-VIDEO STREAM",
                        "SYNC TELEMETRY COORDS"
                    ),
                    isRemoteHumanOperator = true
                )
                updatedPresence[remoteId] = when (remoteOp.presence) {
                    "TYPING" -> PeerPresenceStatus.TYPING
                    "OFFLINE" -> PeerPresenceStatus.OFFLINE
                    else -> PeerPresenceStatus.ONLINE
                }
                if (!updatedThreads.containsKey(remoteId)) {
                    updatedThreads[remoteId] = listOf(
                        HoloChatMessage(
                            id = "init_$remoteId",
                            isUser = false,
                            senderLabel = remoteCallSign,
                            text = "DIRECT P2P ROOM [$sharedRoom] ESTABLISHED WITH $remoteCallSign. Ready for cross-device messaging & WebRTC signaling.",
                            modelBadge = "GLOBAL P2P // E2EE",
                            receiptStatus = HoloReceiptStatus.READ,
                            isSyntheticGreeting = true
                        )
                    )
                }
            }

            state.copy(
                globalOnlineOperators = operators,
                dynamicPersonas = updatedPersonas,
                peerPresenceByContact = updatedPresence,
                threadsByContact = updatedThreads
            )
        }

        // Ensure background shared-room listeners are active for all online remote devices (up to 6 peers)
        val repo = repository ?: return
        remoteOperators.take(6).forEach { remoteOp ->
            val remoteId = remoteOp.userId
            if (backgroundRemoteRoomJobs[remoteId]?.isActive != true) {
                val roomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, remoteId)
                backgroundRemoteRoomJobs[remoteId] = viewModelScope.launch {
                    launch {
                        repo.observeSharedRoomMessages(roomId)
                            .catch { /* handled safely */ }
                            .collect { roomDocs ->
                                if (roomDocs.isNotEmpty()) {
                                    mergeSharedRoomMessages(remoteId, roomId, roomDocs)
                                }
                            }
                    }
                    launch {
                        repo.observeSharedRtcSignals(roomId)
                            .catch { /* handled safely */ }
                            .collect { signals ->
                                processIncomingRtcSignals(signals)
                            }
                    }
                }
            }
        }

        // Auto-select the first live remote operator on initial discovery so Phone A & Phone B link immediately
        val firstOnlineHuman = remoteOperators.firstOrNull { it.presence != "OFFLINE" } ?: remoteOperators.firstOrNull()
        if (firstOnlineHuman != null && !hasAutoSelectedRemoteOperator && HOLO_BOT_PERSONAS.containsKey(_uiState.value.activeContactId)) {
            hasAutoSelectedRemoteOperator = true
            selectContact(firstOnlineHuman.userId)
        }
    }

    private fun processIncomingRtcSignals(signals: List<GlobalRtcSignalDoc>) {
        if (signals.isEmpty()) return
        val latestSignal = signals.first()
        val latestRemoteOffer = signals.firstOrNull {
            it.senderId != operatorUid &&
                (it.signalType == "SDP_OFFER" || it.signalType == "RINGING")
        }
        val latestRemoteFrame = signals.firstOrNull {
            it.senderId != operatorUid && it.sdpPayload.startsWith("FRAME_JPEG::")
        }?.sdpPayload?.removePrefix("FRAME_JPEG::")

        _uiState.update { state ->
            state.copy(
                latestIncomingRtcSignal = if (latestSignal.signalType == "ENDED" || latestSignal.signalType == "CONNECTED") {
                    null
                } else {
                    latestRemoteOffer ?: state.latestIncomingRtcSignal
                },
                latestRemoteCallState = latestSignal.signalType,
                latestRemoteCallType = latestSignal.callType,
                remoteVideoFrameBase64 = if (latestSignal.signalType == "ENDED") {
                    null
                } else {
                    latestRemoteFrame ?: state.remoteVideoFrameBase64
                }
            )
        }
    }

    private fun subscribeToSharedCommsRoom(contactId: String) {
        val repo = repository ?: return
        val roomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, contactId)

        sharedRoomMessagesJob?.cancel()
        sharedRoomRtcJob?.cancel()

        _uiState.update { it.copy(activeSharedRoomId = roomId) }

        // Observe shared room messages (/global_comms_rooms/{roomId}/messages)
        sharedRoomMessagesJob = viewModelScope.launch {
            repo.observeSharedRoomMessages(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Shared room [$roomId] messages warning: ${firestoreError?.message ?: error.message}")
                }
                .collect { roomDocs ->
                    if (roomDocs.isEmpty()) return@collect
                    mergeSharedRoomMessages(contactId, roomId, roomDocs)
                }
        }

        // Observe shared room WebRTC signals (/global_comms_rooms/{roomId}/rtc_signals)
        sharedRoomRtcJob = viewModelScope.launch {
            repo.observeSharedRtcSignals(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Shared room [$roomId] WebRTC warning: ${firestoreError?.message ?: error.message}")
                }
                .collect { signals ->
                    processIncomingRtcSignals(signals)
                }
        }
    }

    private fun mergeSharedRoomMessages(
        contactId: String,
        roomId: String,
        roomDocs: List<GlobalRoomMessageDoc>
    ) {
        val repo = repository
        val isCurrentlySelected = _uiState.value.activeContactId == contactId
        // Automatically mark incoming unread messages from remote device as READ when viewing this contact
        if (repo != null && isCurrentlySelected) {
            viewModelScope.launch(Dispatchers.IO) {
                roomDocs.filter { it.senderId != operatorUid && it.status != "READ" && it.id.isNotBlank() }
                    .forEach { unreadDoc ->
                        repo.updateSharedRoomMessageStatus(roomId, unreadDoc.id, "READ")
                    }
            }
        }

        _uiState.update { state ->
            val greeting = state.threadsByContact[contactId]?.firstOrNull { it.isSyntheticGreeting }
            val mapped = roomDocs.map { doc ->
                val tsMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                val isFromLocalUser = doc.senderId == operatorUid && doc.recipientId != operatorUid
                val decryptedText = if (!doc.cipherHex.isNullOrBlank() && !doc.cipherHex.startsWith("GCM[")) {
                    HoloE2eeCryptoEngine.decryptMessage(
                        cipherBase64 = doc.cipherHex,
                        operatorUid = operatorUid,
                        peerId = contactId,
                        fallbackPlain = doc.text
                    )
                } else {
                    doc.text
                }
                HoloChatMessage(
                    id = doc.id.ifBlank { "room_doc_$tsMillis" },
                    isUser = isFromLocalUser,
                    senderLabel = doc.senderLabel,
                    text = decryptedText,
                    modelBadge = doc.modelBadge ?: "ROOM [$roomId]",
                    timestampText = formatCurrentTime(tsMillis),
                    messageType = runCatching { HoloMessageType.valueOf(doc.messageType) }
                        .getOrDefault(HoloMessageType.TEXT),
                    receiptStatus = runCatching { HoloReceiptStatus.valueOf(doc.status) }
                        .getOrDefault(HoloReceiptStatus.SENT),
                    mediaUri = doc.mediaUri,
                    audioDurationMs = doc.audioDurationMs ?: 0L,
                    cipherHex = doc.cipherHex,
                    signalPayload = doc.signalPayload,
                    isEncrypted = doc.isEncrypted,
                    isStreaming = false,
                    isSyntheticGreeting = false
                )
            }
            val streamingBubble = state.threadsByContact[contactId]?.lastOrNull { it.isStreaming }
            val merged = buildList {
                if (greeting != null) add(greeting)
                addAll(mapped)
                if (streamingBubble != null) add(streamingBubble)
            }
            val unreadRemoteCount = if (state.activeContactId == contactId) {
                0
            } else {
                roomDocs.count { it.senderId != operatorUid && it.status != "READ" }
            }
            state.copy(
                threadsByContact = state.threadsByContact + (contactId to merged),
                unreadCountByContact = state.unreadCountByContact + (contactId to unreadRemoteCount)
            )
        }
    }

    private fun mergeRemoteFirestoreMessages(remoteDocs: List<HoloMessageDoc>) {
        val grouped = remoteDocs
            .sortedBy { it.createdAt?.toDate()?.time ?: 0L }
            .groupBy { it.peerId }

        _uiState.update { state ->
            val updatedThreads = state.threadsByContact.toMutableMap()
            state.dynamicPersonas.keys.forEach { contactId ->
                val remoteForContact = grouped[contactId].orEmpty()
                if (remoteForContact.isNotEmpty() && contactId != state.activeContactId) {
                    val greeting = state.threadsByContact[contactId]
                        ?.firstOrNull { it.isSyntheticGreeting }
                    val mapped = remoteForContact.map { doc ->
                        val tsMillis = doc.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                        val decryptedText = if (!doc.cipherHex.isNullOrBlank() && !doc.cipherHex.startsWith("GCM[")) {
                            HoloE2eeCryptoEngine.decryptMessage(
                                cipherBase64 = doc.cipherHex,
                                operatorUid = operatorUid,
                                peerId = contactId,
                                fallbackPlain = doc.text
                            )
                        } else {
                            doc.text
                        }
                        HoloChatMessage(
                            id = doc.id.ifBlank { "doc_${tsMillis}" },
                            isUser = doc.isFromMe,
                            senderLabel = doc.senderLabel,
                            text = decryptedText,
                            modelBadge = doc.modelBadge ?: "P2P // E2EE",
                            timestampText = formatCurrentTime(tsMillis),
                            messageType = runCatching { HoloMessageType.valueOf(doc.messageType) }
                                .getOrDefault(HoloMessageType.TEXT),
                            receiptStatus = runCatching { HoloReceiptStatus.valueOf(doc.status) }
                                .getOrDefault(HoloReceiptStatus.SENT),
                            mediaUri = doc.mediaUri,
                            audioDurationMs = doc.audioDurationMs ?: 0L,
                            cipherHex = doc.cipherHex,
                            isEncrypted = doc.isEncrypted,
                            isStreaming = false,
                            isSyntheticGreeting = false
                        )
                    }
                    val streamingBubble = state.threadsByContact[contactId]?.lastOrNull { it.isStreaming }
                    val merged = buildList {
                        if (greeting != null) add(greeting)
                        addAll(mapped)
                        if (streamingBubble != null) add(streamingBubble)
                    }
                    updatedThreads[contactId] = merged
                }
            }
            state.copy(threadsByContact = updatedThreads)
        }
    }

    fun getSecurityVerification(peerId: String = _uiState.value.activeContactId): E2eeSecurityVerification {
        return HoloE2eeCryptoEngine.generateVerification(operatorUid, peerId)
    }

    fun selectContact(contactId: String) {
        val knownPersona = _uiState.value.dynamicPersonas[contactId] ?: HOLO_BOT_PERSONAS[contactId]
        if (knownPersona != null) {
            val newRoomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, contactId)
            _uiState.update { state ->
                val nextUnread = state.unreadCountByContact + (contactId to 0)
                val updatedThread = state.threadsByContact[contactId].orEmpty().map { msg ->
                    if (msg.receiptStatus != HoloReceiptStatus.READ) {
                        msg.copy(receiptStatus = HoloReceiptStatus.READ)
                    } else {
                        msg
                    }
                }
                state.copy(
                    activeContactId = contactId,
                    activeSharedRoomId = newRoomId,
                    unreadCountByContact = nextUnread,
                    threadsByContact = state.threadsByContact + (contactId to updatedThread),
                    generationState = ChatGenerationState.Idle
                )
            }
            subscribeToSharedCommsRoom(contactId)

            val repo = repository
            if (repo != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val t0 = SystemClock.elapsedRealtime()
                    val verification = HoloE2eeCryptoEngine.generateVerification(operatorUid, contactId)
                    val res = repo.updateGlobalOperatorPresence(
                        presence = "ONLINE",
                        e2eeFingerprint = verification.keyFingerprintHex,
                        statusMessage = "ROOM: $newRoomId"
                    )
                    if (res.isSuccess) {
                        val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)
                        _uiState.update { it.copy(measuredPingMs = rtt) }
                    }
                    if (HOLO_BOT_PERSONAS.containsKey(contactId)) {
                        val lastMsg = _uiState.value.threadsByContact[contactId]?.lastOrNull()?.text ?: knownPersona.welcomeMessage
                        val presence = _uiState.value.peerPresenceByContact[contactId]?.name ?: "ONLINE"
                        repo.upsertPeerPresence(
                            peerId = contactId,
                            displayName = knownPersona.callSign,
                            presence = presence,
                            unreadCount = 0L,
                            lastMessagePreview = lastMsg.take(120),
                            isAiFallbackEnabled = true,
                            e2eeFingerprint = verification.keyFingerprintHex
                        )
                    }
                }
            }
        }
    }

    fun selectModelTier(tier: HoloAiModelTier) {
        _uiState.update { it.copy(selectedTier = tier) }
    }

    fun setDirectP2pEncryptedMode(enabled: Boolean) {
        _uiState.update { it.copy(isDirectP2pEncryptedMode = enabled) }
    }

    fun togglePeerOnlineStatus(peerId: String = _uiState.value.activeContactId) {
        val current = _uiState.value.peerPresenceByContact[peerId] ?: PeerPresenceStatus.ONLINE
        val next = if (current == PeerPresenceStatus.OFFLINE) {
            PeerPresenceStatus.ONLINE
        } else {
            PeerPresenceStatus.OFFLINE
        }
        setPeerPresence(peerId, next)
    }

    private fun setPeerPresence(peerId: String, status: PeerPresenceStatus) {
        _uiState.update { state ->
            state.copy(peerPresenceByContact = state.peerPresenceByContact + (peerId to status))
        }
        val repo = repository
        val persona = _uiState.value.dynamicPersonas[peerId] ?: HOLO_BOT_PERSONAS[peerId]
        if (repo != null && persona != null && HOLO_BOT_PERSONAS.containsKey(peerId)) {
            viewModelScope.launch(Dispatchers.IO) {
                val verification = HoloE2eeCryptoEngine.generateVerification(operatorUid, peerId)
                val unread = (_uiState.value.unreadCountByContact[peerId] ?: 0).toLong()
                val lastPreview = _uiState.value.threadsByContact[peerId]?.lastOrNull()?.text ?: persona.welcomeMessage
                repo.upsertPeerPresence(
                    peerId = peerId,
                    displayName = persona.callSign,
                    presence = status.name,
                    unreadCount = unread,
                    lastMessagePreview = lastPreview.take(120),
                    isAiFallbackEnabled = true,
                    e2eeFingerprint = verification.keyFingerprintHex
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(generationState = ChatGenerationState.Idle) }
    }

    // ================= WEBRTC SIGNALING & LIVE FRAME STREAMING IN SHARED ROOM =================

    fun startWebRtcSignaling(
        peerId: String,
        isVideo: Boolean,
        sdpOffer: String,
        sdpAnswer: String,
        iceCandidates: List<String>
    ) {
        val repo = repository ?: return
        val roomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, peerId)
        val incomingOffer = _uiState.value.latestIncomingRtcSignal
        val isAnsweringRemoteOffer = incomingOffer != null && incomingOffer.senderId != operatorUid
        val isAiStationChannel = HOLO_BOT_PERSONAS.containsKey(peerId)

        viewModelScope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            if (isAnsweringRemoteOffer && incomingOffer != null && incomingOffer.id.isNotBlank()) {
                // Phone B is answering Phone A's incoming call: update existing signal to CONNECTED with SDP_ANSWER
                repo.updateSharedRtcSignalState(
                    roomId = roomId,
                    signalId = incomingOffer.id,
                    signalType = "CONNECTED",
                    sdpPayload = sdpAnswer,
                    iceCandidate = iceCandidates.firstOrNull().orEmpty(),
                    iceCandidatesCount = iceCandidates.size.toLong()
                )
                repo.sendSharedRoomMessage(
                    roomId = roomId,
                    recipientId = peerId,
                    senderLabel = "OPERATOR-${operatorUid.take(4).uppercase()}",
                    text = "WEBRTC ${if (isVideo) "VIDEO" else "AUDIO"} SDP_ANSWER // CONNECTED [$roomId]",
                    messageType = HoloMessageType.WEBRTC_ANSWER.name,
                    status = HoloReceiptStatus.READ.name,
                    isEncrypted = true,
                    modelBadge = "WebRTC // SRTP",
                    signalPayload = sdpAnswer
                )
                val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)
                _uiState.update {
                    it.copy(
                        activeRtcSessionId = incomingOffer.id,
                        latestIncomingRtcSignal = null,
                        latestRemoteCallState = "CONNECTED",
                        latestRemoteCallType = if (isVideo) "VIDEO" else "AUDIO",
                        measuredPingMs = rtt
                    )
                }
                return@launch
            }

            // Initiating a new outgoing call: publish SDP_OFFER to /global_comms_rooms/{roomId}/rtc_signals
            val sharedSignalRes = repo.sendSharedRtcSignal(
                roomId = roomId,
                recipientId = peerId,
                callType = if (isVideo) "VIDEO" else "AUDIO",
                signalType = "SDP_OFFER",
                sdpPayload = sdpOffer,
                iceCandidate = iceCandidates.firstOrNull().orEmpty(),
                iceCandidatesCount = iceCandidates.size.toLong(),
                isEncrypted = true
            )
            val sharedSignalId = sharedSignalRes.getOrNull()
            val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)

            repo.sendSharedRoomMessage(
                roomId = roomId,
                recipientId = peerId,
                senderLabel = "OPERATOR-${operatorUid.take(4).uppercase()}",
                text = "WEBRTC ${if (isVideo) "VIDEO" else "AUDIO"} SDP_OFFER // ROOM [$roomId]",
                messageType = HoloMessageType.WEBRTC_OFFER.name,
                status = HoloReceiptStatus.SENT.name,
                isEncrypted = true,
                modelBadge = "WebRTC // SRTP",
                signalPayload = sdpOffer
            )

            val createRes = repo.createRtcSession(
                peerId = peerId,
                callType = if (isVideo) "VIDEO" else "AUDIO",
                callState = "OFFER",
                sdpOffer = sdpOffer,
                sdpAnswer = "",
                iceCandidatesCount = iceCandidates.size.toLong(),
                latestIceCandidate = iceCandidates.firstOrNull().orEmpty(),
                isEncrypted = true
            )
            val sessionId = sharedSignalId ?: createRes.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(
                    activeRtcSessionId = sessionId,
                    measuredPingMs = rtt
                )
            }

            // If calling an AI Station Channel (USER_01..USER_03), transition to CONNECTED once Firestore confirms the session write.
            // If calling a live remote human operator (Phone B), remain in SDP_OFFER until Phone B answers!
            if (isAiStationChannel && sharedSignalId != null) {
                repo.updateSharedRtcSignalState(
                    roomId = roomId,
                    signalId = sharedSignalId,
                    signalType = "CONNECTED",
                    sdpPayload = sdpAnswer,
                    iceCandidate = iceCandidates.lastOrNull().orEmpty(),
                    iceCandidatesCount = iceCandidates.size.toLong()
                )
                createRes.getOrNull()?.let { userSessionId ->
                    repo.updateRtcSessionState(
                        sessionId = userSessionId,
                        callState = "CONNECTED",
                        sdpAnswer = sdpAnswer,
                        iceCandidatesCount = iceCandidates.size.toLong(),
                        latestIceCandidate = iceCandidates.lastOrNull().orEmpty()
                    )
                }
            }
        }
    }

    /**
     * Streams a real compressed JPEG frame from CameraX (`ImageAnalysis`) to the remote peer
     * via `/global_comms_rooms/{roomId}/rtc_signals` during an active video call.
     */
    fun publishLiveVideoFrame(peerId: String = _uiState.value.activeContactId, base64JpegFrame: String) {
        val repo = repository ?: return
        val sessionId = _uiState.value.activeRtcSessionId ?: return
        if (base64JpegFrame.isBlank()) return
        val roomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, peerId)
        viewModelScope.launch(Dispatchers.IO) {
            repo.updateSharedRtcSignalState(
                roomId = roomId,
                signalId = sessionId,
                signalType = "CONNECTED",
                sdpPayload = "FRAME_JPEG::$base64JpegFrame"
            )
        }
    }

    fun endWebRtcSignaling() {
        val sessionId = _uiState.value.activeRtcSessionId ?: _uiState.value.latestIncomingRtcSignal?.id
        val repo = repository ?: return
        val roomId = _uiState.value.activeSharedRoomId
        _uiState.update {
            it.copy(
                activeRtcSessionId = null,
                latestIncomingRtcSignal = null,
                latestRemoteCallState = "ENDED",
                remoteVideoFrameBase64 = null
            )
        }
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                repo.updateSharedRtcSignalState(
                    roomId = roomId,
                    signalId = sessionId,
                    signalType = "ENDED"
                )
                repo.updateRtcSessionState(
                    sessionId = sessionId,
                    callState = "ENDED"
                )
            }
        }
    }

    // ================= VOICE NOTES, MEDIA & CONTROL SIGNALS =================

    fun sendControlSignal(command: String, payload: String = "") {
        val summary = if (payload.isNotBlank()) "CTRL // $command [$payload]" else "CTRL // $command"
        dispatchOutgoingMessage(
            text = summary,
            messageType = HoloMessageType.CONTROL_SIGNAL,
            mediaUri = null,
            audioDurationMs = 0L,
            signalPayload = payload.ifBlank { command }
        )
    }

    fun sendVoiceNote(audioFilePath: String, durationMs: Long, base64WavPayload: String? = null) {
        val secs = (durationMs / 1000L).coerceAtLeast(1L)
        val caption = "VOICE TRANSMISSION [00:%02d // 8kHz PCM-WAV]".format(secs)
        dispatchOutgoingMessage(
            text = caption,
            messageType = HoloMessageType.VOICE_NOTE,
            mediaUri = audioFilePath,
            audioDurationMs = durationMs,
            signalPayload = base64WavPayload
        )
    }

    fun sendMediaAttachment(
        mediaUriString: String,
        label: String,
        isImage: Boolean = true,
        base64ImagePayload: String? = null
    ) {
        val caption = if (isImage) {
            "HOLO-IMAGE TELEMETRY // $label"
        } else {
            "ENCRYPTED DATA PACKET // $label"
        }
        dispatchOutgoingMessage(
            text = caption,
            messageType = if (isImage) HoloMessageType.IMAGE else HoloMessageType.FILE,
            mediaUri = mediaUriString,
            audioDurationMs = 0L,
            signalPayload = base64ImagePayload
        )
    }

    fun sendMessage(userPrompt: String) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return
        dispatchOutgoingMessage(
            text = trimmed,
            messageType = HoloMessageType.TEXT,
            mediaUri = null,
            audioDurationMs = 0L
        )
    }

    private fun dispatchOutgoingMessage(
        text: String,
        messageType: HoloMessageType,
        mediaUri: String?,
        audioDurationMs: Long,
        signalPayload: String? = null
    ) {
        if (_uiState.value.generationState is ChatGenerationState.Loading ||
            _uiState.value.generationState is ChatGenerationState.Streaming
        ) {
            return
        }

        val currentState = _uiState.value
        val contactId = currentState.activeContactId
        val roomId = HoloCommRepository.buildDeterministicRoomId(operatorUid, contactId)
        val tier = currentState.selectedTier
        val persona = currentState.activePersona
        val peerPresence = currentState.peerPresenceByContact[contactId] ?: PeerPresenceStatus.ONLINE
        val historyBeforeSend = currentState.threadsByContact[contactId].orEmpty()

        val encrypted = HoloE2eeCryptoEngine.encryptMessage(text, operatorUid, contactId)
        val storedCipher = encrypted.cipherBase64.ifBlank { encrypted.cipherHexPreview }
        val userMsgId = "user_${System.currentTimeMillis()}"
        val transportBadge = if (persona.isRemoteHumanOperator || (currentState.isDirectP2pEncryptedMode && peerPresence != PeerPresenceStatus.OFFLINE)) {
            "ROOM [$roomId] // E2EE"
        } else {
            "${tier.badgeLabel} // E2EE"
        }

        val userMessage = HoloChatMessage(
            id = userMsgId,
            isUser = true,
            senderLabel = "OPERATOR",
            text = text,
            modelBadge = transportBadge,
            timestampText = formatCurrentTime(),
            messageType = messageType,
            receiptStatus = HoloReceiptStatus.SENT,
            mediaUri = mediaUri,
            audioDurationMs = audioDurationMs,
            cipherHex = storedCipher,
            signalPayload = signalPayload,
            isEncrypted = true
        )

        // If talking directly to another live logged-in human phone (and they are online), do not inject an AI bot reply
        val shouldGenerateAiReply = !persona.isRemoteHumanOperator || peerPresence == PeerPresenceStatus.OFFLINE

        val botMessageId = "bot_${System.currentTimeMillis() + 1}"
        val placeholderBotMessage = HoloChatMessage(
            id = botMessageId,
            isUser = false,
            senderLabel = persona.callSign,
            text = "",
            modelBadge = if (peerPresence == PeerPresenceStatus.OFFLINE) "${tier.badgeLabel} // AI BOT" else transportBadge,
            timestampText = formatCurrentTime(),
            receiptStatus = HoloReceiptStatus.READ,
            isStreaming = true
        )

        _uiState.update { state ->
            val baseList = state.threadsByContact[contactId].orEmpty() + userMessage
            val updatedList = if (shouldGenerateAiReply) baseList + placeholderBotMessage else baseList
            state.copy(
                threadsByContact = state.threadsByContact + (contactId to updatedList),
                generationState = if (shouldGenerateAiReply) ChatGenerationState.Loading else ChatGenerationState.Idle
            )
        }

        viewModelScope.launch {
            val repo = repository
            var sharedRoomMsgId: String? = null
            var firestoreUserMsgId: String? = null

            // 1. Persist outgoing message to shared multi-device channel /global_comms_rooms/{roomId}/messages
            if (repo != null) {
                val t0 = SystemClock.elapsedRealtime()
                sharedRoomMsgId = withContext(Dispatchers.IO) {
                    repo.sendSharedRoomMessage(
                        roomId = roomId,
                        recipientId = contactId,
                        senderLabel = "OPERATOR-${operatorUid.take(4).uppercase()}",
                        text = text,
                        messageType = messageType.name,
                        status = HoloReceiptStatus.SENT.name,
                        isEncrypted = true,
                        mediaUri = mediaUri,
                        audioDurationMs = if (audioDurationMs > 0) audioDurationMs else null,
                        cipherHex = storedCipher,
                        modelBadge = transportBadge,
                        signalPayload = signalPayload
                    ).getOrNull()
                }
                if (sharedRoomMsgId != null) {
                    val rtt = (SystemClock.elapsedRealtime() - t0).toInt().coerceIn(2, 999)
                    _uiState.update { it.copy(measuredPingMs = rtt) }
                }

                // Also persist to user-scoped archive if standard message type
                if (messageType in listOf(HoloMessageType.TEXT, HoloMessageType.VOICE_NOTE, HoloMessageType.IMAGE, HoloMessageType.FILE)) {
                    firestoreUserMsgId = withContext(Dispatchers.IO) {
                        repo.sendMessage(
                            peerId = contactId,
                            senderId = operatorUid,
                            senderLabel = "OPERATOR",
                            text = text,
                            messageType = messageType.name,
                            status = HoloReceiptStatus.SENT.name,
                            isFromMe = true,
                            isEncrypted = true,
                            mediaUri = mediaUri,
                            audioDurationMs = if (audioDurationMs > 0) audioDurationMs else null,
                            cipherHex = storedCipher,
                            modelBadge = transportBadge
                        ).getOrNull()
                    }
                }
            }

            // Transition receipt SENT -> DELIVERED once Firestore write completes
            if (sharedRoomMsgId != null || firestoreUserMsgId != null) {
                updateLocalMessageReceipt(contactId, userMsgId, HoloReceiptStatus.DELIVERED)
                if (repo != null) {
                    withContext(Dispatchers.IO) {
                        if (sharedRoomMsgId != null) {
                            repo.updateSharedRoomMessageStatus(roomId, sharedRoomMsgId, HoloReceiptStatus.DELIVERED.name)
                        }
                        if (firestoreUserMsgId != null) {
                            repo.updateMessageStatus(firestoreUserMsgId, HoloReceiptStatus.DELIVERED.name)
                        }
                    }
                }
            }

            if (!shouldGenerateAiReply) {
                return@launch
            }

            // Set peer presence to TYPING... while formulating response
            val previousPresence = _uiState.value.peerPresenceByContact[contactId] ?: PeerPresenceStatus.ONLINE
            setPeerPresence(contactId, PeerPresenceStatus.TYPING)

            updateLocalMessageReceipt(contactId, userMsgId, HoloReceiptStatus.READ)
            if (repo != null) {
                withContext(Dispatchers.IO) {
                    if (sharedRoomMsgId != null) {
                        repo.updateSharedRoomMessageStatus(roomId, sharedRoomMsgId, HoloReceiptStatus.READ.name)
                    }
                    if (firestoreUserMsgId != null) {
                        repo.updateMessageStatus(firestoreUserMsgId, HoloReceiptStatus.READ.name)
                    }
                }
            }

            val validHistory = buildValidAlternatingHistory(persona, historyBeforeSend)
            val promptForAi = when (messageType) {
                HoloMessageType.VOICE_NOTE ->
                    "[Operator transmitted a ${audioDurationMs / 1000}s encrypted voice note]. Acknowledge voice telemetry and respond in character."
                HoloMessageType.IMAGE ->
                    "[Operator transmitted a holographic image attachment: $text]. Acknowledge visual scan and respond in character."
                HoloMessageType.FILE ->
                    "[Operator transmitted an encrypted data file: $text]. Confirm packet checksum and respond in character."
                HoloMessageType.CONTROL_SIGNAL,
                HoloMessageType.WEBRTC_OFFER,
                HoloMessageType.WEBRTC_ANSWER,
                HoloMessageType.WEBRTC_ICE ->
                    "[Operator transmitted control signal: $text]. Confirm signal lock in character."
                HoloMessageType.TEXT -> text
            }

            try {
                val responseText = withContext(Dispatchers.IO) {
                    withTimeout(28_000L) {
                        try {
                            val primaryModel = modelForTier(tier)
                            val chat: Chat = primaryModel.startChat(history = validHistory)
                            chat.sendMessage(promptForAi).text.orEmpty().trim()
                        } catch (primaryEx: Exception) {
                            if (primaryEx is CancellationException && primaryEx !is TimeoutCancellationException) {
                                throw primaryEx
                            }
                            if (tier != HoloAiModelTier.FAST) {
                                Log.w(TAG, "Primary tier ${tier.modelId} delayed (${primaryEx.message}); routing via gemini-3.5-flash-lite")
                                val fallbackChat = fastModel.startChat(history = validHistory)
                                fallbackChat.sendMessage(promptForAi).text.orEmpty().trim()
                            } else {
                                throw primaryEx
                            }
                        }
                    }
                }

                if (responseText.isBlank()) {
                    throw IllegalStateException("Pusta odpowiedź z węzła Firebase AI Logic (${tier.modelId}).")
                }

                val replyBadge = when {
                    previousPresence == PeerPresenceStatus.OFFLINE -> "${tier.badgeLabel} // AI FALLBACK"
                    currentState.isDirectP2pEncryptedMode -> "ROOM [$roomId] // E2EE"
                    else -> tier.badgeLabel
                }

                finalizeBotMessage(contactId, botMessageId, responseText, replyBadge, previousPresence)
                persistPeerReplyToFirestore(contactId, roomId, persona, responseText, replyBadge, previousPresence)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    handleAiGenerationFailure(
                        contactId = contactId,
                        botMessageId = botMessageId,
                        restoredPresence = previousPresence,
                        errorMessage = "Przekroczono czas oczekiwania na odpowiedź modelu ${tier.modelId}."
                    )
                } else {
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firebase AI Logic error (${e.javaClass.simpleName}): ${e.message}")
                handleAiGenerationFailure(
                    contactId = contactId,
                    botMessageId = botMessageId,
                    restoredPresence = previousPresence,
                    errorMessage = "Błąd połączenia z Firebase AI Logic: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun handleAiGenerationFailure(
        contactId: String,
        botMessageId: String,
        restoredPresence: PeerPresenceStatus,
        errorMessage: String
    ) {
        _uiState.update { state ->
            val cleanedThread = state.threadsByContact[contactId]
                .orEmpty()
                .filterNot { it.id == botMessageId }
            state.copy(
                threadsByContact = state.threadsByContact + (contactId to cleanedThread),
                peerPresenceByContact = state.peerPresenceByContact + (contactId to restoredPresence),
                generationState = ChatGenerationState.Error(errorMessage)
            )
        }
    }

    private fun updateLocalMessageReceipt(
        contactId: String,
        messageId: String,
        newStatus: HoloReceiptStatus
    ) {
        _uiState.update { state ->
            val updated = state.threadsByContact[contactId].orEmpty().map { msg ->
                if (msg.id == messageId) msg.copy(receiptStatus = newStatus) else msg
            }
            state.copy(threadsByContact = state.threadsByContact + (contactId to updated))
        }
    }

    private suspend fun persistPeerReplyToFirestore(
        contactId: String,
        roomId: String,
        persona: HoloBotPersona,
        replyText: String,
        badgeLabel: String,
        restoredPresence: PeerPresenceStatus
    ) {
        val repo = repository ?: return
        withContext(Dispatchers.IO) {
            val encrypted = HoloE2eeCryptoEngine.encryptMessage(replyText, operatorUid, contactId)
            val storedCipher = encrypted.cipherBase64.ifBlank { encrypted.cipherHexPreview }
            // Write reply to shared room (/global_comms_rooms/{roomId}/messages)
            repo.sendSharedRoomMessage(
                roomId = roomId,
                recipientId = operatorUid,
                senderLabel = persona.callSign,
                text = replyText,
                messageType = HoloMessageType.TEXT.name,
                status = HoloReceiptStatus.READ.name,
                isEncrypted = true,
                cipherHex = storedCipher,
                modelBadge = badgeLabel
            )
            // Also write to user-scoped archive
            repo.sendMessage(
                peerId = contactId,
                senderId = contactId,
                senderLabel = persona.callSign,
                text = replyText,
                messageType = HoloMessageType.TEXT.name,
                status = HoloReceiptStatus.READ.name,
                isFromMe = false,
                isEncrypted = true,
                cipherHex = storedCipher,
                modelBadge = badgeLabel
            )
            if (HOLO_BOT_PERSONAS.containsKey(contactId)) {
                val verification = HoloE2eeCryptoEngine.generateVerification(operatorUid, contactId)
                repo.upsertPeerPresence(
                    peerId = contactId,
                    displayName = persona.callSign,
                    presence = restoredPresence.name,
                    unreadCount = 0L,
                    lastMessagePreview = replyText.take(120),
                    isAiFallbackEnabled = true,
                    e2eeFingerprint = verification.keyFingerprintHex
                )
            }
        }
    }

    private fun buildValidAlternatingHistory(
        persona: HoloBotPersona,
        messages: List<HoloChatMessage>
    ): List<Content> {
        val validPairs = mutableListOf<Content>(
            content("user") {
                text("SYSTEM ROLE BRIEFING:\n${persona.systemPrompt}\nConfirm link initialization.")
            },
            content("model") {
                text(persona.welcomeMessage)
            }
        )

        val realMessages = messages.filter { !it.isSyntheticGreeting && !it.isStreaming && it.text.isNotBlank() }
        var index = 0
        while (index + 1 < realMessages.size) {
            val first = realMessages[index]
            val second = realMessages[index + 1]
            if (first.isUser && !second.isUser) {
                validPairs.add(content("user") { text(first.text) })
                validPairs.add(content("model") { text(second.text) })
                index += 2
            } else {
                index += 1
            }
        }
        return validPairs
    }

    private fun finalizeBotMessage(
        contactId: String,
        botMessageId: String,
        finalReply: String,
        badgeLabel: String,
        restoredPresence: PeerPresenceStatus
    ) {
        val encrypted = HoloE2eeCryptoEngine.encryptMessage(finalReply, operatorUid, contactId)
        val storedCipher = encrypted.cipherBase64.ifBlank { encrypted.cipherHexPreview }
        _uiState.update { state ->
            val updatedThread = state.threadsByContact[contactId]
                .orEmpty()
                .map { msg ->
                    if (msg.id == botMessageId) {
                        msg.copy(
                            text = finalReply,
                            modelBadge = badgeLabel,
                            cipherHex = storedCipher,
                            receiptStatus = HoloReceiptStatus.READ,
                            isStreaming = false
                        )
                    } else {
                        msg
                    }
                }
            state.copy(
                threadsByContact = state.threadsByContact + (contactId to updatedThread),
                peerPresenceByContact = state.peerPresenceByContact + (contactId to restoredPresence),
                generationState = ChatGenerationState.Idle
            )
        }
    }
}

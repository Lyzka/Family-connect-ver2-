package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.CallAndChatState
import com.example.model.ConnectionState
import com.example.model.HoloCommUiState
import com.example.model.HoloViewMode
import com.example.model.PeerPresenceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HudViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HoloCommUiState())
    val uiState: StateFlow<HoloCommUiState> = _uiState.asStateFlow()

    private var callTimerJob: Job? = null
    private var stateTransitionJob: Job? = null

    /**
     * Updates the HUD latency display with a real measured round-trip time (RTT in ms)
     * from Cloud Firestore operations.
     */
    fun updateMeasuredPing(rttMs: Int) {
        _uiState.update { state ->
            state.copy(pingMs = rttMs.coerceIn(1, 999))
        }
    }

    fun selectContact(contactId: String) {
        _uiState.update { state ->
            val contact = state.contacts.find { it.id == contactId }
            val updatedContacts = state.contacts.map { item ->
                if (item.id == contactId) item.copy(unreadCount = 0) else item
            }
            state.copy(
                contacts = updatedContacts,
                selectedContactId = contactId,
                isDisconnected = false,
                globeYawOffset = (contact?.targetLon ?: 0f) * 0.015f,
                pulseEnergy = 1.45f
            )
        }
    }

    fun syncContactMetadata(
        contactId: String,
        presence: PeerPresenceStatus? = null,
        unreadCount: Int? = null,
        lastMessagePreview: String? = null
    ) {
        _uiState.update { state ->
            val updated = state.contacts.map { c ->
                if (c.id == contactId) {
                    c.copy(
                        presence = presence ?: c.presence,
                        unreadCount = if (contactId == state.selectedContactId && state.viewMode == HoloViewMode.AI_CHAT) {
                            0
                        } else {
                            unreadCount ?: c.unreadCount
                        },
                        lastMessagePreview = lastMessagePreview ?: c.lastMessagePreview
                    )
                } else {
                    c
                }
            }
            state.copy(contacts = updated)
        }
    }

    fun syncGlobalRemoteOperators(
        localUid: String,
        remoteOperators: List<com.example.data.GlobalOperatorPresenceDoc>
    ) {
        _uiState.update { state ->
            val baseContacts = state.contacts.filter { it.id in listOf("USER_01", "USER_02", "USER_03") }
            val remoteContacts = remoteOperators
                .filter { it.userId.isNotBlank() && it.userId != localUid }
                .mapIndexed { idx, op ->
                    val existing = state.contacts.find { it.id == op.userId }
                    val status = when (op.presence) {
                        "TYPING" -> PeerPresenceStatus.TYPING
                        "OFFLINE" -> PeerPresenceStatus.OFFLINE
                        else -> PeerPresenceStatus.ONLINE
                    }
                    com.example.model.HoloContact(
                        id = op.userId,
                        label = op.callSign.ifBlank { op.displayName.take(14).uppercase() },
                        avatarType = com.example.model.BustAvatarType.entries[idx % com.example.model.BustAvatarType.entries.size],
                        sectorCode = op.deviceModel ?: "P2P // REMOTE NODE",
                        frequencyGhz = 512.4f + idx * 24.2f,
                        targetLat = 18f,
                        targetLon = -42f + idx * 35f,
                        presence = status,
                        unreadCount = existing?.unreadCount ?: 0,
                        lastMessagePreview = op.statusMessage ?: "ONLINE IN /global_presence"
                    )
                }
            state.copy(contacts = baseContacts + remoteContacts)
        }
    }

    /**
     * Triggered when a real incoming WebRTC SDP_OFFER arrives from a remote peer
     * in `/global_comms_rooms/{roomId}/rtc_signals`.
     */
    fun triggerIncomingCall(fromPeerId: String = _uiState.value.selectedContactId) {
        stateTransitionJob?.cancel()
        callTimerJob?.cancel()
        _uiState.update { state ->
            state.copy(
                selectedContactId = fromPeerId,
                callAndChatState = CallAndChatState.INCOMING_CALL,
                isCallActive = false,
                isVideoActive = false,
                isDisconnected = false,
                callDurationSeconds = 0,
                pulseEnergy = 1.9f,
                activeSdpSummary = "SDP OFFER // INCOMING RING FROM $fromPeerId"
            )
        }
    }

    /**
     * Invoked when the remote peer answers the call (`CONNECTED` / `SDP_ANSWER` in Firestore)
     * or when this device answers an incoming call.
     */
    fun onRemoteCallConnected(isVideo: Boolean) {
        stateTransitionJob?.cancel()
        _uiState.update { state ->
            if (isVideo) {
                state.copy(
                    callAndChatState = CallAndChatState.VIDEO_ACTIVE,
                    connectionState = ConnectionState.VIDEO_ACTIVE,
                    isCallActive = true,
                    isVideoActive = true,
                    isDisconnected = false,
                    pulseEnergy = 1.8f,
                    activeSdpSummary = "WebRTC VP9+OPUS // HOLO-SPHERE STREAM ACTIVE"
                )
            } else {
                state.copy(
                    callAndChatState = CallAndChatState.IN_CALL,
                    connectionState = ConnectionState.CALL_ACTIVE,
                    isCallActive = true,
                    isVideoActive = false,
                    isDisconnected = false,
                    pulseEnergy = 1.5f,
                    activeSdpSummary = "WebRTC SRTP // OPUS 48kHz + LIVE ICE CANDIDATES"
                )
            }
        }
        startCallTimer()
    }

    /**
     * Invoked when the remote peer terminates the active call (`ENDED` in Firestore).
     */
    fun onRemoteCallEnded() {
        val current = _uiState.value.callAndChatState
        if (current == CallAndChatState.IN_CALL ||
            current == CallAndChatState.VIDEO_ACTIVE ||
            current == CallAndChatState.CONNECTING ||
            current == CallAndChatState.INCOMING_CALL
        ) {
            disconnectCall(onEndRtcCall = {})
        }
    }

    /**
     * Initiates or answers a voice call (WebRTC Audio).
     */
    fun toggleCall(
        onStartRtcCall: (peerId: String, isVideo: Boolean) -> Unit = { _, _ -> },
        onEndRtcCall: () -> Unit = {}
    ) {
        val current = _uiState.value
        if (current.callAndChatState == CallAndChatState.IN_CALL ||
            current.callAndChatState == CallAndChatState.VIDEO_ACTIVE ||
            current.callAndChatState == CallAndChatState.CONNECTING
        ) {
            disconnectCall(onEndRtcCall)
            return
        }

        val isAnsweringIncoming = current.callAndChatState == CallAndChatState.INCOMING_CALL
        stateTransitionJob?.cancel()
        _uiState.update { state ->
            state.copy(
                callAndChatState = CallAndChatState.CONNECTING,
                connectionState = ConnectionState.CONNECTING,
                isCallActive = true,
                isVideoActive = false,
                isDisconnected = false,
                callDurationSeconds = 0,
                pulseEnergy = 1.75f,
                activeSdpSummary = if (isAnsweringIncoming) {
                    "SDP ANSWER+ICE // ESTABLISHING P2P AUDIO..."
                } else {
                    "SDP OFFER+ICE // NEGOTIATING AUDIO P2P..."
                }
            )
        }
        onStartRtcCall(current.selectedContactId, false)

        if (isAnsweringIncoming) {
            onRemoteCallConnected(isVideo = false)
        }
    }

    /**
     * Initiates or toggles a live WebRTC Video call inside the central holographic sphere.
     */
    fun startOrToggleVideoCall(
        onStartRtcCall: (peerId: String, isVideo: Boolean) -> Unit = { _, _ -> },
        onEndRtcCall: () -> Unit = {}
    ) {
        val current = _uiState.value
        if (current.callAndChatState == CallAndChatState.VIDEO_ACTIVE) {
            disconnectCall(onEndRtcCall)
            return
        }

        val isAnsweringIncoming = current.callAndChatState == CallAndChatState.INCOMING_CALL
        stateTransitionJob?.cancel()
        _uiState.update { state ->
            state.copy(
                callAndChatState = CallAndChatState.CONNECTING,
                connectionState = ConnectionState.CONNECTING,
                isCallActive = true,
                isVideoActive = true,
                isDisconnected = false,
                showCameraPermissionNotice = false,
                callDurationSeconds = 0,
                pulseEnergy = 1.85f,
                activeSdpSummary = if (isAnsweringIncoming) {
                    "SDP ANSWER+ICE // ESTABLISHING VP9 HOLO-VIDEO..."
                } else {
                    "SDP OFFER+ICE // NEGOTIATING VP9 HOLO-VIDEO..."
                }
            )
        }
        onStartRtcCall(current.selectedContactId, true)

        if (isAnsweringIncoming) {
            onRemoteCallConnected(isVideo = true)
        }
    }

    fun toggleVideo() {
        startOrToggleVideoCall()
    }

    fun onCameraPermissionResult(isGranted: Boolean, activateVideoOnGrant: Boolean = true) {
        _uiState.update { state ->
            state.copy(
                cameraPermissionGranted = isGranted,
                showCameraPermissionNotice = !isGranted
            )
        }
        if (isGranted && activateVideoOnGrant) {
            startOrToggleVideoCall()
        }
    }

    fun onMediaPermissionsResult(
        cameraGranted: Boolean,
        audioGranted: Boolean,
        activateVideoOnGrant: Boolean = true,
        onStartRtcCall: (peerId: String, isVideo: Boolean) -> Unit = { _, _ -> }
    ) {
        _uiState.update { state ->
            state.copy(
                cameraPermissionGranted = cameraGranted,
                audioPermissionGranted = audioGranted,
                showCameraPermissionNotice = !cameraGranted
            )
        }
        if (cameraGranted && activateVideoOnGrant) {
            startOrToggleVideoCall(onStartRtcCall = onStartRtcCall)
        }
    }

    /**
     * SECURE button: Activates/verifies AES-256-GCM E2EE, opens the security verification modal,
     * and toggles between AI Hybrid Mode and Direct Encrypted P2P channel.
     */
    fun toggleSecure() {
        _uiState.update { state ->
            val nextDirectP2p = !state.isDirectP2pMode
            state.copy(
                isSecureActive = true,
                isDirectP2pMode = nextDirectP2p,
                showSecurityVerificationModal = true,
                isDisconnected = false,
                connectionState = ConnectionState.SECURE_LOCKED,
                pulseEnergy = 1.45f
            )
        }
    }

    fun dismissSecurityVerificationModal() {
        _uiState.update { it.copy(showSecurityVerificationModal = false) }
    }

    /**
     * DISCONNECT / X button: Immediately closes active WebRTC connection, flashes red (#FF1744)
     * with "TRANSMISSION ENDED", and smoothly returns to IDLE ("STANDBY // CHANNEL READY").
     */
    fun disconnectCall(onEndRtcCall: () -> Unit = {}) {
        stateTransitionJob?.cancel()
        callTimerJob?.cancel()
        onEndRtcCall()

        _uiState.update { state ->
            state.copy(
                callAndChatState = CallAndChatState.DISCONNECTED,
                connectionState = ConnectionState.DISCONNECTED,
                isDisconnected = true,
                isCallActive = false,
                isVideoActive = false,
                callDurationSeconds = 0,
                pulseEnergy = 0.4f,
                activeSdpSummary = "RTCPeerConnection.close() // RESOURCES RELEASED"
            )
        }

        stateTransitionJob = viewModelScope.launch {
            delay(2000L)
            _uiState.update { state ->
                state.copy(
                    callAndChatState = CallAndChatState.IDLE,
                    connectionState = ConnectionState.CONNECTING,
                    isDisconnected = false,
                    pulseEnergy = 1.0f,
                    activeSdpSummary = "SDP // STANDBY"
                )
            }
        }
    }

    fun disconnectOrReconnect() {
        disconnectCall()
    }

    private fun startCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                _uiState.update { state ->
                    if (state.callAndChatState == CallAndChatState.IN_CALL ||
                        state.callAndChatState == CallAndChatState.VIDEO_ACTIVE
                    ) {
                        state.copy(callDurationSeconds = state.callDurationSeconds + 1)
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun onGlobeDrag(dragX: Float, dragY: Float) {
        _uiState.update { state ->
            state.copy(
                globeYawOffset = state.globeYawOffset + dragX * 0.006f,
                globePitchOffset = (state.globePitchOffset + dragY * 0.004f).coerceIn(-0.65f, 0.65f)
            )
        }
    }

    /**
     * Applies subtle real-time device rotation/accelerometer tilt to the 3D holographic globe.
     */
    fun onSensorOrientationDelta(yawDelta: Float, pitchDelta: Float) {
        _uiState.update { state ->
            state.copy(
                globeYawOffset = state.globeYawOffset + yawDelta.coerceIn(-0.02f, 0.02f),
                globePitchOffset = (state.globePitchOffset + pitchDelta.coerceIn(-0.02f, 0.02f)).coerceIn(-0.65f, 0.65f)
            )
        }
    }

    fun resetGlobeOrbit() {
        _uiState.update { state ->
            state.copy(
                globeYawOffset = 0f,
                globePitchOffset = 0f,
                pulseEnergy = 1.4f
            )
        }
    }

    fun setViewMode(mode: HoloViewMode) {
        _uiState.update { state ->
            val updatedContacts = if (mode == HoloViewMode.AI_CHAT) {
                state.contacts.map { c ->
                    if (c.id == state.selectedContactId) c.copy(unreadCount = 0) else c
                }
            } else {
                state.contacts
            }
            state.copy(viewMode = mode, contacts = updatedContacts)
        }
    }
}

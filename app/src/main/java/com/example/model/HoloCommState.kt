package com.example.model

enum class BustAvatarType {
    USER_01_MALE,
    USER_02_HOODED,
    USER_03_COMMANDER
}

enum class PeerPresenceStatus(val badgeText: String) {
    ONLINE("ONLINE"),
    TYPING("TYPING..."),
    OFFLINE("OFFLINE // AI RELAY")
}

/**
 * Reactive 3D Scene & Communicator State Machine (Section 4 of specification).
 */
enum class CallAndChatState {
    IDLE,
    INCOMING_CALL,
    CONNECTING,
    IN_CALL,
    VIDEO_ACTIVE,
    DISCONNECTED,
    ERROR
}

enum class ConnectionState(val centerLabel: String) {
    CONNECTING("CONNECTING..."),
    CALL_ACTIVE("VOICE LINK ACTIVE"),
    VIDEO_ACTIVE("HOLO STREAM LIVE"),
    SECURE_LOCKED("QUANTUM SECURED"),
    DISCONNECTED("TRANSMISSION ENDED")
}

enum class HoloViewMode {
    NATIVE_3D_HUD,
    AI_CHAT,
    THREEJS_WEBGL,
    MCP_SHADER_LAB
}

data class HoloContact(
    val id: String,
    val label: String,
    val avatarType: BustAvatarType,
    val sectorCode: String,
    val frequencyGhz: Float,
    val targetLat: Float,
    val targetLon: Float,
    val presence: PeerPresenceStatus = PeerPresenceStatus.ONLINE,
    val unreadCount: Int = 0,
    val lastMessagePreview: String = "STANDBY // CHANNEL READY"
)

data class HoloCommUiState(
    val contacts: List<HoloContact> = listOf(
        HoloContact(
            id = "USER_01",
            label = "USER_01",
            avatarType = BustAvatarType.USER_01_MALE,
            sectorCode = "SEC-09 // ORBITAL",
            frequencyGhz = 142.8f,
            targetLat = 35f,
            targetLon = -20f,
            presence = PeerPresenceStatus.ONLINE,
            unreadCount = 0,
            lastMessagePreview = "ORBITAL LINK READY [142.8 GHz]"
        ),
        HoloContact(
            id = "USER_02",
            label = "USER_02",
            avatarType = BustAvatarType.USER_02_HOODED,
            sectorCode = "SEC-14 // DEEP NET",
            frequencyGhz = 288.4f,
            targetLat = 50f,
            targetLon = 15f,
            presence = PeerPresenceStatus.ONLINE,
            unreadCount = 1,
            lastMessagePreview = "QUANTUM TUNNEL SYNCHRONIZED"
        ),
        HoloContact(
            id = "USER_03",
            label = "USER_03",
            avatarType = BustAvatarType.USER_03_COMMANDER,
            sectorCode = "SEC-03 // CITADEL",
            frequencyGhz = 419.2f,
            targetLat = 12f,
            targetLon = 75f,
            presence = PeerPresenceStatus.OFFLINE,
            unreadCount = 2,
            lastMessagePreview = "CITADEL AI RELAY ACTIVE"
        )
    ),
    val selectedContactId: String = "USER_01",
    val callAndChatState: CallAndChatState = CallAndChatState.IDLE,
    val connectionState: ConnectionState = ConnectionState.CONNECTING,
    val isCallActive: Boolean = false,
    val isVideoActive: Boolean = false,
    val isSecureActive: Boolean = true,
    val isDirectP2pMode: Boolean = false,
    val showSecurityVerificationModal: Boolean = false,
    val isDisconnected: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val showCameraPermissionNotice: Boolean = false,
    val callDurationSeconds: Int = 0,
    val pingMs: Int = 18,
    val activeRtcSessionId: String? = null,
    val activeSdpSummary: String = "SDP // STANDBY",
    val globeYawOffset: Float = 0f,
    val globePitchOffset: Float = 0f,
    val pulseEnergy: Float = 1f,
    val viewMode: HoloViewMode = HoloViewMode.NATIVE_3D_HUD
) {
    val activeContact: HoloContact
        get() = contacts.find { it.id == selectedContactId } ?: contacts.first()

    val formattedCallDuration: String
        get() {
            val mins = callDurationSeconds / 60
            val secs = callDurationSeconds % 60
            return "%02d:%02d".format(mins, secs)
        }

    val centerStatusText: String
        get() = when {
            showCameraPermissionNotice -> "CAMERA/MIC AUTH REQUIRED"
            callAndChatState == CallAndChatState.DISCONNECTED || callAndChatState == CallAndChatState.ERROR ->
                "TRANSMISSION ENDED"
            callAndChatState == CallAndChatState.INCOMING_CALL ->
                "INCOMING TRANSMISSION FROM $selectedContactId"
            callAndChatState == CallAndChatState.CONNECTING ->
                "CONNECTING..."
            callAndChatState == CallAndChatState.VIDEO_ACTIVE || isVideoActive ->
                formattedCallDuration
            callAndChatState == CallAndChatState.IN_CALL || isCallActive ->
                formattedCallDuration
            else -> "STANDBY // CHANNEL READY"
        }
}

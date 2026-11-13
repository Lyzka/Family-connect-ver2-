package com.example.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.HoloCommRepository
import com.example.model.CallAndChatState
import com.example.model.HoloViewMode
import com.example.ui.chat.HoloAiChatOverlay
import com.example.ui.chat.HoloAiChatViewModel
import com.example.ui.components.HoloBottomNavBar
import com.example.ui.components.HoloCentralPeerVideoSphere
import com.example.ui.components.HoloFlowingParticleField
import com.example.ui.components.HoloLeftContactsPanel
import com.example.ui.components.HoloLocalCameraPipPreview
import com.example.ui.components.HoloRightTelemetryPanel
import com.example.ui.components.HoloSecurityVerificationModal
import com.example.ui.render3d.drawHolographicCenterpieceAndParticles
import com.example.ui.render3d.drawSciFiCorridorBackground
import com.example.ui.theme.HoloBlack
import com.example.ui.theme.HoloCyan
import com.example.ui.theme.HoloCyanBright
import com.example.ui.theme.HoloGold
import com.example.ui.theme.HoloGoldBright
import com.example.ui.theme.HoloRed
import com.example.ui.theme.HoloRedBright
import com.example.ui.theme.HoloWhite
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import com.example.ui.webgl.HoloMcpShaderLabModal
import com.example.ui.webgl.HoloThreeJsWebView
import com.example.util.CameraPermissionHelper
import com.example.util.HoloWebRtcAndAudioEngine
import com.example.util.rememberMediaCallPermissionsRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HoloCommunicatorScreen(
    modifier: Modifier = Modifier,
    currentUserId: String = "local_operator",
    onSignOut: (() -> Unit)? = null,
    hudViewModel: HudViewModel = viewModel(key = "hud_$currentUserId"),
    aiChatViewModel: HoloAiChatViewModel = viewModel(key = "chat_$currentUserId")
) {
    val uiState by hudViewModel.uiState.collectAsStateWithLifecycle()
    val chatState by aiChatViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Instantiate real-time Firestore repository and WebRTC + AudioRecord engine
    val repository = remember(context, currentUserId) {
        HoloCommRepository(context.applicationContext)
    }
    val rtcAudioEngine = remember(context) {
        HoloWebRtcAndAudioEngine(context.applicationContext, coroutineScope)
    }

    val liveAudioSpectrum by rtcAudioEngine.liveAudioSpectrum.collectAsStateWithLifecycle()
    val voiceModulationLevel by rtcAudioEngine.voiceModulationLevel.collectAsStateWithLifecycle()
    val isRecordingVoiceNote by rtcAudioEngine.isRecordingVoiceNote.collectAsStateWithLifecycle()
    val recordingDurationMs by rtcAudioEngine.recordingDurationMs.collectAsStateWithLifecycle()
    val playingMessageId by rtcAudioEngine.playingMessageId.collectAsStateWithLifecycle()
    val playbackProgress by rtcAudioEngine.playbackProgress.collectAsStateWithLifecycle()

    // Real hardware telemetry ratios (BatteryManager + ConnectivityManager)
    var batteryRatio by remember { mutableFloatStateOf(0.85f) }
    var networkLinkRatio by remember { mutableFloatStateOf(0.90f) }

    LaunchedEffect(context) {
        while (true) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                if (pct in 1..100) {
                    batteryRatio = (pct / 100f).coerceIn(0.15f, 0.98f)
                }
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
                networkLinkRatio = when {
                    downKbps <= 0 -> 0.35f
                    downKbps >= 50_000 -> 0.96f
                    else -> (0.35f + (downKbps / 50_000f) * 0.61f).coerceIn(0.25f, 0.96f)
                }
            } catch (_: Exception) {
            }
            delay(10_000L)
        }
    }

    // Real-time hardware accelerometer / rotation sensor integration for 3D holographic globe parallax
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastAx = Float.NaN
        var lastAy = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 2) return
                val ax = values[0]
                val ay = values[1]
                if (!lastAx.isNaN() && !lastAy.isNaN()) {
                    val dx = (ax - lastAx) * 0.0025f
                    val dy = (ay - lastAy) * 0.0020f
                    if (kotlin.math.abs(dx) > 0.0004f || kotlin.math.abs(dy) > 0.0004f) {
                        hudViewModel.onSensorOrientationDelta(-dx, dy)
                    }
                }
                lastAx = ax
                lastAy = ay
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensorManager != null && accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: Exception) {
            }
        }
    }

    // Attach Firestore repository once authenticated user ID is available
    LaunchedEffect(repository, currentUserId) {
        if (currentUserId != "local_operator" && currentUserId.isNotBlank()) {
            aiChatViewModel.attachFirestoreRepository(repository, currentUserId)
        }
    }

    // Sync real measured Firestore round-trip latency (RTT) to the HUD ViewModel
    LaunchedEffect(chatState.measuredPingMs) {
        hudViewModel.updateMeasuredPing(chatState.measuredPingMs)
    }

    // Periodically measure real Firestore RTT every 15s while authenticated
    LaunchedEffect(currentUserId) {
        if (currentUserId != "local_operator" && currentUserId.isNotBlank()) {
            while (true) {
                delay(15_000L)
                aiChatViewModel.measureRealtimeFirestorePing { rtt ->
                    hudViewModel.updateMeasuredPing(rtt)
                }
            }
        }
    }

    // Keep HUD contact cards in sync with real-time /global_presence operators and unread message counts
    LaunchedEffect(
        chatState.globalOnlineOperators,
        chatState.peerPresenceByContact,
        chatState.unreadCountByContact,
        chatState.threadsByContact
    ) {
        hudViewModel.syncGlobalRemoteOperators(
            localUid = currentUserId,
            remoteOperators = chatState.globalOnlineOperators
        )
        chatState.peerPresenceByContact.forEach { (peerId, presence) ->
            val unread = chatState.unreadCountByContact[peerId] ?: 0
            val lastMsg = chatState.threadsByContact[peerId]?.lastOrNull()?.text
            hudViewModel.syncContactMetadata(
                contactId = peerId,
                presence = presence,
                unreadCount = unread,
                lastMessagePreview = lastMsg
            )
        }
    }

    // Trigger HUD incoming call alert when a remote device publishes an SDP_OFFER in /global_comms_rooms/{roomId}/rtc_signals
    LaunchedEffect(chatState.latestIncomingRtcSignal?.id) {
        val incomingSignal = chatState.latestIncomingRtcSignal
        if (incomingSignal != null && incomingSignal.senderId != currentUserId) {
            aiChatViewModel.selectContact(incomingSignal.senderId)
            hudViewModel.triggerIncomingCall(fromPeerId = incomingSignal.senderId)
        }
    }

    // Synchronize real two-way WebRTC call state (CONNECTED / ENDED) from Firestore /rtc_signals
    LaunchedEffect(chatState.latestRemoteCallState, chatState.latestRemoteCallType) {
        when (chatState.latestRemoteCallState) {
            "CONNECTED", "SDP_ANSWER" -> {
                if (uiState.callAndChatState == CallAndChatState.CONNECTING) {
                    val isVideoCall = chatState.latestRemoteCallType == "VIDEO" || uiState.isVideoActive
                    hudViewModel.onRemoteCallConnected(isVideo = isVideoCall)
                }
            }
            "ENDED" -> {
                if (uiState.callAndChatState == CallAndChatState.IN_CALL ||
                    uiState.callAndChatState == CallAndChatState.VIDEO_ACTIVE ||
                    uiState.callAndChatState == CallAndChatState.CONNECTING ||
                    uiState.callAndChatState == CallAndChatState.INCOMING_CALL
                ) {
                    rtcAudioEngine.stopCallAudioCapture()
                    hudViewModel.onRemoteCallEnded()
                }
            }
        }
    }

    // Clean up audio capture and playback on dispose
    DisposableEffect(rtcAudioEngine) {
        onDispose {
            rtcAudioEngine.stopCallAudioCapture()
            rtcAudioEngine.stopPlayback()
        }
    }

    val startWebRtcSessionLambda: (String, Boolean) -> Unit = { peerId, isVideo ->
        val verification = aiChatViewModel.getSecurityVerification(peerId)
        val sdpOffer = rtcAudioEngine.buildSdpOffer(peerId, isVideo, verification.keyFingerprintHex)
        val sdpAnswer = rtcAudioEngine.buildRemoteSdpAnswer(peerId, isVideo, verification.keyFingerprintHex)
        val iceCandidates = rtcAudioEngine.gatherIceCandidates(peerId)
        rtcAudioEngine.startCallAudioCapture()
        aiChatViewModel.startWebRtcSignaling(
            peerId = peerId,
            isVideo = isVideo,
            sdpOffer = sdpOffer,
            sdpAnswer = sdpAnswer,
            iceCandidates = iceCandidates
        )
    }

    val endWebRtcSessionLambda: () -> Unit = {
        rtcAudioEngine.stopCallAudioCapture()
        aiChatViewModel.endWebRtcSignaling()
    }

    val mediaCallPermissionsRequester = rememberMediaCallPermissionsRequester(
        onVideoAudioResult = { camGranted, micGranted ->
            hudViewModel.onMediaPermissionsResult(
                cameraGranted = camGranted,
                audioGranted = micGranted,
                activateVideoOnGrant = true,
                onStartRtcCall = startWebRtcSessionLambda
            )
        },
        onAudioOnlyResult = { micGranted ->
            if (micGranted) {
                rtcAudioEngine.startVoiceNoteRecording()
            }
        }
    )

    LaunchedEffect(context) {
        val hasCam = CameraPermissionHelper.hasCameraPermission(context)
        val hasMic = CameraPermissionHelper.hasAudioPermission(context)
        if (hasCam || hasMic) {
            hudViewModel.onMediaPermissionsResult(
                cameraGranted = hasCam,
                audioGranted = hasMic,
                activateVideoOnGrant = false
            )
        }
    }

    // Handle back navigation when inside AI Chat, 3D Mesh, MCP Shader Lab, or E2EE modal
    if (uiState.showSecurityVerificationModal) {
        BackHandler {
            hudViewModel.dismissSecurityVerificationModal()
        }
    } else if (uiState.viewMode != HoloViewMode.NATIVE_3D_HUD) {
        BackHandler {
            hudViewModel.setViewMode(HoloViewMode.NATIVE_3D_HUD)
        }
    }

    // Pause heavy background 3D loops while full-screen modal overlays (AI_CHAT / MCP_SHADER_LAB) are active
    val isModalOverlayOpen = uiState.viewMode == HoloViewMode.AI_CHAT ||
        uiState.viewMode == HoloViewMode.MCP_SHADER_LAB

    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isModalOverlayOpen) {
        if (!isModalOverlayOpen) {
            val startMs = android.os.SystemClock.uptimeMillis() - (timeSec * 1000f).toLong()
            while (true) {
                delay(33L)
                timeSec = (android.os.SystemClock.uptimeMillis() - startMs) / 1000f
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(HoloBlack)
            .testTag("holo_communicator_root")
    ) {
        val isCompactHeight = maxHeight < 430.dp
        val isPortraitLayout = maxWidth < maxHeight

        // Layer 1A: Full-Bleed 3D Sci-Fi Corridor, 3D Globe, Orbital Rings & Icosahedrons
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        hudViewModel.onGlobeDrag(dragAmount.x, dragAmount.y)
                    }
                }
        ) {
            drawSciFiCorridorBackground()
            drawHolographicCenterpieceAndParticles(
                timeSec = timeSec,
                yawOffset = uiState.globeYawOffset,
                pitchOffset = uiState.globePitchOffset,
                isDisconnected = uiState.isDisconnected,
                isSecure = uiState.isSecureActive,
                pulseEnergy = uiState.pulseEnergy,
                callAndChatState = uiState.callAndChatState,
                voiceModulationLevel = voiceModulationLevel
            )
        }

        // Layer 1B: Dedicated Flowing & Drifting Cyan Nano-Particle Atmosphere Composable
        if (!isModalOverlayOpen) {
            HoloFlowingParticleField(
                modifier = Modifier.fillMaxSize(),
                flowIntensity = uiState.pulseEnergy + voiceModulationLevel * 0.45f,
                isAlertRed = uiState.isDisconnected
            )
        }

        // Layer 2: Active Mode Content
        if (uiState.viewMode == HoloViewMode.THREEJS_WEBGL) {
            HoloThreeJsWebView(
                selectedContactId = uiState.selectedContactId,
                centerStatusText = uiState.centerStatusText,
                timeSec = timeSec,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Default: 1:1 3D Perspective HUD Grid
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        horizontal = if (isPortraitLayout) 8.dp else 18.dp,
                        vertical = if (isCompactHeight) 6.dp else 12.dp
                    )
            ) {
                // Main 3-Column Perspective Stage (Left Panel + Centerpiece + Right Panel)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (isCompactHeight) 0.78f else 0.80f)
                        .align(Alignment.TopCenter)
                ) {
                    // Left Panel: CONTACTS / KONTAKTY + 3D Wireframe Bust Avatars + Unread Badges + Gold Sparks
                    HoloLeftContactsPanel(
                        contacts = uiState.contacts,
                        selectedContactId = uiState.selectedContactId,
                        timeSec = timeSec,
                        isCompactHeight = isCompactHeight,
                        onSelectContact = { contactId ->
                            hudViewModel.selectContact(contactId)
                            aiChatViewModel.selectContact(contactId)
                        },
                        modifier = Modifier
                            .weight(if (isPortraitLayout) 0.33f else 0.27f)
                            .fillMaxHeight()
                    )

                    // Centerpiece: Integrated Holographic Video Module (when VIDEO_ACTIVE) + Glowing Status Text
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(if (isPortraitLayout) 0.34f else 0.46f)
                            .fillMaxHeight()
                    ) {
                        if (uiState.callAndChatState == CallAndChatState.VIDEO_ACTIVE) {
                            HoloCentralPeerVideoSphere(
                                peerId = uiState.selectedContactId,
                                timeSec = timeSec,
                                voiceModulationLevel = voiceModulationLevel,
                                sphereDiameter = if (isCompactHeight) 136.dp else if (isPortraitLayout) 150.dp else 190.dp,
                                remoteFrameBase64 = chatState.remoteVideoFrameBase64
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        val glowColor = when (uiState.callAndChatState) {
                            CallAndChatState.DISCONNECTED, CallAndChatState.ERROR -> HoloRed
                            CallAndChatState.INCOMING_CALL -> HoloGoldBright
                            else -> HoloCyan
                        }
                        val statusFontSize = when {
                            uiState.callAndChatState == CallAndChatState.INCOMING_CALL ->
                                if (isPortraitLayout) 13.sp else 18.sp
                            uiState.callAndChatState == CallAndChatState.IDLE ->
                                if (isPortraitLayout) 13.sp else 20.sp
                            else ->
                                if (isPortraitLayout) 18.sp else 28.sp
                        }

                        Box(contentAlignment = Alignment.Center) {
                            // Outer soft neon bloom behind text
                            Text(
                                text = uiState.centerStatusText,
                                style = TextStyle(
                                    fontFamily = RajdhaniFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = statusFontSize,
                                    letterSpacing = if (isPortraitLayout) 1.5.sp else 3.2.sp,
                                    color = glowColor.copy(alpha = 0.55f),
                                    shadow = Shadow(
                                        color = glowColor,
                                        offset = Offset.Zero,
                                        blurRadius = 32f
                                    )
                                ),
                                textAlign = TextAlign.Center
                            )
                            // Crisp white core text with bright cyan/gold/red halo
                            Text(
                                text = uiState.centerStatusText,
                                style = TextStyle(
                                    fontFamily = RajdhaniFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = statusFontSize,
                                    letterSpacing = if (isPortraitLayout) 1.5.sp else 3.2.sp,
                                    color = HoloWhite,
                                    shadow = Shadow(
                                        color = glowColor,
                                        offset = Offset.Zero,
                                        blurRadius = 16f
                                    )
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("center_status_text")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick-launch Holographic WhatsApp/Gemini Comms pill below centerpiece status
                        val activeUnread = chatState.unreadCountByContact[uiState.selectedContactId] ?: 0
                        Box(
                            modifier = Modifier
                                .testTag("open_ai_comms_button")
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xB303152B))
                                .border(1.dp, HoloCyanBright.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                                .clickable {
                                    aiChatViewModel.selectContact(uiState.selectedContactId)
                                    hudViewModel.setViewMode(HoloViewMode.AI_CHAT)
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = buildString {
                                    append("${stringResource(R.string.open_ai_comms)} [${uiState.selectedContactId}]")
                                    if (activeUnread > 0) append(" • $activeUnread NEW")
                                },
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isPortraitLayout) 8.sp else 10.sp,
                                letterSpacing = 1.4.sp,
                                color = HoloCyanBright
                            )
                        }
                    }

                    // Right Panel: Real-time Audio Spectrum + Ping + Encryption + Peer Typing/Online Status
                    HoloRightTelemetryPanel(
                        timeSec = timeSec,
                        isCallActive = uiState.isCallActive,
                        isDisconnected = uiState.isDisconnected,
                        isCompactHeight = isCompactHeight,
                        voiceModulationLevel = voiceModulationLevel,
                        liveAudioSpectrum = liveAudioSpectrum,
                        pingMs = uiState.pingMs,
                        isEncrypted = uiState.isSecureActive,
                        peerPresence = chatState.activePeerPresence,
                        batteryLevelRatio = batteryRatio,
                        networkLinkRatio = networkLinkRatio,
                        modifier = Modifier
                            .weight(if (isPortraitLayout) 0.33f else 0.27f)
                            .fillMaxHeight()
                    )
                }

                // Local Camera Picture-in-Picture (PiP) in the bottom-right corner during active Video Call
                if (uiState.callAndChatState == CallAndChatState.VIDEO_ACTIVE) {
                    HoloLocalCameraPipPreview(
                        hasCameraPermission = uiState.cameraPermissionGranted,
                        timeSec = timeSec,
                        onFrameCaptured = { base64Frame ->
                            aiChatViewModel.publishLiveVideoFrame(
                                peerId = uiState.selectedContactId,
                                base64JpegFrame = base64Frame
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = if (isCompactHeight) 68.dp else 92.dp, end = 8.dp)
                    )
                }

                // Bottom Navigation Bar: 4 Floating Translucent 3D Square Glass Buttons
                HoloBottomNavBar(
                    isCallActive = uiState.isCallActive,
                    isVideoActive = uiState.isVideoActive,
                    isSecureActive = uiState.isSecureActive,
                    isDisconnected = uiState.isDisconnected,
                    isCompactHeight = isCompactHeight,
                    isIncomingCall = uiState.callAndChatState == CallAndChatState.INCOMING_CALL,
                    onCallClick = {
                        hudViewModel.toggleCall(
                            onStartRtcCall = startWebRtcSessionLambda,
                            onEndRtcCall = endWebRtcSessionLambda
                        )
                    },
                    onVideoClick = {
                        if (uiState.callAndChatState == CallAndChatState.VIDEO_ACTIVE) {
                            hudViewModel.startOrToggleVideoCall(
                                onStartRtcCall = startWebRtcSessionLambda,
                                onEndRtcCall = endWebRtcSessionLambda
                            )
                        } else {
                            mediaCallPermissionsRequester.ensureVideoAndAudioPermissions()
                        }
                    },
                    onSecureClick = {
                        hudViewModel.toggleSecure()
                        aiChatViewModel.setDirectP2pEncryptedMode(!uiState.isDirectP2pMode)
                    },
                    onDisconnectClick = {
                        hudViewModel.disconnectCall(onEndRtcCall = endWebRtcSessionLambda)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Top-Center Cyber-Trek Mode Switcher Pill (3D HUD | AI COMMS | 3D MESH | MCP/HLSL | PING NODE | RE-ALIGN | LOGOUT)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x99031022))
                .border(0.8.dp, HoloCyan.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            HudModeChip(
                label = stringResource(R.string.mode_native_3d),
                selected = uiState.viewMode == HoloViewMode.NATIVE_3D_HUD,
                tag = "mode_3d_hud",
                onClick = { hudViewModel.setViewMode(HoloViewMode.NATIVE_3D_HUD) }
            )
            HudModeChip(
                label = stringResource(R.string.mode_ai_chat),
                selected = uiState.viewMode == HoloViewMode.AI_CHAT,
                tag = "mode_ai_chat",
                onClick = {
                    aiChatViewModel.selectContact(uiState.selectedContactId)
                    hudViewModel.setViewMode(HoloViewMode.AI_CHAT)
                }
            )
            HudModeChip(
                label = stringResource(R.string.mode_webgl),
                selected = uiState.viewMode == HoloViewMode.THREEJS_WEBGL,
                tag = "mode_threejs",
                onClick = { hudViewModel.setViewMode(HoloViewMode.THREEJS_WEBGL) }
            )
            HudModeChip(
                label = stringResource(R.string.mode_specs),
                selected = uiState.viewMode == HoloViewMode.MCP_SHADER_LAB,
                tag = "mode_mcp_shader",
                onClick = { hudViewModel.setViewMode(HoloViewMode.MCP_SHADER_LAB) }
            )
            // Real-time Firestore RTT Ping probe & P2P control signal trigger
            Box(
                modifier = Modifier
                    .testTag("ping_node_chip")
                    .clip(RoundedCornerShape(10.dp))
                    .background(HoloCyan.copy(alpha = 0.16f))
                    .border(0.7.dp, HoloCyanBright.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable {
                        aiChatViewModel.measureRealtimeFirestorePing { rtt ->
                            hudViewModel.updateMeasuredPing(rtt)
                        }
                    }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "PING ${uiState.pingMs}ms",
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 8.sp,
                    color = HoloCyanBright
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(HoloGold.copy(alpha = 0.18f))
                    .border(0.7.dp, HoloGold.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { hudViewModel.resetGlobeOrbit() }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "RE-ALIGN",
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 8.sp,
                    color = HoloGoldBright
                )
            }
            if (onSignOut != null) {
                Box(
                    modifier = Modifier
                        .testTag("sign_out_chip")
                        .clip(RoundedCornerShape(10.dp))
                        .background(HoloRed.copy(alpha = 0.22f))
                        .border(0.7.dp, HoloRedBright.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                        .clickable {
                            rtcAudioEngine.stopCallAudioCapture()
                            rtcAudioEngine.stopPlayback()
                            onSignOut()
                        }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LOGOUT",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        color = HoloRedBright
                    )
                }
            }
        }

        // Layer 3: Multi-Turn WhatsApp + Gemini Holographic Comms Overlay
        if (uiState.viewMode == HoloViewMode.AI_CHAT) {
            HoloAiChatOverlay(
                chatState = chatState,
                onSelectContact = { contactId ->
                    hudViewModel.selectContact(contactId)
                    aiChatViewModel.selectContact(contactId)
                },
                onSelectTier = aiChatViewModel::selectModelTier,
                onSendMessage = aiChatViewModel::sendMessage,
                onDismissError = aiChatViewModel::clearError,
                onClose = { hudViewModel.setViewMode(HoloViewMode.NATIVE_3D_HUD) },
                isRecordingVoiceNote = isRecordingVoiceNote,
                recordingDurationMs = recordingDurationMs,
                liveAudioSpectrum = liveAudioSpectrum,
                playingMessageId = playingMessageId,
                playbackProgress = playbackProgress,
                onToggleVoiceRecord = {
                    if (isRecordingVoiceNote) {
                        coroutineScope.launch {
                            val (file, durationMs) = rtcAudioEngine.stopVoiceNoteRecording()
                            if (file != null) {
                                val base64Wav = rtcAudioEngine.encodeWavFileToBase64(file)
                                aiChatViewModel.sendVoiceNote(file.absolutePath, durationMs, base64Wav)
                            }
                        }
                    } else if (CameraPermissionHelper.hasAudioPermission(context)) {
                        rtcAudioEngine.startVoiceNoteRecording()
                    } else {
                        mediaCallPermissionsRequester.ensureAudioPermission()
                    }
                },
                onPlayVoiceNote = { msg ->
                    rtcAudioEngine.togglePlayVoiceNote(
                        messageId = msg.id,
                        uriPath = msg.mediaUri,
                        durationMs = msg.audioDurationMs,
                        base64WavPayload = msg.signalPayload
                    )
                },
                onSendMediaAttachment = { uriString, label, isImage, base64Payload ->
                    aiChatViewModel.sendMediaAttachment(uriString, label, isImage, base64Payload)
                },
                onTogglePeerPresence = {
                    aiChatViewModel.togglePeerOnlineStatus(chatState.activeContactId)
                },
                onOpenSecurityVerify = {
                    hudViewModel.toggleSecure()
                }
            )
        }

        // Layer 4: Cyber-Trek Liquid Glass HLSL Shader & MCP Pipeline Modal Overlay
        if (uiState.viewMode == HoloViewMode.MCP_SHADER_LAB) {
            HoloMcpShaderLabModal(
                onDismiss = { hudViewModel.setViewMode(HoloViewMode.NATIVE_3D_HUD) }
            )
        }

        // Layer 5: WhatsApp-style E2EE AES-256-GCM Security Code Verification Modal
        if (uiState.showSecurityVerificationModal) {
            val verification = remember(uiState.selectedContactId, currentUserId) {
                aiChatViewModel.getSecurityVerification(uiState.selectedContactId)
            }
            HoloSecurityVerificationModal(
                verification = verification,
                isDirectP2pMode = uiState.isDirectP2pMode,
                onToggleChannelMode = { directP2p ->
                    aiChatViewModel.setDirectP2pEncryptedMode(directP2p)
                    if (directP2p != uiState.isDirectP2pMode) {
                        hudViewModel.toggleSecure()
                    }
                },
                onDismiss = hudViewModel::dismissSecurityVerificationModal
            )
        }
    }
}

@Composable
private fun HudModeChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) HoloCyan.copy(alpha = 0.28f) else Color.Transparent)
            .border(
                width = 0.7.dp,
                color = if (selected) HoloCyanBright else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontFamily = OrbitronFontFamily,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 8.sp,
            color = if (selected) HoloWhite else HoloCyanBright.copy(alpha = 0.75f)
        )
    }
}

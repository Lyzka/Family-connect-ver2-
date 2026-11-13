package com.example.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.PeerPresenceStatus
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HoloChatOverlay(
    chatState: HoloAiChatUiState,
    onSelectContact: (String) -> Unit,
    onSelectTier: (HoloAiModelTier) -> Unit,
    onSendMessage: (String) -> Unit,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isRecordingVoiceNote: Boolean = false,
    recordingDurationMs: Long = 0L,
    liveAudioSpectrum: FloatArray = FloatArray(32) { 0f },
    playingMessageId: String? = null,
    playbackProgress: Float = 0f,
    onToggleVoiceRecord: () -> Unit = {},
    onPlayVoiceNote: (HoloChatMessage) -> Unit = {},
    onSendMediaAttachment: (uriString: String, label: String, isImage: Boolean, base64Payload: String?) -> Unit = { _, _, _, _ -> },
    onTogglePeerPresence: () -> Unit = {},
    onOpenSecurityVerify: () -> Unit = {}
) {
    HoloAiChatOverlay(
        chatState = chatState,
        onSelectContact = onSelectContact,
        onSelectTier = onSelectTier,
        onSendMessage = onSendMessage,
        onDismissError = onDismissError,
        onClose = onClose,
        modifier = modifier,
        isRecordingVoiceNote = isRecordingVoiceNote,
        recordingDurationMs = recordingDurationMs,
        liveAudioSpectrum = liveAudioSpectrum,
        playingMessageId = playingMessageId,
        playbackProgress = playbackProgress,
        onToggleVoiceRecord = onToggleVoiceRecord,
        onPlayVoiceNote = onPlayVoiceNote,
        onSendMediaAttachment = onSendMediaAttachment,
        onTogglePeerPresence = onTogglePeerPresence,
        onOpenSecurityVerify = onOpenSecurityVerify
    )
}

@Composable
fun HoloAiChatOverlay(
    chatState: HoloAiChatUiState,
    onSelectContact: (String) -> Unit,
    onSelectTier: (HoloAiModelTier) -> Unit,
    onSendMessage: (String) -> Unit,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isRecordingVoiceNote: Boolean = false,
    recordingDurationMs: Long = 0L,
    liveAudioSpectrum: FloatArray = FloatArray(32) { 0f },
    playingMessageId: String? = null,
    playbackProgress: Float = 0f,
    onToggleVoiceRecord: () -> Unit = {},
    onPlayVoiceNote: (HoloChatMessage) -> Unit = {},
    onSendMediaAttachment: (uriString: String, label: String, isImage: Boolean, base64Payload: String?) -> Unit = { _, _, _, _ -> },
    onTogglePeerPresence: () -> Unit = {},
    onOpenSecurityVerify: () -> Unit = {}
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = chatState.activeMessages
    val isBusy = chatState.generationState is ChatGenerationState.Loading ||
        chatState.generationState is ChatGenerationState.Streaming
    val peerPresence = chatState.activePeerPresence

    // Zero-permission Android Photo Picker (compliant with Google Play policy)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val base64Jpeg = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val original = BitmapFactory.decodeStream(stream) ?: return@withContext null
                            val maxDim = 320
                            val ratio = minOf(
                                maxDim.toFloat() / original.width.coerceAtLeast(1),
                                maxDim.toFloat() / original.height.coerceAtLeast(1),
                                1f
                            )
                            val targetW = (original.width * ratio).toInt().coerceAtLeast(1)
                            val targetH = (original.height * ratio).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(original, targetW, targetH, true)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 64, out)
                            val bytes = out.toByteArray()
                            if (bytes.size <= 180_000) {
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            } else {
                                null
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                onSendMediaAttachment(
                    uri.toString(),
                    "OPTICAL_SCAN_${System.currentTimeMillis() % 10000}.JPG",
                    true,
                    base64Jpeg
                )
            }
        }
    }

    // Auto-scroll to bottom when new messages or streaming chunks arrive
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, messages.lastOrNull()?.receiptStatus) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xD802060D))
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("holo_ai_chat_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 860.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xF2051529),
                            Color(0xF5020A16)
                        )
                    )
                )
                .border(1.5.dp, HoloCyan.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            // ================= 1. TOP HEADER: PERSONA ROLE, PRESENCE & E2EE BADGE =================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // LCARS Vertical Cyan Accent Bar
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(34.dp)
                            .background(HoloCyan)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "HOLO-COMMS // ${chatState.activePersona.callSign}",
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.3.sp,
                                color = HoloWhite
                            )
                            // Real-time Peer Presence Pill (clickable to toggle ONLINE <-> OFFLINE AI Fallback)
                            val presenceColor = when (peerPresence) {
                                PeerPresenceStatus.ONLINE -> HoloCyanBright
                                PeerPresenceStatus.TYPING -> HoloGoldBright
                                PeerPresenceStatus.OFFLINE -> HoloGold
                            }
                            val presenceText = when (peerPresence) {
                                PeerPresenceStatus.ONLINE -> "● ONLINE (P2P)"
                                PeerPresenceStatus.TYPING -> "● TYPING..."
                                PeerPresenceStatus.OFFLINE -> "● OFFLINE // AI BOT"
                            }
                            Box(
                                modifier = Modifier
                                    .testTag("chat_peer_presence_badge")
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(presenceColor.copy(alpha = 0.16f))
                                    .border(0.8.dp, presenceColor.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                    .clickable(onClick = onTogglePeerPresence)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = presenceText,
                                    fontFamily = OrbitronFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp,
                                    color = presenceColor
                                )
                            }
                        }
                        val onlinePeersCount = chatState.globalOnlineOperators.count { it.presence != "OFFLINE" }.coerceAtLeast(1)
                        Text(
                            text = "ROOM: /global_comms_rooms/${chatState.activeSharedRoomId} • GLOBAL ONLINE: $onlinePeersCount • AES-256-GCM",
                            fontFamily = RajdhaniFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.0.sp,
                            color = HoloGoldBright
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onOpenSecurityVerify,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HoloGold.copy(alpha = 0.16f))
                            .border(1.dp, HoloGoldBright.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .testTag("chat_e2ee_verify_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Verify Session E2EE Key",
                            tint = HoloGoldBright,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HoloCyan.copy(alpha = 0.16f))
                            .border(1.dp, HoloCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .testTag("ai_chat_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Holographic Chat",
                            tint = HoloWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Contact Persona Switcher + Gemini Model Tier Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                // Contact Persona & Live Remote Operator Tabs (/global_presence/{userId}) with Unread Indicator
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chatState.dynamicPersonas.values.forEach { persona ->
                        val isSelected = persona.contactId == chatState.activeContactId
                        val unread = chatState.unreadCountByContact[persona.contactId] ?: 0
                        Box(
                            modifier = Modifier
                                .testTag("chat_persona_${persona.contactId.lowercase()}")
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSelected) HoloCyan.copy(alpha = 0.28f)
                                    else Color(0x66031020)
                                )
                                .border(
                                    width = if (isSelected) 1.3.dp else 0.8.dp,
                                    color = if (isSelected) HoloCyanBright else HoloCyan.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectContact(persona.contactId) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = persona.callSign,
                                    fontFamily = RajdhaniFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isSelected) HoloWhite else HoloCyanBright.copy(alpha = 0.8f)
                                )
                                if (unread > 0 && !isSelected) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clip(CircleShape)
                                            .background(HoloGoldBright)
                                    ) {
                                        Text(
                                            text = unread.toString(),
                                            fontFamily = OrbitronFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 8.sp,
                                            color = HoloBlack
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Model Tier Selector (FAST: 3.5-flash-lite | GENERAL: 3.8-flash | COMPLEX: 4-argon)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoloAiModelTier.entries.forEach { tier ->
                        val isSelected = tier == chatState.selectedTier
                        Box(
                            modifier = Modifier
                                .testTag("model_tier_${tier.name.lowercase()}")
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSelected) HoloGold.copy(alpha = 0.26f)
                                    else Color(0x66031020)
                                )
                                .border(
                                    width = if (isSelected) 1.3.dp else 0.8.dp,
                                    color = if (isSelected) HoloGoldBright else HoloGold.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectTier(tier) }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = tier.badgeLabel,
                                fontFamily = OrbitronFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 9.sp,
                                color = if (isSelected) HoloGoldBright else HoloGold.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ================= 2. SCROLLABLE MULTI-TURN MESSAGE THREAD =================
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HoloBlack.copy(alpha = 0.72f))
                    .border(1.dp, HoloCyan.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp)
                    .testTag("ai_chat_message_list")
            ) {
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    HoloChatBubble(
                        message = message,
                        isPlayingVoiceNote = playingMessageId == message.id,
                        playbackProgress = if (playingMessageId == message.id) playbackProgress else 0f,
                        onPlayVoiceNote = { onPlayVoiceNote(message) }
                    )
                }
            }

            // ================= 3. LIVE VOICE NOTE RECORDING WAVEFORM BAR (IF RECORDING) =================
            if (isRecordingVoiceNote) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xD91A0610))
                        .border(1.2.dp, HoloRedBright, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                        .testTag("voice_recording_live_bar")
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(HoloRedBright)
                    )
                    Text(
                        text = "REC VOICE NOTE // %02d:%02d".format(
                            (recordingDurationMs / 1000) / 60,
                            (recordingDurationMs / 1000) % 60
                        ),
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = HoloWhite
                    )
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                    ) {
                        val barCount = liveAudioSpectrum.size.coerceAtLeast(1)
                        val step = size.width / barCount
                        val barW = (step * 0.62f).coerceAtLeast(2f)
                        val cy = size.height * 0.5f
                        for (i in 0 until barCount) {
                            val amp = liveAudioSpectrum[i].coerceIn(0.1f, 1f)
                            val halfH = amp * size.height * 0.46f
                            val x = i * step + (step - barW) * 0.5f
                            drawRoundRect(
                                color = if (i % 3 == 0) HoloGoldBright else HoloCyanBright,
                                topLeft = Offset(x, cy - halfH),
                                size = Size(barW, halfH * 2f),
                                cornerRadius = CornerRadius(2f, 2f),
                                blendMode = BlendMode.Plus
                            )
                        }
                    }
                    Text(
                        text = "TAP MIC TO SEND",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = HoloGoldBright,
                        modifier = Modifier.clickable { onToggleVoiceRecord() }
                    )
                }
            }

            // ================= 4. ERROR BANNER (IF ANY) =================
            val errorState = chatState.generationState as? ChatGenerationState.Error
            if (errorState != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(HoloRed.copy(alpha = 0.2f))
                        .border(1.dp, HoloRedBright, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("ai_chat_error_banner")
                ) {
                    Text(
                        text = "TRANSMISSION ERROR: ${errorState.message}",
                        fontFamily = RajdhaniFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = HoloRedBright,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "DISMISS",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = HoloWhite,
                        modifier = Modifier
                            .clickable(onClick = onDismissError)
                            .padding(start = 10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ================= 5. QUICK TACTICAL PROMPTS =================
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                chatState.activePersona.quickPrompts.forEach { promptChip ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(HoloCyan.copy(alpha = 0.12f))
                            .border(0.8.dp, HoloCyan.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable(enabled = !isBusy) {
                                onSendMessage(promptChip)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "> $promptChip",
                            fontFamily = RajdhaniFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = HoloCyanBright
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ================= 6. INPUT FIELD + ATTACHMENT + VOICE NOTE MIC + SEND =================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Photo / HUD Telemetry Attachment Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HoloCyan.copy(alpha = 0.18f))
                        .border(1.2.dp, HoloCyan.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                        .clickable(enabled = !isBusy) {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .testTag("chat_attach_media_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach Holographic Photo or Telemetry",
                        tint = HoloCyanBright,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Voice Note Record / Transmit Button (Custom Vector Mic Icon)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isRecordingVoiceNote) HoloRed.copy(alpha = 0.36f)
                            else HoloGold.copy(alpha = 0.18f)
                        )
                        .border(
                            width = 1.3.dp,
                            color = if (isRecordingVoiceNote) HoloRedBright else HoloGoldBright.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !isBusy) { onToggleVoiceRecord() }
                        .testTag("chat_voice_note_button")
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val w = size.width
                        val h = size.height
                        val tint = if (isRecordingVoiceNote) HoloRedBright else HoloGoldBright
                        // Microphone capsule
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(w * 0.32f, h * 0.10f),
                            size = Size(w * 0.36f, h * 0.48f),
                            cornerRadius = CornerRadius(w * 0.18f, w * 0.18f),
                            style = Stroke(width = 2.2f)
                        )
                        // U-shaped cradle
                        drawArc(
                            color = tint,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(w * 0.18f, h * 0.25f),
                            size = Size(w * 0.64f, h * 0.48f),
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )
                        // Stand & base
                        drawLine(
                            color = tint,
                            start = Offset(w * 0.5f, h * 0.73f),
                            end = Offset(w * 0.5f, h * 0.90f),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = tint,
                            start = Offset(w * 0.30f, h * 0.90f),
                            end = Offset(w * 0.70f, h * 0.90f),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = "Transmit E2EE message to ${chatState.activePersona.callSign}...",
                            fontFamily = RajdhaniFontFamily,
                            fontSize = 13.sp,
                            color = HoloCyanBright.copy(alpha = 0.55f)
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = RajdhaniFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = HoloWhite
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank() && !isBusy) {
                                val toSend = inputText
                                inputText = ""
                                onSendMessage(toSend)
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HoloCyanBright,
                        unfocusedBorderColor = HoloCyan.copy(alpha = 0.5f),
                        focusedContainerColor = Color(0x99031020),
                        unfocusedContainerColor = Color(0x66031020),
                        cursorColor = HoloCyanBright
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_chat_input")
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isBusy) HoloCyan.copy(alpha = 0.15f)
                            else HoloCyan.copy(alpha = 0.32f)
                        )
                        .border(
                            width = 1.4.dp,
                            color = if (isBusy) HoloCyan.copy(alpha = 0.4f) else HoloCyanBright,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = !isBusy && inputText.isNotBlank()) {
                            val toSend = inputText
                            inputText = ""
                            onSendMessage(toSend)
                        }
                        .testTag("ai_chat_send_button")
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            color = HoloCyanBright,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Holographic Transmission",
                            tint = HoloWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HoloChatBubble(
    message: HoloChatMessage,
    isPlayingVoiceNote: Boolean,
    playbackProgress: Float,
    onPlayVoiceNote: () -> Unit
) {
    val isUser = message.isUser
    val bubbleBorderColor = if (isUser) HoloGold.copy(alpha = 0.80f) else HoloCyan.copy(alpha = 0.80f)
    val bubbleBgColor = if (isUser) Color(0x38FFAA00) else Color(0x2600F3FF)

    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(HoloCyanBright)
                )
            }
            Text(
                text = message.senderLabel,
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = if (isUser) HoloGoldBright else HoloCyanBright
            )
            Text(
                text = "[${message.modelBadge}]",
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = HoloWhite.copy(alpha = 0.65f)
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 580.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isUser) 10.dp else 2.dp,
                        topEnd = if (isUser) 2.dp else 10.dp,
                        bottomStart = 10.dp,
                        bottomEnd = 10.dp
                    )
                )
                .background(bubbleBgColor)
                .border(
                    width = 1.dp,
                    color = bubbleBorderColor,
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 10.dp else 2.dp,
                        topEnd = if (isUser) 2.dp else 10.dp,
                        bottomStart = 10.dp,
                        bottomEnd = 10.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when (message.messageType) {
                HoloMessageType.VOICE_NOTE -> {
                    HoloVoiceNoteBubbleContent(
                        message = message,
                        isPlaying = isPlayingVoiceNote,
                        progress = playbackProgress,
                        onPlayToggle = onPlayVoiceNote
                    )
                }
                HoloMessageType.IMAGE, HoloMessageType.FILE -> {
                    HoloMediaAttachmentBubbleContent(message = message)
                }
                HoloMessageType.TEXT,
                HoloMessageType.CONTROL_SIGNAL,
                HoloMessageType.WEBRTC_OFFER,
                HoloMessageType.WEBRTC_ANSWER,
                HoloMessageType.WEBRTC_ICE -> {
                    Text(
                        text = if (message.text.isEmpty() && message.isStreaming) {
                            "SYNTHESIZING HOLOGRAPHIC STREAM..."
                        } else {
                            message.text
                        },
                        fontFamily = RajdhaniFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = HoloWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Footer Row: E2EE Cipher Preview + Timestamp + Futuristic WhatsApp Receipt Bars (| / ||)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                if (!message.cipherHex.isNullOrBlank()) {
                    Text(
                        text = "AES:${message.cipherHex.take(8)}",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 7.sp,
                        color = HoloCyanBright.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = message.timestampText,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = HoloWhite.copy(alpha = 0.72f)
                )
                if (isUser) {
                    HoloFuturisticReceiptIndicator(status = message.receiptStatus)
                }
            }
        }
    }
}

/**
 * Futuristic sci-fi equivalent of WhatsApp delivery checkmarks:
 * - SENT: 1 cyan vertical bar (`|`)
 * - DELIVERED: 2 cyan vertical bars (`||`)
 * - READ: 2 glowing neon cyan bars (`||`) with additive halo
 */
@Composable
private fun HoloFuturisticReceiptIndicator(status: HoloReceiptStatus) {
    val isRead = status == HoloReceiptStatus.READ
    val barText = when (status) {
        HoloReceiptStatus.SENT -> "|"
        HoloReceiptStatus.DELIVERED -> "||"
        HoloReceiptStatus.READ -> "||"
    }
    val barColor = when (status) {
        HoloReceiptStatus.SENT -> HoloCyan.copy(alpha = 0.65f)
        HoloReceiptStatus.DELIVERED -> HoloCyanBright.copy(alpha = 0.85f)
        HoloReceiptStatus.READ -> HoloCyanBright
    }

    Text(
        text = barText,
        style = TextStyle(
            fontFamily = OrbitronFontFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = barColor,
            shadow = if (isRead) {
                Shadow(
                    color = HoloCyanBright,
                    offset = Offset.Zero,
                    blurRadius = 12f
                )
            } else {
                null
            }
        ),
        modifier = Modifier.testTag("receipt_status_${status.name.lowercase()}")
    )
}

@Composable
private fun HoloVoiceNoteBubbleContent(
    message: HoloChatMessage,
    isPlaying: Boolean,
    progress: Float,
    onPlayToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x66021020))
            .border(0.8.dp, HoloCyanBright.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable { onPlayToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("voice_note_player_${message.id}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isPlaying) HoloGoldBright else HoloCyanBright)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop Voice Note" else "Play Voice Note",
                tint = HoloBlack,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.text,
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = HoloWhite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                val bars = 28
                val step = size.width / bars
                val barW = (step * 0.58f).coerceAtLeast(2f)
                val cy = size.height * 0.5f
                for (i in 0 until bars) {
                    val frac = i.toFloat() / bars
                    val seed = abs(sin(i * 0.9f + message.id.hashCode() * 0.01f))
                    val halfH = (0.2f + 0.75f * seed) * size.height * 0.45f
                    val isPlayed = isPlaying && frac <= progress
                    drawRoundRect(
                        color = if (isPlayed) HoloGoldBright else HoloCyanBright.copy(alpha = 0.72f),
                        topLeft = Offset(i * step, cy - halfH),
                        size = Size(barW, halfH * 2f),
                        cornerRadius = CornerRadius(2f, 2f),
                        blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }
}

@Composable
private fun HoloMediaAttachmentBubbleContent(message: HoloChatMessage) {
    val decodedSignalBitmap = remember(message.signalPayload) {
        val b64 = message.signalPayload
        if (b64.isNullOrBlank()) {
            null
        } else {
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC021226))
                .border(1.2.dp, HoloCyanBright, RoundedCornerShape(8.dp))
                .testTag("media_attachment_frame_${message.id}")
        ) {
            val uri = message.mediaUri
            if (decodedSignalBitmap != null) {
                Image(
                    bitmap = decodedSignalBitmap,
                    contentDescription = message.text,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!uri.isNullOrBlank()) {
                AsyncImage(
                    model = uri,
                    contentDescription = message.text,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Procedural Holographic Recon Scan Frame & Wireframe Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f
                val cy = h * 0.5f

                // Grid lines
                for (i in 1..5) {
                    val y = (i / 6f) * h
                    drawLine(
                        color = HoloCyan.copy(alpha = 0.25f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }
                for (i in 1..7) {
                    val x = (i / 8f) * w
                    drawLine(
                        color = HoloCyan.copy(alpha = 0.20f),
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1f
                    )
                }

                // Wireframe isometric telemetry polygon in the center
                val r = minOf(w, h) * 0.30f
                val hexPath = Path()
                for (k in 0..6) {
                    val a = k * (Math.PI / 3.0)
                    val px = cx + (kotlin.math.cos(a) * r).toFloat()
                    val py = cy + (sin(a) * r).toFloat()
                    if (k == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                }
                drawPath(
                    path = hexPath,
                    color = HoloCyanBright.copy(alpha = 0.8f),
                    style = Stroke(width = 1.8f),
                    blendMode = BlendMode.Plus
                )
                drawCircle(
                    color = HoloGoldBright.copy(alpha = 0.75f),
                    radius = r * 0.45f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.4f),
                    blendMode = BlendMode.Plus
                )

                // HUD Corner Brackets
                val b = 16f
                drawLine(HoloGoldBright, Offset(6f, 6f), Offset(6f + b, 6f), 2.2f)
                drawLine(HoloGoldBright, Offset(6f, 6f), Offset(6f, 6f + b), 2.2f)
                drawLine(HoloGoldBright, Offset(w - 6f, h - 6f), Offset(w - 6f - b, h - 6f), 2.2f)
                drawLine(HoloGoldBright, Offset(w - 6f, h - 6f), Offset(w - 6f, h - 6f - b), 2.2f)
            }
        }

        Text(
            text = message.text,
            fontFamily = OrbitronFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = HoloCyanBright
        )
    }
}

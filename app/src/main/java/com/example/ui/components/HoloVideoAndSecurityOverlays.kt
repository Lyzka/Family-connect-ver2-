package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.HoloBlack
import com.example.ui.theme.HoloCyan
import com.example.ui.theme.HoloCyanBright
import com.example.ui.theme.HoloGold
import com.example.ui.theme.HoloGoldBright
import com.example.ui.theme.HoloWhite
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import com.example.util.E2eeSecurityVerification
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Integrated Holographic Video Module inscribed directly inside the central 3D sphere
 * when a WebRTC video call (VIDEO_ACTIVE) is running.
 * Renders the live remote peer camera stream (decoded from [remoteFrameBase64]) when received
 * from the remote device via `/global_comms_rooms/{roomId}/rtc_signals`, layered with
 * holographic scanlines and acoustic telemetry.
 */
@Composable
fun HoloCentralPeerVideoSphere(
    peerId: String,
    timeSec: Float,
    voiceModulationLevel: Float,
    sphereDiameter: Dp,
    modifier: Modifier = Modifier,
    remoteFrameBase64: String? = null
) {
    val decodedRemoteBitmap = remember(remoteFrameBase64) {
        if (remoteFrameBase64.isNullOrBlank()) {
            null
        } else {
            try {
                val bytes = Base64.decode(remoteFrameBase64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sphereDiameter)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xCC042848),
                        Color(0x99021326),
                        Color(0x2200F3FF)
                    )
                )
            )
            .border(1.8.dp, HoloCyanBright.copy(alpha = 0.85f), CircleShape)
            .testTag("central_peer_video_sphere")
    ) {
        // If remote device is streaming real camera frames, render the live feed inside the sphere
        if (decodedRemoteBitmap != null) {
            Image(
                bitmap = decodedRemoteBitmap,
                contentDescription = "Remote peer live video stream from $peerId",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.88f)
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.52f
            val yaw = sin(timeSec * 1.1f) * 0.18f
            val cosY = cos(yaw)
            val sinY = sin(yaw)

            // 1. Volumetric holographic scanlines sweeping vertically
            val scanRows = 28
            for (r in 0 until scanRows) {
                val y = (r.toFloat() / scanRows) * h
                val scanPulse = 0.12f + 0.14f * kotlin.math.abs(sin(timeSec * 3.5f + r * 0.35f))
                drawLine(
                    color = HoloCyan.copy(alpha = scanPulse),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.2f,
                    blendMode = BlendMode.Plus
                )
            }

            // 2. If no remote camera frame has arrived yet, render 3D wireframe head/torso projection
            if (decodedRemoteBitmap == null) {
                fun proj(x3: Float, y3: Float, z3: Float): Offset {
                    val rx = x3 * cosY + z3 * sinY
                    val rz = -x3 * sinY + z3 * cosY
                    val p = 1f + rz * 0.2f
                    return Offset(cx + rx * w * 0.42f * p, cy + y3 * h * 0.42f)
                }

                val jawOpen = voiceModulationLevel * 0.08f
                val latitudes = listOf(
                    Triple(-0.74f, 0.24f, 0.26f),
                    Triple(-0.56f, 0.38f, 0.40f),
                    Triple(-0.36f, 0.40f, 0.42f),
                    Triple(-0.16f, 0.36f, 0.39f),
                    Triple(0.02f + jawOpen * 0.5f, 0.28f, 0.31f),
                    Triple(0.18f + jawOpen, 0.17f, 0.20f),
                    Triple(0.40f, 0.25f, 0.22f),
                    Triple(0.68f, 0.76f, 0.35f)
                )
                val cols = 12
                val pts = Array(latitudes.size) { rIdx ->
                    val (ly, rx, rz) = latitudes[rIdx]
                    Array(cols) { cIdx ->
                        val frac = cIdx.toFloat() / (cols - 1)
                        val theta = (frac - 0.5f) * 3.14159f
                        proj(sin(theta) * rx, ly, cos(theta) * rz)
                    }
                }

                for (r in pts.indices) {
                    for (c in 0 until cols) {
                        val p = pts[r][c]
                        if (c + 1 < cols) {
                            drawLine(
                                color = HoloCyanBright.copy(alpha = 0.72f),
                                start = p,
                                end = pts[r][c + 1],
                                strokeWidth = 1.3f,
                                blendMode = BlendMode.Plus
                            )
                        }
                        if (r + 1 < pts.size) {
                            drawLine(
                                color = HoloCyan.copy(alpha = 0.65f),
                                start = p,
                                end = pts[r + 1][c],
                                strokeWidth = 1.2f,
                                blendMode = BlendMode.Plus
                            )
                        }
                        drawCircle(
                            color = HoloWhite.copy(alpha = 0.85f),
                            radius = 1.6f,
                            center = p,
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }

            // 3. Acoustic Voice-Reactive Vocoder Wave inside the Hologram
            val mouthY = if (decodedRemoteBitmap != null) h * 0.82f else cy + h * 0.04f
            val mouthHalfW = w * 0.18f
            val wavePath = Path()
            val segs = 16
            for (i in 0..segs) {
                val frac = i.toFloat() / segs
                val mx = cx - mouthHalfW + frac * mouthHalfW * 2f
                val env = sin(frac * 3.14159f)
                val my = mouthY + sin(timeSec * 18f + i * 0.9f) * h * 0.04f * voiceModulationLevel * env
                if (i == 0) wavePath.moveTo(mx, my) else wavePath.lineTo(mx, my)
            }
            drawPath(
                path = wavePath,
                color = HoloGoldBright,
                style = Stroke(width = 2.2f),
                blendMode = BlendMode.Plus
            )
        }

        // Top HUD badge inside video sphere
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xAA020D1C))
                .border(0.8.dp, HoloCyanBright.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (decodedRemoteBitmap != null) {
                    "LIVE STREAM // $peerId"
                } else {
                    "LIVE WebRTC VP9 // $peerId"
                },
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                color = HoloCyanBright
            )
        }
    }
}

/**
 * Picture-in-Picture (PiP) Local Camera Preview positioned in the corner of the HUD panel
 * during an active WebRTC Video call. Uses CameraX [PreviewView] and [ImageAnalysis] to
 * capture real live frames from the device camera and stream them to the remote peer.
 */
@Composable
fun HoloLocalCameraPipPreview(
    hasCameraPermission: Boolean,
    timeSec: Float,
    modifier: Modifier = Modifier,
    onFrameCaptured: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(hasCameraPermission, lifecycleOwner) {
        var cameraProvider: ProcessCameraProvider? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var lastFrameSentMs = 0L

        if (hasCameraPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val now = SystemClock.elapsedRealtime()
                            if (onFrameCaptured != null && now - lastFrameSentMs >= 950L) {
                                lastFrameSentMs = now
                                try {
                                    val rawBitmap = imageProxy.toBitmap()
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val rotated = if (rotation != 0) {
                                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                        Bitmap.createBitmap(
                                            rawBitmap,
                                            0,
                                            0,
                                            rawBitmap.width,
                                            rawBitmap.height,
                                            matrix,
                                            true
                                        )
                                    } else {
                                        rawBitmap
                                    }
                                    val scaled = Bitmap.createScaledBitmap(rotated, 160, 160, true)
                                    val out = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 58, out)
                                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                    if (b64.isNotBlank() && b64.length <= 120_000) {
                                        onFrameCaptured(b64)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            imageProxy.close()
                        }

                        provider.unbindAll()
                        val selector = when {
                            runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) ->
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) ->
                                CameraSelector.DEFAULT_BACK_CAMERA
                            else -> null
                        }
                        if (selector != null) {
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                        }
                    } catch (_: Exception) {
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
        onDispose {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            try {
                analysisExecutor.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 118.dp, height = 88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xD9031224))
            .border(1.5.dp, HoloCyanBright, RoundedCornerShape(10.dp))
            .testTag("local_camera_pip_preview")
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Holographic HUD Reticle & Scanline overlay on top of PiP camera
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val scanY = ((timeSec * 0.45f) % 1f) * h
            drawLine(
                color = HoloCyanBright.copy(alpha = 0.55f),
                start = Offset(0f, scanY),
                end = Offset(w, scanY),
                strokeWidth = 2f,
                blendMode = BlendMode.Plus
            )
            // Corner targeting brackets
            val len = 12f
            drawLine(HoloGoldBright, Offset(4f, 4f), Offset(4f + len, 4f), 2f)
            drawLine(HoloGoldBright, Offset(4f, 4f), Offset(4f, 4f + len), 2f)
            drawLine(HoloGoldBright, Offset(w - 4f, h - 4f), Offset(w - 4f - len, h - 4f), 2f)
            drawLine(HoloGoldBright, Offset(w - 4f, h - 4f), Offset(w - 4f, h - 4f - len), 2f)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xAA020A14))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = "LOCAL CAM // PiP",
                fontFamily = OrbitronFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 7.sp,
                color = HoloGoldBright
            )
        }
    }
}

/**
 * WhatsApp-style End-to-End Encryption (E2EE - AES-256-GCM) Security Code Verification Modal
 * triggered when the operator taps the SECURE (padlock) button.
 */
@Composable
fun HoloSecurityVerificationModal(
    verification: E2eeSecurityVerification,
    isDirectP2pMode: Boolean,
    onToggleChannelMode: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC010711))
            .clickable(onClick = onDismiss)
            .padding(16.dp)
            .testTag("e2ee_security_modal")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF5051A33),
                            Color(0xFA020B18)
                        )
                    )
                )
                .border(1.5.dp, HoloCyanBright, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "E2EE Verified",
                        tint = HoloCyanBright,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "E2EE QUANTUM SECURITY VERIFICATION",
                            fontFamily = OrbitronFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = HoloWhite
                        )
                        Text(
                            text = "PEER: ${verification.peerId} // ${verification.cipherSuite}",
                            fontFamily = RajdhaniFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = HoloGoldBright
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("close_security_modal_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Security Verification",
                        tint = HoloWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 60-Digit Safety Number Grid (4 rows x 3 blocks of 5 digits, like WhatsApp)
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HoloBlack.copy(alpha = 0.78f))
                    .border(1.dp, HoloCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(vertical = 12.dp, horizontal = 14.dp)
            ) {
                Text(
                    text = "60-DIGIT SESSION SAFETY CODE (SHA-256 / AES-GCM)",
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = HoloCyanBright
                )
                Spacer(modifier = Modifier.height(4.dp))
                verification.safetyNumberBlocks.chunked(3).forEach { rowBlocks ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowBlocks.forEach { block ->
                            Text(
                                text = block,
                                fontFamily = OrbitronFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 2.sp,
                                color = HoloWhite
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "KEY DIGEST: ${verification.keyFingerprintHex}",
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = HoloGoldBright,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Channel Routing Switcher: AI Hybrid Mode vs Direct Encrypted P2P Channel
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isDirectP2pMode) HoloCyan.copy(alpha = 0.28f) else Color(0x66031020))
                        .border(
                            width = if (!isDirectP2pMode) 1.4.dp else 0.8.dp,
                            color = if (!isDirectP2pMode) HoloCyanBright else HoloCyan.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onToggleChannelMode(false) }
                        .padding(vertical = 8.dp)
                        .testTag("mode_hybrid_ai_channel")
                ) {
                    Text(
                        text = "HYBRID AI + E2EE",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (!isDirectP2pMode) HoloWhite else HoloCyanBright.copy(alpha = 0.7f)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDirectP2pMode) HoloGold.copy(alpha = 0.28f) else Color(0x66031020))
                        .border(
                            width = if (isDirectP2pMode) 1.4.dp else 0.8.dp,
                            color = if (isDirectP2pMode) HoloGoldBright else HoloGold.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onToggleChannelMode(true) }
                        .padding(vertical = 8.dp)
                        .testTag("mode_direct_p2p_channel")
                ) {
                    Text(
                        text = "DIRECT P2P E2EE",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (isDirectP2pMode) HoloGoldBright else HoloGold.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

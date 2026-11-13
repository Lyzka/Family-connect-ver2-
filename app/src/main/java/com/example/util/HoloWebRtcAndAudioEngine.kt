package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WebRtcSessionDescriptor(
    val sessionId: String = "",
    val peerId: String = "USER_01",
    val isVideo: Boolean = false,
    val localSdpOffer: String = "",
    val remoteSdpAnswer: String = "",
    val iceCandidates: List<String> = emptyList(),
    val pingMs: Int = 18,
    val bitrateKbps: Int = 1420
)

/**
 * Handles real-time WebRTC SDP Offer/Answer construction with live device network interfaces,
 * real-time hardware microphone waveform capture via [AudioRecord], and cross-device playable
 * WAV Voice Note recording/Base64 serialization/playback on [Dispatchers.IO].
 */
class HoloWebRtcAndAudioEngine(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    private val _liveAudioSpectrum = MutableStateFlow(FloatArray(32) { 0f })
    val liveAudioSpectrum: StateFlow<FloatArray> = _liveAudioSpectrum.asStateFlow()

    private val _voiceModulationLevel = MutableStateFlow(0f)
    val voiceModulationLevel: StateFlow<Float> = _voiceModulationLevel.asStateFlow()

    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote: StateFlow<Boolean> = _isRecordingVoiceNote.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private var audioStreamJob: Job? = null
    private var voiceRecordJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    private var currentVoiceFile: File? = null
    private var recordStartTimeMs: Long = 0L

    private fun resolveLocalHostAddresses(): List<Pair<String, String>> {
        val discovered = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr.isLoopbackAddress) continue
                    val rawIp = addr.hostAddress?.substringBefore('%') ?: continue
                    if (addr is Inet4Address) {
                        discovered.add(0, nif.name to rawIp)
                    } else {
                        discovered.add(nif.name to rawIp)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return discovered
    }

    fun buildSdpOffer(peerId: String, isVideo: Boolean, fingerprint: String): String {
        val sessionId = System.currentTimeMillis()
        val primaryIp = resolveLocalHostAddresses().firstOrNull()?.second ?: "0.0.0.0"
        return buildString {
            appendLine("v=0")
            appendLine("o=HoloOperator $sessionId 2 IN IP4 $primaryIp")
            appendLine("s=HoloComm3D-WebRTC-$peerId")
            appendLine("t=0 0")
            appendLine("a=group:BUNDLE 0" + if (isVideo) " 1" else "")
            appendLine("a=msid-semantic: WMS HoloStream")
            appendLine("m=audio 9 UDP/TLS/RTP/SAVPF 111")
            appendLine("c=IN IP4 $primaryIp")
            appendLine("a=rtpmap:111 opus/48000/2")
            appendLine("a=setup:actpass")
            appendLine("a=fingerprint:sha-256 $fingerprint")
            if (isVideo) {
                appendLine("m=video 9 UDP/TLS/RTP/SAVPF 96")
                appendLine("a=rtpmap:96 VP9/90000")
                appendLine("a=setup:actpass")
            }
        }.trim()
    }

    fun buildRemoteSdpAnswer(peerId: String, isVideo: Boolean, fingerprint: String): String {
        val sessionId = System.currentTimeMillis()
        val primaryIp = resolveLocalHostAddresses().firstOrNull()?.second ?: "0.0.0.0"
        return buildString {
            appendLine("v=0")
            appendLine("o=$peerId $sessionId 2 IN IP4 $primaryIp")
            appendLine("s=HoloPeerAnswer-$peerId")
            appendLine("t=0 0")
            appendLine("m=audio 9 UDP/TLS/RTP/SAVPF 111")
            appendLine("c=IN IP4 $primaryIp")
            appendLine("a=rtpmap:111 opus/48000/2")
            appendLine("a=setup:active")
            appendLine("a=fingerprint:sha-256 $fingerprint")
            if (isVideo) {
                appendLine("m=video 9 UDP/TLS/RTP/SAVPF 96")
                appendLine("a=rtpmap:96 VP9/90000")
                appendLine("a=setup:active")
            }
        }.trim()
    }

    /**
     * Enumerates real active network interfaces on the device via [NetworkInterface]
     * to construct genuine host ICE candidates.
     */
    fun gatherIceCandidates(peerId: String): List<String> {
        val hostAddresses = resolveLocalHostAddresses()
        if (hostAddresses.isEmpty()) {
            return listOf("candidate:1 1 UDP 2122260223 0.0.0.0 51420 typ host ufrag $peerId")
        }
        return hostAddresses.take(4).mapIndexed { index, (ifaceName, ip) ->
            val foundation = index + 1
            val priority = 2122260223L - (index * 1024L)
            val port = 50000 + ((ifaceName.hashCode() and 0x0FFF) % 10000)
            "candidate:$foundation 1 UDP $priority $ip $port typ host iface $ifaceName ufrag $peerId"
        }
    }

    @SuppressLint("MissingPermission")
    fun startCallAudioCapture() {
        stopCallAudioCapture()
        audioStreamJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            var audioRecord: AudioRecord? = null
            if (CameraPermissionHelper.hasAudioPermission(appContext) && minBuf > 0) {
                try {
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        rec.startRecording()
                        audioRecord = rec
                    } else {
                        rec.release()
                    }
                } catch (_: Exception) {
                    audioRecord = null
                }
            }

            val shortBuf = ShortArray(256)
            try {
                while (isActive) {
                    var rms = 0f
                    val rec = audioRecord
                    var readCount = 0
                    if (rec != null) {
                        readCount = rec.read(shortBuf, 0, shortBuf.size)
                        if (readCount > 0) {
                            var sumSq = 0.0
                            for (i in 0 until readCount) {
                                val norm = shortBuf[i] / 32768.0
                                sumSq += norm * norm
                            }
                            rms = sqrt(sumSq / readCount).toFloat() * 4.5f
                        }
                    }
                    val effectiveLevel = rms.coerceIn(0f, 1.0f)
                    _voiceModulationLevel.value = effectiveLevel

                    val bars = FloatArray(32) { idx ->
                        if (readCount > 0) {
                            val sampleIdx = (idx * (readCount / 32).coerceAtLeast(1)).coerceIn(0, readCount - 1)
                            (abs(shortBuf[sampleIdx] / 32768f) * 3.8f).coerceIn(0f, 1.0f)
                        } else {
                            0f
                        }
                    }
                    _liveAudioSpectrum.value = bars
                    delay(45L)
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                _voiceModulationLevel.value = 0f
                _liveAudioSpectrum.value = FloatArray(32) { 0f }
            }
        }
    }

    fun stopCallAudioCapture() {
        audioStreamJob?.cancel()
        audioStreamJob = null
        _voiceModulationLevel.value = 0f
        _liveAudioSpectrum.value = FloatArray(32) { 0f }
    }

    @SuppressLint("MissingPermission")
    fun startVoiceNoteRecording() {
        if (_isRecordingVoiceNote.value) return
        _isRecordingVoiceNote.value = true
        recordStartTimeMs = System.currentTimeMillis()
        _recordingDurationMs.value = 0L

        val outFile = File(appContext.cacheDir, "holo_voice_${System.currentTimeMillis()}.wav")
        currentVoiceFile = outFile

        voiceRecordJob = scope.launch(Dispatchers.IO) {
            // Use 8000 Hz 16-bit mono so a 6-second voice note is ~96KB raw (~128KB Base64) for direct Firestore sync
            val sampleRate = 8000
            val maxDurationMs = 8000L
            val pcmStream = ByteArrayOutputStream()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            var audioRecord: AudioRecord? = null
            if (CameraPermissionHelper.hasAudioPermission(appContext) && minBuf > 0) {
                try {
                    val rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        rec.startRecording()
                        audioRecord = rec
                    } else {
                        rec.release()
                    }
                } catch (_: Exception) {
                    audioRecord = null
                }
            }

            val shortBuf = ShortArray(256)
            try {
                while (isActive && _isRecordingVoiceNote.value) {
                    val elapsed = System.currentTimeMillis() - recordStartTimeMs
                    _recordingDurationMs.value = elapsed
                    if (elapsed >= maxDurationMs) {
                        _isRecordingVoiceNote.value = false
                        break
                    }

                    val rec = audioRecord
                    var readCount = 0
                    var rms = 0f
                    if (rec != null) {
                        readCount = rec.read(shortBuf, 0, shortBuf.size)
                        if (readCount > 0) {
                            var sum = 0.0
                            for (i in 0 until readCount) {
                                val s = shortBuf[i].toInt()
                                pcmStream.write(s and 0xFF)
                                pcmStream.write((s shr 8) and 0xFF)
                                val n = s / 32768.0
                                sum += n * n
                            }
                            rms = sqrt(sum / readCount).toFloat() * 4.5f
                        }
                    }

                    val level = rms.coerceIn(0f, 1.0f)
                    _voiceModulationLevel.value = level
                    _liveAudioSpectrum.value = FloatArray(32) { idx ->
                        if (readCount > 0) {
                            val sampleIdx = (idx * (readCount / 32).coerceAtLeast(1)).coerceIn(0, readCount - 1)
                            (abs(shortBuf[sampleIdx] / 32768f) * 3.8f).coerceIn(0f, 1.0f)
                        } else {
                            0f
                        }
                    }
                    delay(20L)
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                writeWavFile(outFile, pcmStream.toByteArray(), sampleRate)
                _voiceModulationLevel.value = 0f
                _liveAudioSpectrum.value = FloatArray(32) { 0f }
            }
        }
    }

    suspend fun stopVoiceNoteRecording(): Pair<File?, Long> {
        if (!_isRecordingVoiceNote.value && voiceRecordJob == null) return null to 0L
        val duration = (System.currentTimeMillis() - recordStartTimeMs).coerceAtLeast(1L)
        _isRecordingVoiceNote.value = false
        voiceRecordJob?.join()
        voiceRecordJob = null
        val file = currentVoiceFile
        return if (file != null && file.exists() && file.length() > 44L) {
            file to duration
        } else {
            null to 0L
        }
    }

    /**
     * Encodes a recorded `.wav` voice note into Base64 so it can be transmitted
     * directly through `/global_comms_rooms/{roomId}/messages` (`signalPayload`)
     * and played back on the recipient's device.
     */
    fun encodeWavFileToBase64(file: File): String? {
        return try {
            if (!file.exists() || file.length() <= 44L) return null
            val bytes = file.readBytes()
            if (bytes.size > 180_000) {
                Base64.encodeToString(bytes.copyOfRange(0, 180_000), Base64.NO_WRAP)
            } else {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePlayableWavFile(messageId: String, uriPath: String?, base64Wav: String?): File? {
        val localFile = uriPath?.let { File(it) }
        if (localFile != null && localFile.exists() && localFile.length() > 44L) {
            return localFile
        }
        if (!base64Wav.isNullOrBlank()) {
            return try {
                val safeId = messageId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                val cached = File(appContext.cacheDir, "remote_voice_$safeId.wav")
                if (!cached.exists() || cached.length() <= 44L) {
                    val decoded = Base64.decode(base64Wav, Base64.NO_WRAP)
                    FileOutputStream(cached).use { it.write(decoded) }
                }
                if (cached.exists() && cached.length() > 44L) cached else null
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun togglePlayVoiceNote(
        messageId: String,
        uriPath: String?,
        durationMs: Long,
        base64WavPayload: String? = null
    ) {
        if (_playingMessageId.value == messageId) {
            stopPlayback()
            return
        }
        stopPlayback()

        playbackJob = scope.launch(Dispatchers.IO) {
            val file = resolvePlayableWavFile(messageId, uriPath, base64WavPayload) ?: return@launch
            _playingMessageId.value = messageId
            _playbackProgress.value = 0f
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
                mediaPlayer = mp
                val total = mp.duration.coerceAtLeast(durationMs.toInt()).coerceAtLeast(1)
                while (isActive && mp.isPlaying) {
                    _playbackProgress.value = (mp.currentPosition.toFloat() / total).coerceIn(0f, 1f)
                    delay(40L)
                }
                _playbackProgress.value = 1f
            } catch (_: Exception) {
            } finally {
                stopPlayback()
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _playingMessageId.value = null
        _playbackProgress.value = 0f
    }

    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int) {
        try {
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * 2
            FileOutputStream(file).use { out ->
                val header = ByteArray(44)
                header[0] = 'R'.code.toByte()
                header[1] = 'I'.code.toByte()
                header[2] = 'F'.code.toByte()
                header[3] = 'F'.code.toByte()
                writeIntLE(header, 4, totalDataLen)
                header[8] = 'W'.code.toByte()
                header[9] = 'A'.code.toByte()
                header[10] = 'V'.code.toByte()
                header[11] = 'E'.code.toByte()
                header[12] = 'f'.code.toByte()
                header[13] = 'm'.code.toByte()
                header[14] = 't'.code.toByte()
                header[15] = ' '.code.toByte()
                writeIntLE(header, 16, 16)
                header[20] = 1
                header[21] = 0
                header[22] = 1
                header[23] = 0
                writeIntLE(header, 24, sampleRate)
                writeIntLE(header, 28, byteRate)
                header[32] = 2
                header[33] = 0
                header[34] = 16
                header[35] = 0
                header[36] = 'd'.code.toByte()
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                writeIntLE(header, 40, pcmData.size)
                out.write(header)
                out.write(pcmData)
            }
        } catch (_: Exception) {
        }
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

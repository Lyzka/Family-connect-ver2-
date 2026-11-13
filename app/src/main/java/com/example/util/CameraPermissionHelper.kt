package com.example.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper utilities and Jetpack Compose launcher state for checking and requesting
 * runtime [Manifest.permission.CAMERA] and [Manifest.permission.RECORD_AUDIO] permissions
 * for live holographic WebRTC voice/video calls and voice notes.
 */
object CameraPermissionHelper {
    const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    const val RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            CAMERA_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            RECORD_AUDIO_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasVideoAndAudioPermissions(context: Context): Boolean {
        return hasCameraPermission(context) && hasAudioPermission(context)
    }

    fun shouldShowCameraRationale(activity: Activity): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            CAMERA_PERMISSION
        )
    }

    fun requestCameraPermission(
        context: Context,
        launcher: ActivityResultLauncher<String>,
        onAlreadyGranted: () -> Unit = {}
    ) {
        if (hasCameraPermission(context)) {
            onAlreadyGranted()
        } else {
            launcher.launch(CAMERA_PERMISSION)
        }
    }
}

class CameraPermissionRequester(
    private val context: Context,
    private val launcher: ManagedActivityResultLauncher<String, Boolean>,
    private val onResult: (Boolean) -> Unit
) {
    fun isGranted(): Boolean = CameraPermissionHelper.hasCameraPermission(context)

    fun ensureCameraPermission() {
        CameraPermissionHelper.requestCameraPermission(
            context = context,
            launcher = launcher,
            onAlreadyGranted = { onResult(true) }
        )
    }
}

@Composable
fun rememberCameraPermissionRequester(
    onPermissionResult: (Boolean) -> Unit
): CameraPermissionRequester {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        onPermissionResult(isGranted)
    }
    return remember(context, launcher, onPermissionResult) {
        CameraPermissionRequester(
            context = context,
            launcher = launcher,
            onResult = onPermissionResult
        )
    }
}

/**
 * Controller for requesting both CAMERA and RECORD_AUDIO permissions (for VIDEO WebRTC calls)
 * or RECORD_AUDIO permission (for VOICE WebRTC calls and Voice Notes).
 */
class MediaCallPermissionsRequester(
    private val context: Context,
    private val multiLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    private val audioLauncher: ManagedActivityResultLauncher<String, Boolean>,
    private val onVideoAudioResult: (Boolean, Boolean) -> Unit,
    private val onAudioOnlyResult: (Boolean) -> Unit
) {
    fun ensureVideoAndAudioPermissions() {
        val cam = CameraPermissionHelper.hasCameraPermission(context)
        val aud = CameraPermissionHelper.hasAudioPermission(context)
        if (cam && aud) {
            onVideoAudioResult(true, true)
        } else {
            multiLauncher.launch(
                arrayOf(
                    CameraPermissionHelper.CAMERA_PERMISSION,
                    CameraPermissionHelper.RECORD_AUDIO_PERMISSION
                )
            )
        }
    }

    fun ensureAudioPermission() {
        if (CameraPermissionHelper.hasAudioPermission(context)) {
            onAudioOnlyResult(true)
        } else {
            audioLauncher.launch(CameraPermissionHelper.RECORD_AUDIO_PERMISSION)
        }
    }
}

@Composable
fun rememberMediaCallPermissionsRequester(
    onVideoAudioResult: (cameraGranted: Boolean, audioGranted: Boolean) -> Unit,
    onAudioOnlyResult: (audioGranted: Boolean) -> Unit
): MediaCallPermissionsRequester {
    val context = LocalContext.current
    val multiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cam = results[CameraPermissionHelper.CAMERA_PERMISSION]
            ?: CameraPermissionHelper.hasCameraPermission(context)
        val aud = results[CameraPermissionHelper.RECORD_AUDIO_PERMISSION]
            ?: CameraPermissionHelper.hasAudioPermission(context)
        onVideoAudioResult(cam, aud)
    }
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onAudioOnlyResult(granted)
    }
    return remember(context, multiLauncher, audioLauncher, onVideoAudioResult, onAudioOnlyResult) {
        MediaCallPermissionsRequester(
            context = context,
            multiLauncher = multiLauncher,
            audioLauncher = audioLauncher,
            onVideoAudioResult = onVideoAudioResult,
            onAudioOnlyResult = onAudioOnlyResult
        )
    }
}

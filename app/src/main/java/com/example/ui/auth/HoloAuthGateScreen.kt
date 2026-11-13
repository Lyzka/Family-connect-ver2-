package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.ui.components.HoloFlowingParticleField
import com.example.ui.render3d.drawHolographicCenterpieceAndParticles
import com.example.ui.render3d.drawSciFiCorridorBackground
import com.example.ui.theme.HoloBlack
import com.example.ui.theme.HoloCyan
import com.example.ui.theme.HoloCyanBright
import com.example.ui.theme.HoloGoldBright
import com.example.ui.theme.HoloRedBright
import com.example.ui.theme.HoloWhite
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.RajdhaniFontFamily
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Unwraps ContextWrapper chains to obtain the host Activity required by CredentialManager UI sheets.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Silent Auto-Sign-In on startup using GetGoogleIdOption (single option per request).
 * Returns the launched Job so interactive sign-in can cancel any in-flight background check
 * and prevent concurrent CredentialManager requests on the same Activity.
 */
fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
): Job? {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return null
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onUnauthenticated()
        return null
    }
    val activityContext = context.findActivity() ?: context

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    return scope.launch {
        try {
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onUnauthenticated()
        }
    }
}

/**
 * Interactive Google Sign-In flow using GetSignInWithGoogleOption (single option per request),
 * with sequential single-option fallback to GetGoogleIdOption(filterByAuthorizedAccounts = false)
 * when NoCredentialException occurs on devices with unlinked Google accounts.
 */
fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
    priorAutoSignInJob: Job? = null
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        onAuthError("Brak konfiguracji Google Sign-In: nie znaleziono default_web_client_id.")
        return
    }

    val activityContext = context.findActivity() ?: context
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val primaryRequest = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

    scope.launch {
        try {
            // Ensure any startup silent check is cancelled before launching interactive sheet
            priorAutoSignInJob?.cancel()

            val result = try {
                credentialManager.getCredential(activityContext, primaryRequest)
            } catch (noCredEx: NoCredentialException) {
                Log.w("Auth", "Primary GetSignInWithGoogleOption returned NoCredentialException; trying unfiltered GetGoogleIdOption picker", noCredEx)
                val fallbackGoogleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(fallbackGoogleIdOption)
                    .build()
                credentialManager.getCredential(activityContext, fallbackRequest)
            }

            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                Firebase.auth.signInWithCredential(authCredential).await()
                onAuthSuccess()
            } else {
                onAuthError("Nieoczekiwany typ poświadczenia: ${credential.type}")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
            onAuthError(
                "Logowanie Google zostało przerwane lub odrzucone. Upewnij się, że na urządzeniu jest zalogowane konto Google (Ustawienia -> Konta -> Google) i wybierz konto z listy."
            )
        } catch (e: NoCredentialException) {
            Log.w("Auth", "No Google account found on device: ${e.message}", e)
            onAuthError(
                "Nie wykryto konta Google na tym urządzeniu/emulatorze. Dodaj konto Google w Ustawieniach systemu Android (Ustawienia -> Konta -> Dodaj konto -> Google) i spróbuj ponownie."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError("Błąd logowania Google: ${e.localizedMessage ?: "Sign in failed"}")
        }
    }
}

/**
 * Safe Sign-Out flow clearing CredentialManager state inside NonCancellable before Firebase.auth.signOut().
 */
fun signOutHoloOperator(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun HoloAuthGateScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var autoSignInJob by remember { mutableStateOf<Job?>(null) }
    var timeSec by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        autoSignInJob = attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    LaunchedEffect(Unit) {
        val start = android.os.SystemClock.uptimeMillis()
        while (true) {
            delay(33L)
            timeSec = (android.os.SystemClock.uptimeMillis() - start) / 1000f
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(HoloBlack)
            .testTag("holo_auth_gate_screen")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSciFiCorridorBackground()
            drawHolographicCenterpieceAndParticles(
                timeSec = timeSec,
                yawOffset = 0f,
                pitchOffset = 0f,
                isDisconnected = false,
                isSecure = true,
                pulseEnergy = 1.25f
            )
        }

        HoloFlowingParticleField(
            modifier = Modifier.fillMaxSize(),
            flowIntensity = 1.2f,
            isAlertRed = false
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(20.dp)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xE604162C),
                            Color(0xF2020914)
                        )
                    )
                )
                .border(1.5.dp, HoloCyanBright.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(26.dp)
                        .background(HoloCyan)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "HOLOCOMM 3D // QUANTUM GATE",
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.8.sp,
                    color = HoloWhite
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "ZALOGUJ KONTO GOOGLE ABY AKTYWOWAĆ GLOBALNY SYSTEM OBECNOŚCI (/global_presence), WSPÓŁDZIELONE POKOJE (/global_comms_rooms) ORAZ POŁĄCZENIA WIDEO WEBRTC",
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.0.sp,
                color = HoloCyanBright.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HoloRedBright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FF1744))
                        .border(1.dp, HoloRedBright.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .testTag("auth_error_banner")
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = {
                            isLoading = false
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            isLoading = false
                            errorMessage = msg
                        },
                        scope = scope,
                        onAuthCancelled = {
                            isLoading = false
                        },
                        priorAutoSignInJob = autoSignInJob
                    )
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HoloCyan.copy(alpha = 0.28f),
                    contentColor = HoloWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .border(1.4.dp, HoloCyanBright, RoundedCornerShape(10.dp))
                    .testTag("google_sign_in_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = HoloCyanBright,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Sign in with Google",
                        fontFamily = OrbitronFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.2.sp,
                        color = HoloWhite
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "PROTOCOL: ZERO-TRUST CREDENTIAL MANAGER // SECURE P2P NODE",
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                color = HoloGoldBright.copy(alpha = 0.85f)
            )
        }
    }
}

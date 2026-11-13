package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import com.example.ui.HoloCommunicatorScreen
import com.example.ui.auth.HoloAuthGateScreen
import com.example.ui.auth.signOutHoloOperator
import com.example.ui.theme.MyApplicationTheme
import com.example.util.configureAppCheck
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        // STEP 2: Enable edge-to-edge and initialize Compose UI with Root Auth Gate
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                HoloCommAppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
    }
}

@Composable
fun HoloCommAppRoot() {
    val auth = remember { Firebase.auth }
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    val user = currentUser
    if (user == null) {
        HoloAuthGateScreen(
            onAuthSuccess = { currentUser = auth.currentUser }
        )
    } else {
        key(user.uid) {
            HoloCommunicatorScreen(
                modifier = Modifier.fillMaxSize(),
                currentUserId = user.uid,
                onSignOut = {
                    signOutHoloOperator(
                        credentialManager = credentialManager,
                        scope = scope
                    )
                }
            )
        }
    }
}

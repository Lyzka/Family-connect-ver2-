package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.GlobalOperatorPresenceDoc
import com.example.data.HoloCommRepository
import com.example.ui.HudViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context and verify holographic hud state and multi-device sync`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Family Contact ver. 2", appName)

        val viewModel = HudViewModel()
        assertEquals("STANDBY // CHANNEL READY", viewModel.uiState.value.centerStatusText)

        viewModel.selectContact("USER_02")
        assertEquals("USER_02", viewModel.uiState.value.selectedContactId)

        viewModel.toggleCall()
        assertTrue(viewModel.uiState.value.isCallActive)
        assertEquals("CONNECTING...", viewModel.uiState.value.centerStatusText)

        // Verify remote operator presence sync adds Phone B to HUD contacts
        viewModel.syncGlobalRemoteOperators(
            localUid = "phone_a_uid",
            remoteOperators = listOf(
                GlobalOperatorPresenceDoc(
                    id = "phone_b_uid",
                    userId = "phone_b_uid",
                    displayName = "Operator B",
                    callSign = "OPR // PHONE-B",
                    presence = "ONLINE",
                    e2eeFingerprint = "A4F9-99C1-7B2E-00D4",
                    deviceModel = "Pixel 9 Pro"
                )
            )
        )
        assertTrue(viewModel.uiState.value.contacts.any { it.id == "phone_b_uid" })
        assertEquals(
            "phone_a_uid_phone_b_uid",
            HoloCommRepository.buildDeterministicRoomId("phone_b_uid", "phone_a_uid")
        )

        // Verify symmetric AES-256-GCM encryption on Phone A and decryption on Phone B
        val plainSecret = "REALTIME_P2P_PAYLOAD_2026"
        val encryptedOnPhoneA = com.example.util.HoloE2eeCryptoEngine.encryptMessage(
            plainText = plainSecret,
            operatorUid = "phone_a_uid",
            peerId = "phone_b_uid"
        )
        val decryptedOnPhoneB = com.example.util.HoloE2eeCryptoEngine.decryptMessage(
            cipherBase64 = encryptedOnPhoneA.cipherBase64,
            operatorUid = "phone_b_uid",
            peerId = "phone_a_uid",
            fallbackPlain = "FAILED"
        )
        assertEquals(plainSecret, decryptedOnPhoneB)

        // Verify real two-way WebRTC call state transitions (CONNECTED -> ENDED)
        viewModel.onRemoteCallConnected(isVideo = true)
        assertTrue(viewModel.uiState.value.isVideoActive)
        viewModel.onRemoteCallEnded()
        assertTrue(viewModel.uiState.value.isDisconnected)
    }
}

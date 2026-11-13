package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoloCommRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun buildDeterministicRoomId_ordersUserIdsAlphabetically() {
        val roomAB = HoloCommRepository.buildDeterministicRoomId("user_omega_99", "user_alpha_01")
        val roomBA = HoloCommRepository.buildDeterministicRoomId("user_alpha_01", "user_omega_99")
        assertEquals("user_alpha_01_user_omega_99", roomAB)
        assertEquals(roomAB, roomBA)
    }

    @Test
    fun globalPresence_twoDifferentGoogleAccounts_seeEachOtherInRealTime() = runBlocking {
        // Phone A signs in and publishes presence to /global_presence/{aliceUid}
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = HoloCommRepository(firestore)
        val alicePresenceResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateGlobalOperatorPresence(
                presence = "ONLINE",
                displayName = "Operator Alice",
                callSign = "OPR // ALICE-A",
                e2eeFingerprint = "A4F9-99C1-7B2E-00D4"
            )
        }
        assertTrue(alicePresenceResult.isSuccess)

        // Phone B signs in and publishes presence to /global_presence/{bobUid}
        val bobUid = signInTestUser(BOB_EMAIL)
        val bobPresenceResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateGlobalOperatorPresence(
                presence = "ONLINE",
                displayName = "Operator Bob",
                callSign = "OPR // BOB-B",
                e2eeFingerprint = "B8E2-4410-9C3A-11F8"
            )
        }
        assertTrue(bobPresenceResult.isSuccess)

        // Phone B observes /global_presence and sees both Alice (Phone A) and Bob (Phone B) online
        val activeOperators = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeGlobalActiveOperators().first { list ->
                list.any { it.userId == aliceUid } && list.any { it.userId == bobUid }
            }
        }
        assertTrue(activeOperators.any { it.userId == aliceUid && it.presence == "ONLINE" })
        assertTrue(activeOperators.any { it.userId == bobUid && it.presence == "ONLINE" })
    }

    @Test
    fun sharedCommsRoom_twoDevices_exchangeMessagesControlSignalsAndWebRtcSignaling() = runBlocking {
        // 1. Sign in Phone A (Alice) and Phone B (Bob) to obtain their UIDs
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val bobUid = signInTestUser(BOB_EMAIL)
        val repository = HoloCommRepository(firestore)

        val sharedRoomId = repository.buildDeterministicRoomId(aliceUid, bobUid)
        val uniqueMessageText = "Cross-device quantum ping ${UUID.randomUUID()}"

        // 2. Phone A sends a message to Phone B in /global_comms_rooms/{roomId}/messages
        signInTestUser(ALICE_EMAIL)
        val sendResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendSharedRoomMessage(
                roomId = sharedRoomId,
                recipientId = bobUid,
                senderLabel = "OPR // ALICE-A",
                text = uniqueMessageText,
                messageType = "TEXT",
                status = "SENT",
                isEncrypted = true,
                modelBadge = "P2P // AES-256-GCM"
            )
        }
        assertTrue(sendResult.isSuccess)
        val messageId = sendResult.getOrThrow()

        // 3. Phone A also publishes a WebRTC SDP_OFFER to /global_comms_rooms/{roomId}/rtc_signals
        val signalResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendSharedRtcSignal(
                roomId = sharedRoomId,
                recipientId = bobUid,
                callType = "VIDEO",
                signalType = "SDP_OFFER",
                sdpPayload = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=HoloWebRTC"
            )
        }
        assertTrue(signalResult.isSuccess)
        val signalId = signalResult.getOrThrow()

        // 4. Phone B switches in, observes the shared room messages, and marks Alice's message as READ
        signInTestUser(BOB_EMAIL)
        val roomMessages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSharedRoomMessages(sharedRoomId).first { list ->
                list.any { it.id == messageId }
            }
        }
        assertTrue(roomMessages.any { it.id == messageId && it.text == uniqueMessageText && it.senderId == aliceUid })

        val readUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateSharedRoomMessageStatus(sharedRoomId, messageId, "READ")
        }
        assertTrue(readUpdate.isSuccess)

        // 5. Phone B answers the WebRTC call with SDP_ANSWER -> CONNECTED
        val answerUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateSharedRtcSignalState(
                roomId = sharedRoomId,
                signalId = signalId,
                signalType = "CONNECTED",
                sdpPayload = "v=0\r\no=- 54321 2 IN IP4 127.0.0.1\r\ns=HoloAnswer",
                iceCandidatesCount = 3L
            )
        }
        assertTrue(answerUpdate.isSuccess)
    }

    @Test
    fun observeGlobalAndSharedRoom_unauthenticatedCaller_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = HoloCommRepository(firestore)

        val crossResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getMessagesForUser(targetUserId = aliceUid)
        }
        assertTrue(crossResult.isFailure)
        val firestoreEx = crossResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@holocomm.test"
        const val BOB_EMAIL = "bob@holocomm.test"
        const val DEFAULT_TIMEOUT_MS = 10000L
        const val FLOW_TIMEOUT_MS = 6000L
    }
}

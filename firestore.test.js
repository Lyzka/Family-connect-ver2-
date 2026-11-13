const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_operator_01";
const BOB_UID = "bob_operator_02";
const CHARLIE_UID = "charlie_outsider_03";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function buildRoomId(uidA, uidB) {
  return [uidA, uidB].sort().join("_");
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write /global_presence or /global_comms_rooms", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const roomId = buildRoomId(ALICE_UID, BOB_UID);
  await assertFails(unauthDb.collection("global_presence").get());
  await assertFails(
    unauthDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("messages")
      .get()
  );
  await assertFails(
    unauthDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("rtc_signals")
      .get()
  );
});

test("Authenticated Phone A and Phone B can publish their own /global_presence/{userId} and list all active operators, but cannot spoof each other's presence", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  await assertSucceeds(
    aliceDb.collection("global_presence").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "OPERATOR ALICE",
      callSign: "OPR // ALICE-01",
      presence: "ONLINE",
      statusMessage: "READY FOR ORBITAL LINK",
      e2eeFingerprint: "A4F9-99C1-7B2E-00D4",
      deviceModel: "Pixel 9 Pro",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    bobDb.collection("global_presence").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "OPERATOR BOB",
      callSign: "OPR // BOB-02",
      presence: "ONLINE",
      statusMessage: "SECTOR 14 RELAY",
      e2eeFingerprint: "B8E2-4410-9C3A-11F8",
      deviceModel: "Galaxy S26 Ultra",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Both devices can list /global_presence to see who is online
  await assertSucceeds(aliceDb.collection("global_presence").get());
  await assertSucceeds(bobDb.collection("global_presence").get());

  // Phone B cannot overwrite Phone A's presence document
  await assertFails(
    bobDb.collection("global_presence").doc(ALICE_UID).update({
      presence: "OFFLINE",
      updatedAt: new Date(),
    })
  );
});

test("Phone A and Phone B can exchange messages and update delivery status in shared /global_comms_rooms/{roomId}/messages", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const roomId = buildRoomId(ALICE_UID, BOB_UID);
  const now = new Date();

  const msgRefAlice = aliceDb
    .collection("global_comms_rooms")
    .doc(roomId)
    .collection("messages")
    .doc("msg_shared_01");

  // Phone A sends a message to Phone B
  await assertSucceeds(
    msgRefAlice.set({
      roomId: roomId,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      senderLabel: "OPR // ALICE-01",
      text: "Hello Phone B, initiating quantum link.",
      messageType: "TEXT",
      status: "SENT",
      isEncrypted: true,
      modelBadge: "P2P // AES-256-GCM",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Phone B reads messages in the shared room
  await assertSucceeds(
    bobDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("messages")
      .get()
  );

  // Phone B updates message status to READ
  await assertSucceeds(
    bobDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("messages")
      .doc("msg_shared_01")
      .update({
        status: "READ",
        updatedAt: new Date(),
      })
  );

  // Non-participant Charlie cannot update Alice & Bob's message
  await assertFails(
    charlieDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("messages")
      .doc("msg_shared_01")
      .update({
        status: "SENT",
        updatedAt: new Date(),
      })
  );
});

test("Phone A and Phone B can exchange WebRTC SDP offer/answer and ICE candidates in /global_comms_rooms/{roomId}/rtc_signals", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const roomId = buildRoomId(ALICE_UID, BOB_UID);
  const now = new Date();

  const sigRefAlice = aliceDb
    .collection("global_comms_rooms")
    .doc(roomId)
    .collection("rtc_signals")
    .doc("sig_001");

  // Phone A publishes SDP_OFFER
  await assertSucceeds(
    sigRefAlice.set({
      roomId: roomId,
      senderId: ALICE_UID,
      recipientId: BOB_UID,
      callType: "VIDEO",
      signalType: "SDP_OFFER",
      sdpPayload: "v=0\r\no=- 49281 2 IN IP4 127.0.0.1\r\ns=HoloCommOffer",
      iceCandidate: "candidate:1 1 UDP 2122252543 192.168.1.10 54321 typ host",
      iceCandidatesCount: 1,
      isEncrypted: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Phone B answers with SDP_ANSWER and transitions signalType -> CONNECTED
  await assertSucceeds(
    bobDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("rtc_signals")
      .doc("sig_001")
      .update({
        signalType: "CONNECTED",
        sdpPayload: "v=0\r\no=- 88312 2 IN IP4 127.0.0.1\r\ns=HoloCommAnswer",
        iceCandidatesCount: 3,
        updatedAt: new Date(),
      })
  );
});

test("Shadow update with ghost field on GlobalRoomMessage is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const roomId = buildRoomId(ALICE_UID, BOB_UID);
  const now = new Date();

  await assertFails(
    aliceDb
      .collection("global_comms_rooms")
      .doc(roomId)
      .collection("messages")
      .doc("msg_ghost")
      .set({
        roomId: roomId,
        senderId: ALICE_UID,
        recipientId: BOB_UID,
        senderLabel: "OPR // ALICE-01",
        text: "Valid text",
        messageType: "TEXT",
        status: "SENT",
        isEncrypted: true,
        ghostField: "hacked",
        createdAt: now,
        updatedAt: now,
      })
  );
});

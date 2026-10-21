# P2P Chat — Physical Device Test Plan

Requires **at least 3 Android phones** (4 for the full A→B→C→D chain), all with
the same debug/release APK installed, Bluetooth ON, app permissions granted.

## Setup
1. Install the APK on every phone (`adb install -r app-debug.apk` or transfer the file).
2. Open P2P Chat, create a distinct display name on each phone (A, B, C, D).
3. On each phone open **Nearby Peers** — confirm every directly-in-range phone appears
   with a live RSSI value and can connect. Note each phone's Peer ID (Profile screen).

## Test 1 — Direct message (A → B)
1. A opens B from Nearby Peers → Start chat → send "test-1".
2. PASS: B receives it; on A the message shows **Delivered** only after B's app
   received it (ACK). Kill B's app before sending → message must stay **Queued**,
   then deliver after B reopens the app and reconnects.

## Test 2 — Multi-hop relay (A → B → C → D)
1. Arrange phones in a line ~8–10 m apart (or use stairs/floors) so A and D are
   **out of direct range**: verify on A that D does NOT appear in Nearby Peers,
   and on D that A does NOT appear. B must see A and C; C must see B and D.
2. A sends a message to D (open D's conversation via a previously learned Peer ID /
   conversation created when in range, or via Mesh Status → peer table entry).
3. PASS criteria:
   - D receives the message.
   - B and C show **no readable copy** of the message (relays forward ciphertext
     envelopes only — check their chat lists: no conversation with the plaintext).
   - A's message state goes Queued → Relayed → **Delivered** (Delivered only when
     D's acknowledgement has travelled back through C and B).
   - In Mesh Status on B/C, the outbox/forward counters move.

## Test 3 — No false delivery
1. Same layout as Test 2. D turns Bluetooth OFF.
2. A sends to D. PASS: state reaches **Relayed** (or stays Queued) and NEVER
   shows Delivered while D is unreachable.
3. D turns Bluetooth back ON. PASS: message delivers and only then flips to Delivered.

## Test 4 — Relay with locked screens
1. Repeat Test 2 with B and C screens locked and the relay foreground service
   running (persistent notification visible).
2. PASS: message still reaches D. Note OEM battery managers (Xiaomi/Oppo/Vivo/
   Samsung) may kill the service — record device model + result; exempt P2P Chat
   from battery optimisation and retest.

## Test 5 — Disconnect / reconnect storm
1. During a Test 2 transfer, toggle Bluetooth OFF/ON on C.
2. PASS: no duplicate message appears on D (dedup), no crash, delivery completes
   after reconnect, hop count in Message Details is sane (≤ 3 for this chain).

## Test 6 — Expiry
1. Send a message to a peer that stays offline. PASS: it remains Queued, is retried
   on reconnects, and is marked **Expired** after its expiry (default 7 days; for a
   quicker check, build a test variant with a short expiry and verify the state
   transition and queue purge).

## Test 7 — Battery & persistence
1. Leave relay service running 8 hours on B; record battery drain.
2. Force-stop the app on B, reopen. PASS: queued messages are still in the outbox
   (Room persistence), identity is unchanged (Profile fingerprint matches).

## Known platform limitations (expected, not failures)
- After force-stop, Android does not restart the service until the user reopens
  the app; after reboot the service does not auto-start unless the user opens
  the app once (documented behaviour of this build).
- Aggressive OEM battery savers can kill background BLE; exemption is the remedy.
- iOS is out of scope; BLE background rules differ per platform.

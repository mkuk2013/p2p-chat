# P2P Chat — Connect. Relay. Chat.

**Offline Bluetooth Low Energy mesh messaging for Android.** P2P Chat lets phones
exchange private text messages with **no mobile data, no Wi-Fi, and no central
server**. Every phone is both a client and a relay: a message from A can reach D
through B and C even when A and D are far outside each other's Bluetooth range.

- Package: `com.p2pchat.messenger`
- Platform: Android 8.0+ (API 26+), Kotlin, Jetpack Compose + Material Design 3
- Transport: Bluetooth Low Energy (GATT server + client on every device)

---

## Architecture

Modular Gradle project — each layer is its own module with its own tests:

| Module | Responsibility |
|---|---|
| `:app` | Compose UI (10 screens), foreground relay service, DI wiring, identity payload exchange |
| `:core-common` | Message envelope + binary codec, packet chunking/reassembly, dedup cache, PeerId derivation — pure Kotlin/JVM |
| `:core-crypto` | Identity (Ed25519 + X25519), E2E sealing (X25519 ECDH → HKDF-SHA256 → AES-256-GCM), envelope signing, replay protection — Bouncy Castle + JCA primitives only |
| `:core-database` | Room database (peers, conversations, messages, outbox, seen-IDs), Keystore-wrapped identity storage, repositories |
| `:transport-ble` | BLE scanning/advertising, GATT server & client, MTU-aware chunked transfer, reconnect with backoff (max 5 links) |
| `:mesh-engine` | Peer table, controlled-flooding router with split horizon, store-and-forward outbox, ACK-based delivery, TTL/loop protection, per-peer rate limiting |

Data flow: `UI → ChatRepository → MeshRouter (sign + queue) → BleTransport
(chunk, GATT) → peer BleTransport (reassemble) → peer MeshRouter (verify, dedup,
deliver or forward)`.

## The mesh protocol (implemented, not assumed)

Bluetooth does **not** provide multi-hop networking by itself — P2P Chat
implements forwarding at the application layer:

- Every message travels in a signed **envelope**: protocol version, unique
  message ID, sender/recipient Peer IDs, creation timestamp, expiry
  (default 7 days), TTL/hop counters, ephemeral X25519 key, nonce, ciphertext,
  Ed25519 signature. Binary codec with a 512 KB cap; corrupt/oversized frames
  are rejected.
- **Controlled flooding with split horizon:** a relay forwards an envelope to
  all connected neighbours except the one it arrived from, after decrementing
  TTL. Duplicates are suppressed by a seen-message cache (LRU, 2,000 entries);
  hop count is hard-capped (12) as a second loop defence, plus a per-peer
  forwarding rate limit (30/min) against malicious loops.
- **Store-and-forward:** if no onward route exists, the relay keeps the
  envelope in its own persistent outbox (Room) and forwards it when a route
  appears; it drops its copy when the recipient's ACK passes through.
- **Honest delivery states:** `QUEUED → RELAYED → DELIVERED`. *Relayed* means a
  neighbour accepted the envelope for forwarding — it is **not** delivery.
  A message is marked **Delivered only when the recipient's signed
  acknowledgement** has travelled back through the mesh.

## Security

- **Identity:** each install generates an Ed25519 signing keypair and an
  X25519 agreement keypair. PeerId = first 16 bytes of SHA-256(Ed25519 public
  key), hex. Private keys are stored **encrypted at rest**, wrapped by an
  AES-256-GCM key in the Android Keystore. No hardcoded secrets anywhere.
- **End-to-end encryption:** per-message ephemeral X25519 → ECDH with the
  recipient's static X25519 → HKDF-SHA256 (info `p2pchat-v1`) → AES-256-GCM
  (random 12-byte nonce, 128-bit tag). Relays see only the envelope routing
  fields and ciphertext — never plaintext.
- **Authentication & integrity:** every envelope is Ed25519-signed over its
  canonical codec bytes; receivers verify before routing or decrypting.
  AEAD failure (wrong recipient, tampering) drops the message — no ACK is
  sent for undecryptable messages, so senders keep retrying legitimately.
- **Replay protection:** seen-message IDs, timestamp freshness window
  (5 min), TTL/expiry.
- **Honest limitations:** this is an ephemeral-static ECDH design, *not* a
  Signal-style double ratchet — there is no forward secrecy against a
  recipient key compromised later, and no identity recovery: **lose the phone,
  lose the identity and history. There is no server backup, by design.**
  The design uses reviewed primitives (Bouncy Castle, JCA) and no custom
  algorithms, but it has not had an independent security audit — do not rely
  on it for life-safety communication.

## Bluetooth behaviour & platform limits

- Android 12+ permissions `BLUETOOTH_SCAN` (with `neverForLocation`),
  `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`; Android ≤ 11 uses
  `ACCESS_FINE_LOCATION` for scanning. Bluetooth is enabled **only** via the
  official system prompt — never silently.
- Every phone runs a GATT server *and* client; MTU 517 is requested and
  payloads are chunked to the negotiated MTU (header 12 B). Connections are
  capped at 5 with 10 s timeouts and 1s/2s/4s reconnect backoff.
- Background relay runs as a foreground service with a persistent
  notification and duty-cycled scanning (default 10 s on / 25 s off,
  configurable). Android will **not** restart it after force-stop, and OEM
  battery killers may stop it — exempt the app from battery optimisation for
  reliable relaying. These are platform facts, documented in
  `docs/PHYSICAL_TEST_PLAN.md`.

## Build & install

Requirements: Android Studio (Ladybug+) or JDK 17 + Android SDK 34 +
Gradle 8.7.

```bash
git clone https://github.com/mkuk2013/p2p-chat.git
cd p2p-chat
# If gradle/wrapper/gradle-wrapper.jar is absent, generate it once:
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug          # debug APK
./gradlew :core-common:test :core-crypto:test :mesh-engine:test   # JVM unit tests
./gradlew :app:assembleRelease        # release build (add your signing config)
```

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy
the APK to the phone and open it.

> **Branding assets:** launcher/splash PNGs live in `app/src/main/res/`. If you
> cloned and any PNG is missing, regenerate all of them from the logo stored
> in this repo as base64 text: `python3 branding/regen-icons.py`
> (requires Pillow). The master artwork is `branding/logo-512.b64.txt`.

## Testing

- **48 JVM unit tests** covering envelope codec roundtrip/corruption, chunk
  split/reassembly (out-of-order, duplicates, expiry), dedup LRU/TTL, crypto
  seal/open + tamper failure, envelope sign/verify, outbox backoff, peer
  table, and a simulated **A→B→C→D mesh chain** (delivery, no false
  delivered, store-and-forward after a route appears, loop prevention).
  Verified passing with Kotlin 2.0.20 / JUnit 4.
- **Physical-device plan** (3–4 phones, locked-screen relays, BT toggle
  storms, battery/persistence): see `docs/PHYSICAL_TEST_PLAN.md`.
  BLE behaviour can only be fully validated on real hardware.

## Project status

Implemented and unit-verified: protocol, crypto, mesh engine, BLE transport,
Room persistence, foreground service, full UI. **Still requiring real-device
validation:** end-to-end BLE delivery across phone models/OEMs, background
relay survival under OEM battery managers, and the multi-hop physical test
plan above. Debug APK builds with `./gradlew :app:assembleDebug`.

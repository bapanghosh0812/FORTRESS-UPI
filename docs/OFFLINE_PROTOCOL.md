# Fortress Offline Protocol v1 (FOP/1)

Moving pre-funded value between two phones with **no internet**, using BLE or NFC, with **hardware-enforced double-spend prevention** and guaranteed settlement once either phone reconnects.

Reference implementations:
* Payer/payee engine: `apps/mobile/android/core/offline`
* Settlement verifier: `services/offline-mesh/internal/domain`
* Canonical codec: `shared/crypto/fce` (Go) ⇄ `core/crypto/Fce.kt` (Kotlin)

---

## 1. Core idea: notes backed by single-use StrongBox keys

Offline payments can't check a bank balance. Fortress therefore uses **pre-funded notes**, like UPI Lite X, but makes each note *physically* unspendable twice:

1. While online, the user loads the vault (for example ₹2,000). For each note (₹500, ₹200, … ₹10) the phone generates a fresh P-256 key **inside StrongBox** (TEE fallback) with:
   * `setMaxUsageCount(1)`: KeyMint deletes the key after **one** signature,
   * `setUnlockedDeviceRequired(true)` and a short user-auth window (biometric or device credential),
   * an **attestation challenge** from the server.
2. The server verifies each key's **attestation chain** (Google root → device → key). It checks that the key is hardware-backed, that `USAGE_COUNT_LIMIT=1` is hardware-enforced, the boot state is verified and the app signature is ours. It then debits the user's account into the offline pool, and the **HSM signs a Note Certificate** binding `noteId → note public key → denomination → expiry`.
3. Spending a note = one signature by its key. **A second signature is impossible because the key no longer exists**, even on a rooted phone, since StrongBox keys can't be extracted.

> **Partial spends without change-making.** A ₹100 note can pay ₹73. The spend states `applied = ₹73`, and at settlement the server credits the payee ₹73 and refunds ₹27 to the payer's wallet. No change-making offline is needed.

### Enforcement levels (carried in the certificate, tag 8)

| Value | Meaning | Default policy |
|---|---|---|
| 2 | StrongBox, hardware-enforced single use | Full offline limit |
| 1 | TEE, hardware-enforced single use | Reduced limit |
| 0 | Software-enforced (device lacks `FEATURE_KEYSTORE_SINGLE_USE_KEY`) | Offline vault **disabled** |

Regulatory caps (per-txn and wallet ceiling) come from the current NPCI UPI Lite / offline circulars and are enforced by the issuer when it certifies notes. They are never hard-coded on the device.

---

## 2. Canonical encoding (FCE/1)

Every signed or encrypted structure uses **FCE/1**, a strict TLV encoding:

```
field   = tag:u8 || len:u32be || value[len]
message = field*            — tags strictly ascending, unknown tags rejected, max 64 KiB
u64     = 8 bytes big-endian (values ≥ 2^63 rejected, for parity with Kotlin Long)
list    = count:u32be || (len:u32be || item)*      — max 64 items
signing input = ASCII(domain) || 0x00 || body
signature     = ECDSA-P256-SHA256, ASN.1 DER
envelope      = { 1: body, 2: signature }
```

Golden vector, tested in both languages: `{1: u64 5, 2: "ab"}` → `0100000008000000000000000502000000026162`.

## 3. Structures

| Structure | Tags |
|---|---|
| **NoteCertificate** (domain `FORTRESS/NOTE-CERT/v1`, signed by issuer HSM) | 1 noteId[16] · 2 walletId[16] · 3 notePublicKey (SPKI DER) · 4 denominationPaise · 5 issuedAt (unix s) · 6 expiresAt · 7 issuerKeyId[1..32] · 8 enforcement |
| **ReceiverCertificate** (`FORTRESS/RECEIVER-CERT/v1`, issuer HSM) | 1 receiverId[16] (opaque, *not* a VPA hash) · 2 displayName (UTF-8 ≤ 64 B) · 3 identityPublicKey (SPKI) · 4 issuedAt · 5 expiresAt · 6 issuerKeyId |
| **Frame** | 1 type (1 HELLO, 2 OFFER, 3 PAY, 4 RECEIPT, 15 ABORT) · 2 payload |
| **Hello** | 1 protocolVersion (=1) · 2 payerEphemeralPub[65] · 3 payerNonce[32] |
| **Offer** | 1 payeeEphemeralPub[65] · 2 payeeNonce[32] · 3 sealedRequest |
| **OfferRequest** (plaintext) | 1 signedReceiverCert · 2 requestedAmountPaise (0 = payer chooses) · 3 payeeSignature |
| **OfferSigBody** (`FORTRESS/OFFER/v1`, payee identity key) | 1 SHA-256(helloPayload) · 2 payeeEphemeralPub · 3 payeeNonce · 4 receiverId · 5 requestedAmountPaise |
| **Pay** | 1 sealedBundle |
| **PaymentBundle** (plaintext) | 1 paymentId[16] · 2 transcriptHash[32] · 3 receiverId[16] · 4 totalAmountPaise · 5 createdAtMillis · 6 spends (list of SpendItem) |
| **SpendItem** | 1 signedNoteCert · 2 appliedAmountPaise · 3 spendSignature |
| **SpendSigBody** (`FORTRESS/SPEND/v1`, **single-use note key**) | 1 noteId · 2 paymentId · 3 receiverId · 4 transcriptHash · 5 appliedAmountPaise · 6 totalAmountPaise · 7 createdAtMillis |
| **Receipt** | 1 sealedReceipt |
| **ReceiptBody** (`FORTRESS/RECEIPT/v1`, payee identity key, sent as envelope) | 1 paymentId · 2 SHA-256(bundle plaintext) · 3 status (1 = ACCEPTED) · 4 receivedAtMillis |
| **Abort** | 1 reasonCode |

## 4. Handshake

```
Payer (central / NFC reader)                         Payee (peripheral / HCE card)
────────────────────────────                         ─────────────────────────────
eP ← fresh P-256, nP ← 32 random bytes
HELLO {v=1, pub(eP), nP}  ───────────────────────►
                                                     eQ ← fresh P-256, nQ ← random
                                                     Z  = ECDH(eQ, pub(eP))          (point validated on curve)
                                                     PRK = HKDF-Extract(nP‖nQ, Z)
                                                     k_qp‖k_pq = HKDF-Expand(PRK, "FORTRESS/SESSION/v1"‖pub(eP)‖pub(eQ), 64)
                                                     sig = Sign_identity(OfferSigBody)
                       ◄───────────────────────────  OFFER {pub(eQ), nQ, AEAD(k_qp, ctr 0, AAD_OFFER, OfferRequest)}
verify ReceiverCert (issuer sig, expiry)
verify sig with cert.identityPublicKey
T = SHA-256("FORTRESS/TRANSCRIPT/v1"‖0‖{1:hello, 2:pub(eQ), 3:nQ, 4:OfferRequest})
SAS = 4 digits from SHA-256("FORTRESS/SAS/v1"‖0‖T)   (shown on both screens for BLE)
── user confirms name + amount (+ biometric) ──
select notes (minimise stranded value), sign SpendSigBody with each note key (keys self-destruct)
persist bundle to encrypted outbox *before* transmitting
PAY {AEAD(k_pq, ctr 0, AAD_PAY‖T, PaymentBundle)} ──►
                                                     verify every note: issuer sig, expiry, applied ≤ denom,
                                                       spend sig, same wallet, unique noteIds, Σapplied = total,
                                                       receiverId = mine, transcript = T, amount = requested
                                                     record noteIds (UNIQUE → local replay guard) + bundle in inbox
                       ◄───────────────────────────  RECEIPT {AEAD(k_qp, ctr 1, AAD_RECEIPT‖T, Envelope(ReceiptBody))}
verify receipt sig, paymentId, bundleHash
```

**AEAD:** AES-256-GCM, 96-bit nonce = `direction:u32be ‖ counter:u64be` (direction 1 = payee→payer, 2 = payer→payee). Each direction has its own key and every counter is used once, so nonce reuse can't happen. Associated data: `AAD_OFFER = "FORTRESS/AAD/OFFER/v1"‖0‖SHA-256(hello)‖pub(eQ)‖nQ`, `AAD_PAY/RECEIPT = "FORTRESS/AAD/PAY|RECEIPT/v1"‖0‖T`.

**Transport framing:**
* BLE: GATT service `6f1d0001-8f5a-4c3e-9b8e-3a1f0c7d2b10`. Payer writes chunks to RX `…0002`; payee notifies on TX `…0003`. Each chunk is `flags:u8 ‖ data`, where `flags & 1` = final. MTU 517 is requested and the chunk size is `MTU − 4`. Advertising carries **only** the service UUID: no device name, no PII, low TX power for proximity, and a randomized MAC from the OS.
* NFC: payee runs HCE with AID `F0464F5254524553`. Payer sends `80 D0 P1 00 Lc data 00` (P1 = 01 means more chunks), and responses are chained with `61xx` / `GET RESPONSE`. HCE requires an unlocked device.

## 5. Failure semantics (no value is ever lost)

| Failure point | Outcome |
|---|---|
| Before any note is signed | Notes return to AVAILABLE. |
| After signing, before the bundle is persisted (crash) | Key consumed, no spend exists anywhere → the server **auto-refunds** the note after `expiresAt + grace`. |
| Bundle persisted, PAY not delivered | Payer's outbox settles it: **the payee is credited even though they never saw it**, because the receiverId is signed. |
| PAY delivered, RECEIPT lost | Both sides hold the bundle. The server settles idempotently (`noteId` + digest of the signed spend body). |
| Same note seen with two different digests | `CONFLICT`: the first settlement wins, the device is flagged, and the fraud pipeline runs. This can only happen with software-enforced keys or a hardware break. |

## 6. Settlement rules (offline-mesh)

1. Parse strictly (FCE/1). Reject unknown tags and oversize input.
2. Verify each note certificate against the **issuer keyring** (HSM public keys by `issuerKeyId`).
3. Verify each spend signature with the **note public key** in its certificate.
4. Invariants: 1–16 spends; all notes from the **same wallet**; unique noteIds; `0 < applied ≤ denomination`; `Σapplied = total`; submission time ≤ `expiresAt + settlementGrace` (default 7 days).
5. **Redeem** (return unspent value while online): `receiverId = 0¹⁶`, `transcript = 0³²`, `applied = 0`, `total = 0`. The full denomination is refunded.
6. Atomically claim every `noteId` with the SHA-256 of its spend signing input, then emit ledger instructions: credit `receiverId` with `total`, and refund `Σ(denomination − applied)` to `walletId`.
7. Reply `SETTLED`, `DUPLICATE` (idempotent replay) or `CONFLICT`.

## 7. Threat model summary

| Threat | Control |
|---|---|
| Double spend | Single-use StrongBox key (hardware), server note-claim uniqueness (detection), enforcement-level policy |
| Rogue payee or MITM | Issuer-signed receiver certificate + identity-key signature over both ephemeral keys; SAS for BLE |
| Relay attack | Gains nothing: the signed receiverId pins the funds to the real payee. NFC proximity and BLE low TX power add friction |
| Sniffing (BLE/NFC) | Ephemeral ECDH + AES-256-GCM. No PII in advertising. Nonces are fresh per session |
| Replay to the same payee | Payee-local UNIQUE(noteId); transcript binding |
| Clock tampering | Expiry is enforced by the server at settlement; the payee's local check is advisory |
| Stolen unlocked phone | Spending needs biometric/credential within a 30 s window; the vault is capped by regulation |
| Device storage theft | SQLCipher with a 256-bit raw key wrapped by a StrongBox AES-GCM KEK (unlocked-device-only), plus no-backup storage |

# Launching Fortress legally: Play Store and payments compliance

This is an engineering checklist, not legal advice. Before launch, have a payments lawyer and your sponsor bank's compliance team confirm each item against the circulars in force on your launch date. Items marked **verify** change often.

## The short answer

You can launch a UPI app legally **only as a Third-Party Application Provider (TPAP)** working through a **sponsor PSP bank** with **NPCI's approval**. In that model:

* NPCI operates UPI.
* Banks hold the accounts and move the money.
* Fortress provides the app.

Operating a payment system yourself without RBI authorisation is an offence under the Payment and Settlement Systems Act, 2007, punishable with imprisonment and fines. The TPAP route keeps you inside the authorised system.

The code in this repository is built for that model:

* It talks to a PSP through `services/payments`.
* The UPI PIN goes only through NPCI's Common Library.
* Device binding follows NPCI's rules.
* Limits come from server policy.

Today it runs against a **sandbox PSP**, so no real money moves until the bank integration below is done.

## 1. Partners and approvals (before any real payment)

| # | Item | Owner | Status in repo |
|---|---|---|---|
| 1 | Incorporate an Indian company. Banks and NPCI do entity due diligence. | Founders | ◻ |
| 2 | Sign with a **sponsor PSP bank** (ideally two for redundancy). The bank applies to NPCI to onboard you as a TPAP. | Founders, bank | ◻ |
| 3 | **NPCI TPAP approval**, then UAT and certification of the app against NPCI's UPI test cases (payments, collect, mandates, UDIR disputes, device binding). | Bank, NPCI, engineering | Flows built; sandbox only |
| 4 | Integrate the bank's SDK, which bundles **NPCI's Common Library** (PIN entry). Release builds use `UnlinkedCommonLibrary` until then, so a release APK cannot take a PIN. | Engineering | ◻ link SDK |
| 5 | Replace `sandboxpsp` with the bank's API adapter (pay, status, validate-address, mobile mapper, account discovery, balance). | Engineering | Ports defined in `services/payments/internal/app` |
| 6 | Integrate **UDIR** (NPCI's dispute system) for in-app complaints. The app UI and copy are ready; the backend call is a stub. | Engineering | ◻ |
| 7 | **Bill payments**: onboard to Bharat BillPay (NBBL) through a BBPOU bank. Recharges also run on BBPS or through operator aggregators. | Founders, bank | Preview only |

## 2. Offline payments: why the cap exists and what you may launch

* RBI's **framework for small-value digital payments in offline mode** allows offline payments only within per-payment and total caps. NPCI's **UPI Lite** caps match: currently ₹1,000 per payment and ₹5,000 balance (**verify** the latest circular).
* Fortress therefore cannot offer "any amount up to the daily UPI limit" without internet. With internet, users can pay up to their full UPI limit (₹1 lakh a day for most banks).
* The app enforces the caps it receives from `GET /v1/limits`; the server reads them from configuration (`OFFLINE_PER_TXN_PAISE`, `OFFLINE_WALLET_PAISE`). When RBI or NPCI raise a cap, change the config and every app follows without an update.
* **Important:** Fortress's hardware-sealed note protocol is a technical design. Launching it to the public requires an authorised operator to run it, under RBI's offline framework or as **UPI Lite / UPI Lite X** with an issuer bank. Do not launch offline payments on your own licence. Until a bank partner supports it, ship the vault switched off in release builds.

## 3. Data, security and audits

| Requirement | What it means for Fortress | Status |
|---|---|---|
| RBI **Storage of Payment System Data** (2018) | All payment data stored only in India. A **System Audit Report** by a CERT-In empanelled auditor before go-live and yearly. | Architecture is India-only; ◻ audit |
| **CERT-In Directions** (2022) | Report cyber incidents within 6 hours, keep logs in India for 180 days, and sync clocks to Indian NTP sources. | ◻ runbook, log retention |
| **VAPT** and app security review by a CERT-In empanelled auditor | Usually demanded by the sponsor bank and NPCI before certification and yearly after. | Hardening in place; ◻ audit |
| NPCI security requirements for TPAPs | Device binding, PIN only via the CL, no storage of PINs or full card data, fraud and risk monitoring. | Implemented (binding, CL boundary, integrity checks) |
| **DPDP Act, 2023** and **DPDP Rules, 2025** | A consent notice in plain language (English and Indian languages), purpose limitation, data-principal rights (access, correction, erasure), breach notification, and a grievance contact. **Verify** which obligations are in force at launch; the Rules phase in. | ◻ notices, ◻ rights workflow |

## 4. Customer protection

* **Failed-transaction timelines and compensation** under RBI's turnaround-time framework: failed UPI debits must be auto-reversed within T+1, with compensation after that. Show the status honestly; the app already says "money not debited" and tracks pending states.
* **Three-level grievance redressal**, published in the app and on the website:
  1. In-app support.
  2. A named Grievance Officer with contact details.
  3. The RBI Integrated Ombudsman (cms.rbi.org.in) if the complaint is not resolved in 30 days.

  The Help screen already shows this structure. ◻ Publish the officer's name and contact.
* **Fraud education**: "you never need a PIN to receive money" appears on request screens. Keep the fraud shield and new-payee warnings on by default.

## 5. Google Play Console

| Item | Notes |
|---|---|
| **Organization developer account** | Requires a D-U-N-S number for the company. |
| **Financial features declaration** | Mandatory. Declare UPI payments (P2P, P2M), bill payments and offline payments. Be ready to show your sponsor-bank agreement and NPCI approval if review asks. |
| **Payments policy** | Real-world payments and P2P transfers don't use Google Play Billing; declare them correctly. |
| **Data safety form** | Must match the app: phone number, device identifiers, financial info (account references, transaction history), approximate location if used for fraud checks. Data is encrypted in transit; users can request deletion. |
| **Privacy policy URL** | Hosted on your domain and linked in-app (About → Privacy). |
| **Account deletion** | Play requires deletion both in-app and through a web link. Sign-out exists in-app. ◻ Add "Delete account" to the Android app (the preview shows the flow) and a web form. |
| **Permissions** | Fortress requests camera, biometric, Bluetooth, NFC and phone state. It does **not** request SEND_SMS or READ_SMS; binding opens a pre-filled SMS the user sends, which keeps the app out of Play's restricted SMS policy. Keep it that way. |
| **Target API level** | `targetSdk 36` meets the current requirement. **Verify** each August. |
| **Play App Signing and Integrity** | Use Play App Signing. Set `EXPECTED_SIGNER_SHA256` to the Play signing certificate, and link the Play Integrity Cloud project (`PLAY_CLOUD_PROJECT_NUMBER`). |
| **Content rating and audience** | Finance app for 18+; no ads unless declared. |
| **Store listing** | Use NPCI/UPI marks only as NPCI's brand guidelines allow. Name the sponsor bank as your banking partner. Don't claim "RBI approved" unless it is literally true. |

## 6. Brand

* Search the Trade Marks Registry (classes 9 and 36) and the Play Store for "Fortress" before you invest in the name. It is a common word, and payments brands are contested.
* Keep the logo files in `docs/assets/brand/` as the single source for the launcher icon, store icon (`play-store-icon-512.png`) and in-app mark.

## 7. What's real today and what's still sandbox

| Area | Today |
|---|---|
| Android app | Compiles; 19 unit tests pass; debug and release (R8) APKs build. Pearl and Midnight themes, Home grid, settings, My QR, scanner, offline vault UI and protocol engine, device binding, app lock. |
| UPI payments | Full flow (create → CL PIN → authorise → poll) against the **sandbox PSP**. ◻ Bank SDK and API. |
| Payee verification | `GET /v1/directory/vpa/{vpa}` and `/v1/directory/mobile/{n}` exist; sandbox answers. ◻ Bank's validate-address and mapper. |
| Limits | Served by `GET /v1/limits` from configuration; the app enforces them. |
| Offline vault | Protocol, StrongBox notes, settlement service and caps are built. ◻ An authorised operator (see section 2) before release. |
| Bills and recharge | Designed in the preview. ◻ BBPS onboarding and Android screens. |
| Backend storage | In-memory stores refuse to start in production. ◻ CockroachDB adapters, HSM signer. |
| Go services | Written to compile with the Go 1.27 standard library; CI builds and tests them. They were not compiled on this machine because the Go toolchain wasn't installed. |

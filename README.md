<p align="center"><img src="docs/assets/brand/fortress-mark.png" width="140" alt="Fortress UPI"></p>

# Fortress — Pay Anytime • Offline

A UPI app built around three ideas:

1. **Hardware-rooted trust.** Every API call, offline note and receipt is signed inside StrongBox/TEE (Android) or the Secure Enclave (iOS) and verified against attested keys.
2. **Real offline payments.** Pre-funded notes are backed by **single-use StrongBox keys** (`setMaxUsageCount(1)`) and moved over BLE or NFC with end-to-end encryption. They settle automatically when either phone reconnects.
3. **A platform that proves itself.** Distroless images, SHA-pinned CI, SAST/secret/CVE/IaC gates and a **p99 < 15 ms** load gate. Nothing ships unverified.

| Read | What's inside |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engineering ground truth, monorepo map, system diagram, multi-tier load balancing, capacity, SLOs |
| [docs/OFFLINE_PROTOCOL.md](docs/OFFLINE_PROTOCOL.md) | FOP/1: notes, handshake, crypto, framing, failure semantics, threat model |
| [docs/SECURITY.md](docs/SECURITY.md) | Device binding, RASP, memory hygiene, pipeline gates, compliance mapping, residual risks |
| [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md) | Pearl and Midnight themes, tokens with measured contrast, motion, 120 Hz rules, page specs |
| [docs/LAUNCH_COMPLIANCE.md](docs/LAUNCH_COMPLIANCE.md) | What it takes to launch legally: TPAP and sponsor bank, NPCI certification, offline caps, audits, DPDP, Play Console |
| [apps/preview](apps/preview) | Interactive web preview of every screen (`python -m http.server 4173 --directory apps/preview`) |

## Repository

```
apps/mobile/android   Kotlin · Compose · AGP 9 — app, core:{crypto,security,offline,designsystem}
apps/mobile/ios       SwiftPM FortressCore (Secure Enclave signer, FCE, tokens)
services/             Go 1.27, stdlib-only — core-ledger, identity, payments, offline-mesh, security-gateway
shared/               Go — crypto (FCE, P-256), platform (server bootstrap, metrics)
deploy/docker         Multi-stage → distroless:nonroot
infra/                Kubernetes (PSA restricted, KEDA, NetworkPolicies), Kong
.github/workflows     Zero-trust CI/CD
```

## Run it

```bash
# Go services (unit tests incl. race detector)
cd services/offline-mesh && GOWORK=off go test -race ./...

# Offline settlement engine with a throwaway signed fixture
cd services/offline-mesh && go run ./cmd/genfixture > /tmp/fx.json
ISSUER_KEYS="$(jq -r .issuerKeys /tmp/fx.json)" go run ./cmd/offline-mesh

# Container (from repo root)
docker build -f deploy/docker/go-service.Dockerfile --build-arg SERVICE=offline-mesh -t fortress/offline-mesh .

# Android (Gradle 9.8; generate the wrapper once)
cd apps/mobile/android && gradle wrapper --gradle-version 9.8.0 && ./gradlew :app:assembleDebug testDebugUnitTest
```

Cross-language golden vectors (`fce_test.go`, `CryptoCoreTest.kt`, `FCETests.swift`, `devicesig_test.go`, `RequestSignerTest.kt`) keep the Go, Kotlin and Swift encoders byte-identical.

## Before production

- [ ] Sponsor PSP bank(s) + NPCI onboarding as a TPAP, and NPCI Common Library integration (`feature/upi-online`)
- [ ] Offline vault operated by an authorised partner (UPI Lite / RBI offline framework); caps come from `GET /v1/limits`; issuer HSM (Luna) key ceremony; set `fortress.issuerKeys`
- [ ] Replace the placeholder SPKI pins (the release gate enforces this) and set `fortress.expectedSignerSha256`
- [ ] Durable adapters: CockroachDB (`crdb`), Redis nonce store, Kafka outbox, PKCS#11 signer
- [ ] Server-side Key Attestation + Play Integrity verification service
- [ ] CI secrets: `SONAR_TOKEN`, `SONAR_HOST_URL`. Branch protection requiring every `ci` job
- [ ] Third-party VAPT + CERT-In empanelled audit before go-live
- [ ] Everything in [docs/LAUNCH_COMPLIANCE.md](docs/LAUNCH_COMPLIANCE.md), including the Play Console financial-features declaration

# Fortress iOS (baseline)

```
ios/
├── FortressCore/                 SwiftPM package (this repo)
│   ├── Sources/FortressCore/
│   │   ├── FCE.swift             Canonical encoding (golden-vector parity with Go/Kotlin)
│   │   ├── SecureEnclaveSigner.swift  P-256 keys inside the Secure Enclave, ThisDeviceOnly
│   │   └── FortressTheme.swift   Design tokens + motion springs (mirrors Compose tokens)
│   └── Tests/FortressCoreTests/
└── FortressApp/                  ◻ Xcode app target (create in Xcode, add FortressCore as a local package)
    ├── Features/Dashboard        SwiftUI, LazyVStack + ProMotion (CADisableMinimumFrameDurationOnPhone = YES)
    ├── Features/Scanner          AVFoundation + Vision VNDetectBarcodesRequest (on-device)
    └── Features/Offline          CoreBluetooth central/peripheral (foreground), CoreNFC reader
```

## Platform constraints that shape the iOS offline design

| Capability | iOS reality | Fortress approach |
|---|---|---|
| Hardware key | Secure Enclave P-256 (no per-key usage counter) | Identity + request keys in the Enclave; offline notes issued at the **TEE tier** with lower caps |
| Device attestation | App Attest (`DCAppAttestService`) | Server verifies App Attest assertions at binding and per sensitive call |
| BLE | Central and peripheral, but advertising is limited in the background | Offline pay/receive only while the app is in the foreground |
| NFC card emulation (HCE) | Only through Apple's regional NFC & SE platform entitlement | iOS acts as **payer** (CoreNFC reader) by default; receive uses BLE unless the entitlement is granted for India |
| Jailbreak / hook detection | Same caveats as Android RASP | App Attest + server-side risk scoring are authoritative |

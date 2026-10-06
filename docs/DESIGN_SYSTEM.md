# Fortress Design System

Luxury, calm and fast. Fortress should feel like a private bank: a deep navy and gold identity taken from the logo, generous white space, and colour used only when something matters.

## 1. Themes and tokens

Two themes share one identity. Users choose in **Profile → Appearance** (Pearl, Midnight or System); the choice is saved on the device and applied instantly, including the status-bar icons.

* **Pearl** (default). A bright, calm daytime theme. Light surfaces, navy ink, a navy hero behind the brand, gold accents.
* **Midnight**. A deep navy night theme. It is never flat black: the darkest surface is `#070E22`, so the logo's navy reads as part of the same family.

| Token | Pearl | Midnight | Use |
|---|---|---|---|
| Background | `#F3F4F8` | `#070E22` | App background |
| Surface / Raised | `#FFFFFF` / `#F7F8FB` | `#0F1A38` / `#132148` | Cards, sheets, inputs |
| Line | `#E4E7EF` | `#22315C` | 1 dp borders, dividers |
| Ink | `#0C1633` | `#EEF1F8` | Primary text, amounts |
| Ink Muted / Dim | `#4B5570` / `#5F6882` | `#AEB7D0` / `#8790AC` | Secondary and tertiary text |
| Gold Accent | `#8A6418` | `#F2D488` | Gold text, active tab, icon tints |
| Gold | `#A97A26` | `#E2B656` | Focus rings, selected borders, sparkline |
| Tile | `#EEF1F8` | `#172752` | Feature-tile and keypad backgrounds |
| Emerald | `#0B7F58` | `#35D49A` | Money received, success, toggles on |
| Sapphire | `#2F5BD3` | `#8EA9FF` | System and trust states (settles on sync) |
| Danger | `#C42F43` | `#FF7182` | Errors, blocked payments, sign-out |
| Hero gradient | `#0A1636 → #122656 → #1C3A7D` | `#0B1840 → #132A62 → #1F3F8C` | Home hero, My QR card, profile card |

Brand constants, identical in both themes: the gold gradient `#F4D78E → #D7A54A → #A97A26` (primary buttons, the Scan button) with `#1A1305` text on it, white and `#F0CF7E` text on the navy hero, and saffron and green only inside the UPI glyph.

Source: `core/designsystem/Tokens.kt` (`PearlPalette`, `MidnightPalette`, `FortressColors`) on Android and `apps/preview/styles.css` (`.device` and `.device[data-app-theme="midnight"]`) in the web preview. Colour tokens are read through a CompositionLocal, so inside draw lambdas read them into a local first.

**Logo.** The launcher icon is an adaptive icon (`mipmap-anydpi-v26`): the gold shield from the supplied logo on a navy field, with a monochrome layer for Android 13+ themed icons. In-app, `drawable-nodpi/fortress_mark.png` is the shield cut from the logo with its navy inner field restored. Brand assets live in `docs/assets/brand/`.

**Typography.** Sora for display (geometric, echoing the logo wordmark) and Manrope for text, both licensed under OFL. All amounts use **tabular, lining figures** (`tnum, lnum`) so digits never jitter while animating. Run `scripts/fetch-fonts.sh` to bundle them; until then the system sans is used.

## 2. Motion

| Spec | Value | Used for |
|---|---|---|
| `press` | spring, damping 0.55, stiffness medium | Button and tile press scale (0.93 to 0.96) |
| `settle` | spring, low-bouncy, stiffness 380 | Cards and sheets settling |
| `magnet` | spring, damping 0.82, stiffness 1400 | Scanner brackets snapping onto a QR |
| Navigation | spring slide (1/5 width) + 220 ms fade | Screen transitions; direction-aware |
| Ambient | 3.6 s ease breath | Offline aura, liquid edge sheen (9 s orbit) |

Everything interactive is a **spring**: springs can be interrupted mid-flight without visual seams, while fixed-duration tweens can't.

## 3. 120 Hz discipline (no frame drops)

1. **Animate in the draw phase.** Animated values (press scale, edge sheen, aura, radar rings, particles) are read inside `graphicsLayer {}` or draw lambdas only. A frame then re-runs drawing alone, never composition or layout.
2. **Zero allocation per frame** in the particle engine (`Particles.kt`): primitive arrays, xorshift RNG, and integration on real frame deltas so 60/90/120/144 Hz look identical.
3. **One lazy list per screen** with stable keys and `animateItem()`, so offscreen items are never composed.
4. **High refresh mode** requested at the panel's native resolution (`HighRefreshRate`). Android 15+ adaptive refresh still idles static screens.
5. Gate: Macrobenchmark `FrameTimingMetric` on the dashboard scroll and the scanner lock (P99 frame time < 8.3 ms on the reference device) runs in the baseline-profile module (◻ planned).

## 4. Core pages

### Home
* **Hero**: navy gradient card with the shield, a live network pill, a greeting, the combined balance (masked by default; one tap reveals it and the choice is remembered), and the UPI ID with Copy and My QR.
* **Feature grid**: eight one-tap actions. Scan & pay (gold), Pay UPI ID, Pay phone number, Bank transfer, Tap & Pay, Receive offline, My QR, History.
  * UPI ID, phone and bank transfer open a bottom sheet that validates input as you type.
  * A bank account is paid through NPCI's `account@IFSC.ifsc.npci` address.
  * Each sheet verifies the payee's registered name with their bank (validate-address or mobile mapper) before the review screen.
* **Offline vault card**, **People** (jewel-tone initials avatars), **This month** sparkline, **Recent activity**, and the **Fortress Shield** footer.

### Profile & settings
One screen, every preference one tap away; each sub-page is one level deep with a back button and system back support.
* **Appearance**: three swatches (Pearl, Midnight, System), applied instantly.
* **Payments**:
  * **Payment limits** come from the server (`GET /v1/limits`), with an explanation of the offline cap.
  * **Offline vault** opens the vault.
* **Security & privacy**:
  * **Security center**: binding, key locations and integrity verdicts.
  * Toggles for **Hide balances** and **New payee warnings**.
  * **Auto-lock**: Immediately, 1 minute or 5 minutes.
* **Support**: **Help & complaints** (FAQ and the three-level grievance path) and **About** (version and the TPAP disclosure).
* **Lock now** and **Sign out**. Sign-out destroys this device's keys and binding after a confirmation dialog.

### Smart Scanner
* An on-device ML Kit QR model, `KEEP_ONLY_LATEST` backpressure and the PERFORMANCE preview mode.
* **AI assist**: ML Kit zoom suggestions auto-zoom on small or distant codes (≤ 4×). AF/AE metering is pointed at the detected code (throttled to 600 ms).
* Dimmed surround with a clear window. Brackets **magnetize** onto the QR, turn emerald, play a haptic `CONFIRM`, and hand off to Review after a 140 ms hold. The scanner keeps fixed colours in both themes because it sits on a camera feed.
* A strict UPI parser rejects parameter pollution, bidi spoofing, foreign currency and out-of-range amounts. Online payments then hand off to the NPCI CL for the PIN.

### My QR
A navy card with the user's name, UPI ID and a standard `upi://pay` QR (ZXing, error-correction level H so the centred shield doesn't affect scanning), plus Copy and the Android share sheet.

### Offline Mode
* Activates when the default network loses `VALIDATED`. A **three-beat low-tick haptic heartbeat** plays, and a breathing **emerald→sapphire aura** surrounds the screen.
* **Offline Secure Balance**: the hero amount, the count of hardware-sealed notes, and denomination chips.
* Actions:
  * **Tap & Pay** (NFC): armed by biometric *before* the tap, so one touch completes in about 300 ms.
  * **Pay nearby** (BLE): the closest receiver at low TX power, name and a 4-digit match code, then biometric.
  * **Receive**: HCE and BLE at once.
* **Caps come from server policy**:
  * Per payment: `offlinePerTxnPaise`. The vault holds at most `offlineWalletPaise`.
  * The app refuses larger amounts with a plain explanation, and asks the user to pay online instead.
* A radar-ring animation visualizes the radio field. Success triggers the emerald/gold particle bloom and a rise+click haptic.

## 5. Accessibility

Contrast (WCAG 2.x), measured against the background and surface; the lower of the two is reported:

| Token | Pearl | Midnight |
|---|---|---|
| Ink | 16.2:1 | 15.2:1 |
| Ink Muted | 6.7:1 | 8.6:1 |
| Ink Dim | 5.0:1 | 5.4:1 |
| Gold Accent | 4.9:1 | 11.9:1 |
| Emerald | 4.6:1 | 9.0:1 |
| Sapphire | 5.4:1 | 7.6:1 |
| Danger | 5.0:1 | 6.5:1 |

Notes on the remaining colours:

* Every text token passes AA (≥ 4.5:1) in both themes.
* Gold (`#A97A26` in Pearl) is 3.5:1, so it is used only for borders, focus rings and graphics (≥ 3:1 for non-text UI), never for body text.
* On the gold gradient, `#1A1305` text is 8.2:1. On the navy hero, white is 10.8:1 or higher and hero gold is 7.1:1.

Other rules:

* Every custom control declares a `Role` and a label. Toggles are `Role.Switch`, theme and auto-lock choices are `Role.RadioButton`, and masked balances announce "Balance hidden".
* Haptics complement visual state and never replace it. They follow the system haptics setting.
* Motion: the web preview honours `prefers-reduced-motion`; on Android the ambient loops should follow "Remove animations" (◻ wire to `Settings.Global.ANIMATOR_DURATION_SCALE`).

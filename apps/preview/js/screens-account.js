/* Fortress preview — offline vault, profile & settings, onboarding, lock, splash. */
(() => {
  "use strict";
  const F = window.F, t = (k, v) => F.t(k, v), I = F.icon, esc = F.esc;
  const appbar = (title, right = "") => F.appbar(title, { right });

  F.getPath = (k) => k.split(".").reduce((o, p) => (o ? o[p] : undefined), F.state);
  const row = (icon, title, sub, a, v, extra) => `<button class="li" data-a="${a}" ${v !== undefined ? `data-v="${esc(v)}"` : ""}>
      <span class="lead">${I(icon, 19)}</span><div class="grow"><div class="t">${title}</div>${sub ? `<div class="s">${sub}</div>` : ""}</div>${extra ?? `<span class="trail">${I("chev", 18)}</span>`}</button>`;
  const sw = (key, title, sub, icon, locked) => {
    const on = !!F.getPath(key);
    return `<button class="li" role="switch" aria-checked="${on}" data-a="toggle" data-v="${key}" ${locked ? "disabled" : ""}>
      ${icon ? `<span class="lead">${I(icon, 19)}</span>` : ""}<div class="grow"><div class="t">${title}</div>${sub ? `<div class="s">${sub}</div>` : ""}</div>
      <span class="toggle" aria-checked="${on}" aria-hidden="true" ${locked ? 'style="opacity:.55"' : ""}></span></button>`;
  };
  F.settingRow = row;

  /* ══ Offline vault ═══════════════════════════════════════════ */
  const denomChips = (notes) => {
    const m = new Map();
    notes.forEach((n) => m.set(n, (m.get(n) || 0) + 1));
    return [...m].sort((a, b) => b[0] - a[0]).map(([d, c]) => `<span>${F.inrShort(d)} × ${c}</span>`).join("");
  };

  F.screens.offline = () => {
    const s = F.state, L = s.limits, total = F.vaultTotal(), hide = s.hideBalance;
    const pending = s.txns.filter((x) => x.kind === "offline" && x.status === "settling");
    const pct = Math.min(100, Math.round((total / L.offlineWallet) * 100));
    return {
      tab: "offline",
      html: `<div class="appbar"><div class="title grow">${t("offline.title")}</div><button class="icon-btn" data-a="go" data-v="offlineInfo" aria-label="How offline payments work">${I("info", 18)}</button></div>
        <section class="vault">
          <div class="row"><div class="grow"><div class="lbl">${t("offline.balance").toUpperCase()}</div><div class="bal">${hide ? "₹ ••••" : F.inr(total)}</div></div>
            <button class="hero-btn" data-a="eyeToggle" aria-label="${hide ? "Show" : "Hide"} balance">${I(hide ? "eye" : "eyeoff", 18)}</button></div>
          <div class="small" style="opacity:.82;margin-top:6px">${s.online ? "Works with or without internet" : "No internet · pay with hardware-sealed notes"} · ${s.vault.length} notes</div>
          ${s.vault.length && !hide ? `<div class="note-chips">${denomChips(s.vault)}</div>` : ""}
        </section>
        <div class="grid4" style="grid-template-columns:repeat(3,minmax(0,1fr))">
          <button class="act" data-a="go" data-v="offPay" data-p="tap"><span class="tile gold">${I("nfc", 24)}</span>${t("offline.tap")}</button>
          <button class="act" data-a="go" data-v="offPay" data-p="nearby"><span class="tile">${I("bt", 24)}</span>${t("offline.nearby")}</button>
          <button class="act" data-a="go" data-v="offReceive"><span class="tile">${I("receive", 24)}</span>${t("offline.receive")}</button>
        </div>
        <div class="btn-row"><button class="btn ghost" data-a="loadVault">${I("plus", 18)}${t("offline.load")}</button><button class="btn ghost" data-a="unloadVault">${I("bank", 18)}To bank</button></div>
        <div class="card stack">
          <div class="row"><div class="grow"><div class="eyebrow">Offline limits · set by RBI & NPCI</div></div><span class="pill info">Live from server</span></div>
          <dl class="kv"><dt>Per offline payment</dt><dd>${F.inrShort(L.offlinePerTxn)}</dd><dt>Vault can hold</dt><dd>${F.inrShort(L.offlineWallet)}</dd><dt>With internet (UPI)</dt><dd>${F.inrShort(L.dailyUpi)} / day</dd></dl>
          <div style="height:8px;border-radius:4px;background:var(--bg-2);overflow:hidden"><i style="display:block;height:100%;width:${pct}%;background:var(--gold-grad)"></i></div>
          <div class="small muted">${F.inrShort(total)} of ${F.inrShort(L.offlineWallet)} loaded</div>
          <button class="link" style="text-align:left" data-a="go" data-v="offlineInfo">Why can't I send more without internet?</button>
        </div>
        <div class="section-head"><div class="h2">Waiting to settle</div>${pending.length ? `<span class="pill warn">${pending.length}</span>` : ""}</div>
        ${pending.length ? `<div class="list">${pending.map((x) => F.txRow(x)).join("")}</div>` : `<div class="card flat row small muted">${I("check", 18)}Every offline payment is settled with your bank.</div>`}
        <div class="card stack">
          <div class="h2">How offline payments stay safe</div>
          ${[["vault", "Notes sealed in hardware", "Each note lives in your phone's StrongBox chip and can be signed exactly once."], ["shield", "Receiver verifies instantly", "The other phone checks Fortress's signature on every note, without internet."], ["repeat", "Settles automatically", "When either phone gets internet, the money moves bank-to-bank and any change comes back to you."]]
            .map(([ic, h, d]) => `<div class="row" style="align-items:flex-start"><span class="lead" style="width:36px;height:36px;border-radius:12px;display:grid;place-items:center;background:var(--tile);color:var(--tile-ink);flex:none">${I(ic, 18)}</span><div><div style="font:800 13.5px var(--text)">${h}</div><div class="small muted" style="line-height:1.5">${d}</div></div></div>`).join("")}
        </div>`,
    };
  };

  F.screens.offlineInfo = () => {
    const L = F.state.limits;
    return {
      cls: "full",
      html: `${appbar("Offline limits, explained")}
        <div class="card stack" style="line-height:1.6">
          <div class="h2">Why there's a cap without internet</div>
          <div class="muted">With internet, you can pay up to your full UPI limit (${F.inrShort(L.dailyUpi)} a day). Without internet, no bank can check your balance in real time, so the RBI caps offline payments to keep the risk small if a phone is lost or tampered with.</div>
        </div>
        <div class="card"><dl class="kv">
          <dt>Per offline payment</dt><dd>${F.inrShort(L.offlinePerTxn)}</dd><dt>Offline vault maximum</dt><dd>${F.inrShort(L.offlineWallet)}</dd>
          <dt>Rule source</dt><dd>RBI offline payments framework · NPCI UPI Lite</dd></dl></div>
        <div class="card flat row small" style="align-items:flex-start;line-height:1.55;background:var(--info-bg);color:var(--info);border:0">${I("repeat", 18)}<span>Fortress reads these caps from its server. When the RBI or NPCI raises them, your app updates automatically. No app update is needed.</span></div>
        <div class="card stack" style="line-height:1.6">
          <div class="h2">Need to pay more?</div>
          <div class="muted">Any amount up to your daily UPI limit works the moment you have internet. Fortress retries automatically on weak networks and shows the payment as pending instead of failing.</div>
        </div>
        <div class="card stack" style="line-height:1.6">
          <div class="h2">If your phone is lost</div>
          <div class="muted">Notes can't be copied out of the hardware chip, and every note can be spent only once. Call your bank to block the vault. Unspent notes return to your account at settlement.</div>
        </div>`,
    };
  };

  /* Offline pay: amount → connect (NFC / BLE) → SAS → sending → receipt */
  const DEVICES = (F.OFF_DEVICES = [
    { name: "Asha Kirana Store", vpa: "asha.store@fortresspsp", sub: "Merchant · about 2 m away", biz: true, glyph: "store" },
    { name: "Ananya Rao", vpa: "ananya.rao@fortresspsp", sub: "Phone · about 4 m away" },
  ]);
  F.sas = (a, b) => String(F.hash(a + "|" + b) % 10000).padStart(4, "0");
  const stepList = (F.stepList = (labels, at) => `<div class="steps">${labels.map((l, i) => `<div class="step ${i < at ? "done" : i === at ? "active" : ""}"><span class="dotc">${i < at ? I("check", 13, 3) : ""}</span>${l}</div>`).join("")}</div>`);
  F.OFF_STEPS = ["Secure channel set up (ECDH · AES-256-GCM)", "Notes signed inside StrongBox", "Receiver verified Fortress signatures", "Signed receipt stored on both phones"];

  F.screens.offPay = (p) => {
    p.mode = p.mode || p.id || "tap"; p.phase = p.phase || "amount";
    const L = F.state.limits, total = F.vaultTotal(), amt = F.entryPaise(p.entry);
    const title = p.mode === "tap" ? t("offline.tap") : t("offline.nearby");
    if (p.phase === "amount") {
      const over = amt > L.offlinePerTxn, short = amt > total;
      const hint = over ? `Offline payments are capped at ${F.inrShort(L.offlinePerTxn)} each` : short ? `Only ${F.inr(total)} in your vault. Load more while online.` : `Up to ${F.inrShort(Math.min(L.offlinePerTxn, total))} · no internet needed`;
      return {
        cls: "full",
        html: `${appbar(title)}
          <div class="pay-head"><span class="av lg" style="background:var(--hero)">${I(p.mode === "tap" ? "nfc" : "bt", 28)}</span>
            <div class="h2">${p.mode === "tap" ? "Pay by tapping phones" : "Pay a phone near you"}</div><div class="small muted">From offline vault · ${F.inr(total)}</div></div>
          <div class="amount-entry ${amt ? "" : "empty"}" id="amt"><span class="cur">₹</span><span>${F.entryDisplay(p.entry)}</span><i class="caret"></i></div>
          <div class="center hint ${over || short ? "bad" : ""}" id="offHint">${hint}</div>
          <div class="chips" style="justify-content:center">${[5000, 10000, 20000, 50000].filter((v) => v <= Math.min(L.offlinePerTxn, total)).map((v) => `<button class="chip" data-a="quickAmt" data-v="${v / 100}">${F.inrShort(v)}</button>`).join("")}</div>
          <div style="flex:1"></div>${F.keypad()}
          <button class="btn gold" id="payBtn" data-a="offContinue" ${amt && !over && !short ? "" : "disabled"}>${I(p.mode === "tap" ? "nfc" : "bt", 18)}<span id="payLbl">${amt ? `Pay ${F.inr(amt)} offline` : t("pay.enterAmount")}</span></button>`,
      };
    }
    if (p.phase === "connect") {
      const found = p.mode === "nearby" && p.devices;
      return {
        cls: "full",
        html: `${appbar(title)}
          <div class="center stack" style="gap:6px;margin-top:6px"><div class="small muted">Paying</div><div class="amount">${F.inr(amt)}</div></div>
          <div class="radar"><i></i><i></i><i></i>${I(p.mode === "tap" ? "nfc" : "bt", 46)}</div>
          <div class="center stack" style="gap:6px"><div class="h2">${p.mode === "tap" ? "Hold near the receiver's phone" : found ? "Choose who you're paying" : "Looking for nearby Fortress phones"}</div>
            <div class="small muted">${p.mode === "tap" ? "Keep the phones back-to-back for a second" : "Bluetooth works up to about 10 metres"}</div></div>
          ${found ? `<div class="list">${DEVICES.map((d, i) => `<button class="li" data-a="offPickDevice" data-v="${i}">${F.avatar(d.name, "sm", { biz: d.biz, glyph: d.glyph, verified: true })}<div class="grow"><div class="t">${esc(d.name)}</div><div class="s">${d.sub}</div></div><span class="trail">${I("chev", 18)}</span></button>`).join("")}</div>` : ""}
          <div style="flex:1"></div><button class="btn ghost" data-a="back">${t("common.cancel")}</button>`,
        mount: () => {
          if (p.waiting) return;
          p.waiting = true;
          if (p.mode === "tap") F.later(1900, () => { p.to = DEVICES[0]; p.phase = "sending"; p.step = 0; p.waiting = false; F.refresh(); });
          else F.later(1500, () => { p.devices = true; F.refresh(); });
        },
      };
    }
    if (p.phase === "sas") {
      return {
        cls: "full",
        html: `${appbar(title)}
          <div class="pay-head">${F.avatar(p.to.name, "lg", { biz: p.to.biz, glyph: p.to.glyph, verified: true })}<div class="h2">Paying ${esc(p.to.name)}</div><div class="amount">${F.inr(amt)}</div></div>
          <div class="card center stack"><div class="eyebrow">Security code</div><div class="sas">${F.sas(p.to.name, amt)}</div>
            <div class="small muted" style="line-height:1.5">Check that ${esc(p.to.name.split(" ")[0])}'s phone shows the same 4 digits. This stops anyone nearby from pretending to be them.</div></div>
          <div style="flex:1"></div>
          <div class="btn-row"><button class="btn ghost" data-a="back">Codes differ</button><button class="btn gold" data-a="offConfirm">${I("finger", 18)}Codes match</button></div>`,
      };
    }
    /* sending */
    return {
      cls: "full",
      html: `${appbar(title)}
        <div class="pay-head">${F.avatar(p.to.name, "lg", { biz: p.to.biz, glyph: p.to.glyph, verified: true })}<div class="h2">Paying ${esc(p.to.name)}</div><div class="amount">${F.inr(amt)}</div></div>
        <div class="card">${stepList(F.OFF_STEPS, p.step || 0)}</div>
        <div class="center small muted">Works with flight mode on. Nothing leaves either phone unencrypted.</div>`,
      mount: () => {
        if (p.running) return;
        p.running = true;
        const tick = () => {
          p.step = (p.step || 0) + 1; F.refresh();
          if (p.step < F.OFF_STEPS.length) F.later(520, tick);
          else F.later(380, () => F.commitOffline(p));
        };
        F.later(520, tick);
      },
    };
  };

  F.screens.offReceive = (p) => {
    p.phase = p.phase || "wait";
    const from = DEVICES[1], amt = 25000;
    if (p.phase === "wait") {
      return {
        cls: "full",
        html: `${appbar(t("offline.receive"))}
          <div class="radar" style="margin-top:20px"><i></i><i></i><i></i>${I("receive", 46)}</div>
          <div class="center stack" style="gap:6px"><div class="h2">Ready to receive</div><div class="small muted">Ask the payer to tap your phone or choose you in “Pay nearby”.</div></div>
          <div class="card"><dl class="kv"><dt>Receiving as</dt><dd>${esc(F.state.user.name)}</dd><dt>Receiver certificate</dt><dd>Valid · renews when online</dd><dt>NFC & Bluetooth</dt><dd>On</dd></dl></div>
          <div style="flex:1"></div><button class="btn ghost" data-a="back">${t("common.cancel")}</button>`,
        mount: () => { if (!p.waiting) { p.waiting = true; F.later(2600, () => { p.phase = "offer"; F.refresh(); }); } },
      };
    }
    if (p.phase === "offer") {
      return {
        cls: "full",
        html: `${appbar(t("offline.receive"))}
          <div class="pay-head">${F.avatar(from.name, "lg", { verified: true })}<div class="h2">${esc(from.name)} wants to pay you</div><div class="amount">${F.inr(amt)}</div></div>
          <div class="card center stack"><div class="eyebrow">Security code</div><div class="sas">${F.sas(from.name, amt)}</div><div class="small muted">Make sure ${esc(from.name.split(" ")[0])}'s screen shows the same code.</div></div>
          <div style="flex:1"></div>
          <div class="btn-row"><button class="btn ghost" data-a="back">Decline</button><button class="btn gold" data-a="offAccept">${I("check", 18)}Accept ${F.inrShort(amt)}</button></div>`,
      };
    }
    return {
      cls: "full",
      html: `${appbar(t("offline.receive"))}
        <div class="pay-head">${F.avatar(from.name, "lg", { verified: true })}<div class="h2">Receiving from ${esc(from.name)}</div><div class="amount">${F.inr(amt)}</div></div>
        <div class="card">${stepList(["Secure channel set up", "Notes checked against Fortress's signature", "Notes stored in your vault, sealed", "Signed receipt sent to the payer"], p.step || 0)}</div>`,
      mount: () => {
        if (p.running) return;
        p.running = true;
        const tick = () => { p.step = (p.step || 0) + 1; F.refresh(); if (p.step < 4) F.later(480, tick); else F.later(380, () => F.commitReceive(from, amt)); };
        F.later(480, tick);
      },
    };
  };

  /* ══ Profile & settings ══════════════════════════════════════ */
  const themeName = () => t("theme." + (F.state.theme || "pearl"));
  F.screens.profile = () => {
    const s = F.state, u = s.user, L = s.limits;
    return {
      tab: "profile",
      html: `<div class="appbar"><div class="title grow">${t("profile.title")}</div><button class="icon-btn" data-a="go" data-v="help" aria-label="${t("set.help")}">${I("help", 18)}</button></div>
        <div class="profile-card">${F.avatar(u.name, "lg")}<div class="grow"><div style="font:700 18px var(--display)" class="ellipsis">${esc(u.name)}</div>
          <div class="small muted">${esc(u.phone)}</div><div class="small muted ellipsis">${esc(u.vpa)}</div></div>
          <button class="hero-btn" data-a="go" data-v="myqr" aria-label="${t("home.myQr")}">${I("qr", 20)}</button></div>
        <div class="grid4">
          <button class="act" data-a="checkBalance"><span class="tile">${I("wallet", 22)}</span>${t("act.balance")}</button>
          <button class="act" data-a="go" data-v="accounts"><span class="tile">${I("key", 22)}</span>UPI PIN</button>
          <button class="act" data-a="go" data-v="appearance"><span class="tile">${I("moon", 22)}</span>${t("set.theme")}</button>
          <button class="act" data-a="lockNow"><span class="tile">${I("lock", 22)}</span>Lock now</button>
        </div>
        <div class="eyebrow group-label">${t("set.payments")}</div>
        <div class="list">
          ${row("bank", t("set.accounts"), `${s.accounts.length} linked · primary ${esc(F.primary().bank)}`, "go", "accounts")}
          ${row("at", t("set.upiIds"), `${esc(u.vpa)}${u.vpas.length > 1 ? ` · +${u.vpas.length - 1} more` : ""}`, "go", "upiIds")}
          ${row("gauge", t("set.limits"), `${F.inrShort(L.dailyUpi - L.usedToday)} left today`, "go", "limits")}
          ${row("repeat", t("set.autopay"), `${s.mandates.filter((m) => m.status === "active").length} active mandates`, "go", "autopay")}
          ${row("vault", t("offline.title"), `${F.inr(F.vaultTotal())} ready offline`, "tab", "offline")}
        </div>
        <div class="eyebrow group-label">${t("set.security")}</div>
        <div class="list">
          ${row("shield", t("set.security"), `App lock ${s.appLock ? "on" : "off"} · ${s.biometric ? "fingerprint" : "PIN"} · auto-lock ${s.autoLock.toLowerCase()}`, "go", "security")}
          ${row("eyeoff", t("set.privacy"), "Balances, contacts, your data", "go", "privacy")}
        </div>
        <div class="eyebrow group-label">${t("set.app")}</div>
        <div class="list">
          ${row("bell", t("set.notifications"), "Payments, requests, bills, security", "go", "notifSettings")}
          ${row("moon", t("set.appearance"), themeName(), "go", "appearance")}
          ${row("globe", t("set.language"), s.lang === "hi" ? "हिन्दी" : "English", "go", "language")}
        </div>
        <div class="eyebrow group-label">${t("set.support")}</div>
        <div class="list">
          ${row("help", t("set.help"), "FAQs, complaints, chat with us", "go", "help")}
          ${row("info", t("set.about"), "Version, licences, legal", "go", "about")}
        </div>
        <button class="btn danger" data-a="signOut">${I("logout", 18)}${t("set.signout")}</button>
        <div class="center small dim">Fortress 1.0.0 (preview) · Your data stays in India</div>`,
    };
  };

  F.screens.accounts = () => ({
    cls: "full",
    html: `${appbar(t("set.accounts"))}
      ${F.state.accounts.map((a) => `<div class="card stack">
        <div class="row">${F.avatar(a.bank, "", { biz: true, glyph: "bank" })}<div class="grow"><div style="font:800 15px var(--text)" class="ellipsis">${esc(a.bank)}</div><div class="small muted">${a.type} •• ${a.masked} · ${a.ifsc}</div></div>${a.primary ? '<span class="pill gold">Primary</span>' : ""}</div>
        <div class="btn-row"><button class="btn sm ghost" style="width:100%" data-a="checkBalance" data-v="${a.id}">${I("wallet", 16)}Balance</button><button class="btn sm ghost" style="width:100%" data-a="changePin" data-v="${a.id}">${I("key", 16)}Change PIN</button></div>
        <div class="row" style="gap:16px;flex-wrap:wrap">${a.primary ? "" : `<button class="link" data-a="setPrimary" data-v="${a.id}">Make primary</button>`}<button class="link" data-a="resetPin" data-v="${a.id}">${t("pin.forgot")}</button>${a.primary ? "" : `<button class="link" style="color:var(--bad)" data-a="removeBank" data-v="${a.id}">Remove</button>`}</div>
      </div>`).join("")}
      <button class="btn gold" data-a="addBank">${I("plus", 18)}Add bank account</button>
      <div class="card flat small muted" style="line-height:1.55">Fortress finds accounts linked to your verified mobile number. Your UPI PIN is set on NPCI's secure screen. Fortress never sees your PIN or card details.</div>`,
  });

  F.screens.upiIds = (p) => {
    const v = (p.nv || "").toLowerCase(), taken = ["aarav", "admin", "fortress", "support"].includes(v);
    const ok = /^[a-z0-9.]{3,30}$/.test(v) && !F.state.user.vpas.includes(v + "@fortress") && !taken;
    return {
      cls: "full",
      html: `${appbar(t("set.upiIds"))}
        <div class="list">${F.state.user.vpas.map((x) => `<div class="li"><span class="lead">${I("at", 19)}</span><div class="grow"><div class="t ellipsis">${esc(x)}</div><div class="s">${x === F.state.user.vpa ? "Primary · shown to people you pay" : "Also receives money"}</div></div>
          ${x === F.state.user.vpa ? '<span class="pill gold">Primary</span>' : `<button class="link" data-a="setPrimaryVpa" data-v="${esc(x)}">Make primary</button><button class="icon-btn" style="width:36px;height:36px" data-a="deleteVpa" data-v="${esc(x)}" aria-label="Delete ${esc(x)}">${I("trash", 16)}</button>`}</div>`).join("")}</div>
        <div class="card stack"><div class="h2">Create a new UPI ID</div>
          <div class="search-box"><input placeholder="yourname" value="${esc(v)}" data-in="newVpa" aria-label="New UPI ID" autocapitalize="off" spellcheck="false" maxlength="30"><b class="dim">@fortress</b></div>
          <div class="hint ${v ? (ok ? "ok" : "bad") : ""}" id="nvHint">${v ? (ok ? "Available" : taken || F.state.user.vpas.includes(v + "@fortress") ? "Already taken" : "3–30 letters, numbers or dots") : "Letters, numbers and dots"}</div>
          <button class="btn gold" id="nvGo" data-a="createVpa" ${ok ? "" : "disabled"}>Create UPI ID</button></div>`,
    };
  };

  F.screens.limits = () => {
    const L = F.state.limits, used = Math.round((L.usedToday / L.dailyUpi) * 100);
    const personal = F.state.personalLimit || 0;
    return {
      cls: "full",
      html: `${appbar(t("set.limits"))}
        <div class="card stack"><div class="eyebrow">Today's UPI usage</div>
          <div class="row"><div class="grow" style="font:700 24px var(--display)">${F.inr(L.usedToday)}</div><span class="small muted">of ${F.inrShort(L.dailyUpi)}</span></div>
          <div style="height:10px;border-radius:5px;background:var(--bg-2);overflow:hidden"><i style="display:block;height:100%;width:${used}%;background:var(--gold-grad)"></i></div>
          <div class="small muted">${F.inrShort(L.dailyUpi - L.usedToday)} left · ${L.countToday} of ${L.dailyCount} payments · resets at midnight</div></div>
        <div class="card"><dl class="kv">
          <dt>Per UPI payment</dt><dd>${F.inrShort(L.perTxnUpi)}</dd><dt>Daily UPI limit</dt><dd>${F.inrShort(L.dailyUpi)}</dd><dt>Payments per day</dt><dd>${L.dailyCount}</dd>
          <dt>Offline per payment</dt><dd>${F.inrShort(L.offlinePerTxn)}</dd><dt>Offline vault maximum</dt><dd>${F.inrShort(L.offlineWallet)}</dd>
          <dt>First 24 h on a new phone</dt><dd>₹5,000</dd></dl>
          <div class="small dim" style="margin-top:12px">Set by NPCI and your bank. Fortress syncs them from its server, so changes apply without an app update.</div></div>
        <div class="card stack"><div class="h2">Your own daily limit</div><div class="small muted">Add a lower limit for extra safety. Only you can raise it again, after confirming with your fingerprint.</div>
          <div class="seg" role="group" aria-label="Personal daily limit">${[[0, "Bank limit"], [1000000, "₹10k"], [2500000, "₹25k"], [5000000, "₹50k"]].map(([v, l]) => `<button data-a="personalLimit" data-v="${v}" aria-pressed="${personal === v}">${l}</button>`).join("")}</div></div>`,
    };
  };

  F.screens.security = () => {
    const s = F.state;
    return {
      cls: "full",
      html: `${appbar(t("set.security"))}
        <div class="profile-card" style="align-items:flex-start"><img src="fortress-mark.png" alt="" style="width:46px"><div class="grow stack" style="gap:6px">
          <div style="font:700 16px var(--display)">Fortress Shield · all protections on</div>
          ${["Bound to this phone and SIM", "Keys in hardware (StrongBox)", "Play Integrity: strong", "No root, hooking or screen-reader abuse"].map((x) => `<div class="row small" style="gap:8px;color:#B9F5DD">${I("check", 14, 3)}<span style="color:#FFFFFF">${x}</span></div>`).join("")}</div></div>
        <div class="list">
          ${sw("appLock", "App lock", "Ask for fingerprint or phone PIN when Fortress opens", "lock")}
          ${sw("biometric", "Unlock with fingerprint", "Uses your phone's secure hardware", "finger")}
          ${sw("spamShield", "Fraud & spam shield", "Warn about reported UPI IDs and suspicious requests", "shield")}
          ${sw("payeeWarnings", "New payee warnings", "Extra check on the first payment to someone", "alert")}
        </div>
        <div class="card stack"><div class="h2">Auto-lock</div><div class="seg" role="group" aria-label="Auto-lock">${["Immediately", "1 minute", "5 minutes"].map((v) => `<button data-a="setAutoLock" data-v="${v}" aria-pressed="${s.autoLock === v}">${v}</button>`).join("")}</div></div>
        <div class="list">
          ${row("key", "Change UPI PIN", "For any linked bank account", "go", "accounts")}
          ${row("block", "Blocked people", s.blocked.length ? `${s.blocked.length} blocked` : "No one blocked", "go", "blocked")}
          ${row("mobile", "This device", "Bound 12 Sep 2026 · only device with access", "toast", "Only one phone can use your Fortress account at a time")}
          ${row("lock", "Lock Fortress now", "", "lockNow")}
        </div>
        <div class="card flat small" style="line-height:1.55;background:var(--warn-bg);color:var(--warn);border:0"><b>Lost your phone?</b> Call your bank to block UPI on your accounts. Fortress can't be used on another phone without your SIM and a fresh device check.</div>`,
    };
  };

  F.screens.blocked = () => ({
    cls: "full",
    html: `${appbar("Blocked people")}
      ${F.state.blocked.length ? `<div class="list">${F.state.blocked.map((b) => `<div class="li">${F.avatar(b, "sm")}<div class="grow t ellipsis">${esc(F.contact(b)?.name || b)}</div><button class="link" data-a="unblock" data-v="${esc(b)}">Unblock</button></div>`).join("")}</div>`
        : `<div class="card center muted">No one is blocked. Blocked people and UPI IDs can't send you requests.</div>`}`,
  });

  F.screens.privacy = () => ({
    cls: "full",
    html: `${appbar(t("set.privacy"))}
      <div class="list">
        ${sw("hideBalance", "Hide balances", "Show ₹ •••• until you tap to reveal", "eyeoff")}
        ${sw("contactSync", "Find contacts on UPI", "Matches hashed numbers. Contacts are never uploaded.", "contacts")}
        ${sw("notif.offers", "Personalised offers", "Off by default. We never sell your data.", "gift")}
      </div>
      <div class="card flat row small" style="align-items:flex-start;line-height:1.55">${I("globe", 18)}<span>All payment data is stored only in India, as the RBI requires. Fortress shares data with your bank and NPCI only to complete payments.</span></div>
      <div class="list">
        ${row("download", "Get a copy of my data", "Your profile and payments, as JSON", "downloadData")}
        ${row("doc", "Privacy policy", "How we collect, use and protect data", "toast", "The privacy policy opens in the published app")}
        ${row("trash", "Delete my Fortress account", "Deregisters your UPI IDs and erases app data", "deleteAccount")}
      </div>`,
  });

  F.screens.notifSettings = () => ({
    cls: "full",
    html: `${appbar(t("set.notifications"))}
      <div class="list">
        ${sw("notif.payments", "Payments", "Money sent and received", "swap")}
        ${sw("notif.requests", "Payment requests", "When someone asks you for money", "request")}
        ${sw("notif.bills", "Bill reminders", "3 days before a saved bill is due", "bolt")}
        ${sw("notif.offline", "Offline settlements", "When offline payments settle", "vault")}
        ${sw("notif.security", "Security alerts", "Always on, for your protection", "shield", true)}
        ${sw("notif.offers", "Offers", "Cashback and rewards", "gift")}
      </div>`,
  });

  F.screens.appearance = () => {
    const cur = F.state.theme;
    const sw2 = (id, bg) => `<button data-a="setTheme" data-v="${id}" aria-pressed="${cur === id}"><span class="sw" style="background:${bg}"></span>${t("theme." + id)}</button>`;
    return {
      cls: "full",
      html: `${appbar(t("set.appearance"))}
        <div class="theme-swatch">
          ${sw2("pearl", "linear-gradient(180deg,#0E1E4A 0 38%,#F3F4F8 38%)")}
          ${sw2("midnight", "linear-gradient(180deg,#132a62 0 38%,#070E22 38%)")}
          ${sw2("system", "linear-gradient(90deg,#F3F4F8 0 50%,#070E22 50%)")}
        </div>
        <div class="card flat small muted" style="line-height:1.55">Pearl is bright and calm for daytime. Midnight is a deep navy for night use. Both are tuned for contrast and readability, and the gold accents stay the same.</div>
        <div class="list">${sw("hideBalance", "Hide balances", "Applies to every screen", "eyeoff")}</div>`,
    };
  };

  F.screens.language = () => ({
    cls: "full",
    html: `${appbar(t("set.language"))}
      <div class="list" role="radiogroup">${[["en", "English", "English"], ["hi", "हिन्दी", "Hindi"]].map(([id, n, e]) => `<button class="li" role="radio" aria-checked="${F.state.lang === id}" data-a="setLang" data-v="${id}">
        <span class="lead" style="${F.state.lang === id ? "background:var(--gold-grad);color:var(--on-gold)" : ""}">${F.state.lang === id ? I("check", 18, 2.6) : I("globe", 18)}</span><div class="grow"><div class="t">${n}</div><div class="s">${e}</div></div></button>`).join("")}
        ${["বাংলা", "தமிழ்", "తెలుగు", "मराठी"].map((n) => `<div class="li" aria-disabled="true"><span class="lead">${I("globe", 18)}</span><div class="grow"><div class="t dim">${n}</div><div class="s">Coming soon</div></div></div>`).join("")}</div>`,
  });

  const FAQ = [
    ["Money was debited but the payee didn't get it", "Most failed payments reverse automatically within 1 working day. If not, open the payment in History and tap “Report a problem”. The complaint goes to your bank through NPCI."],
    ["How do I change or reset my UPI PIN?", "Profile → Bank accounts → Change PIN. If you forgot it, tap “Forgot UPI PIN?” and verify with your debit card on NPCI's secure screen."],
    ["Why is offline limited?", "Without internet no bank can confirm your balance, so the RBI caps offline payments. Fortress applies the current limits automatically."],
    ["Someone asked me to enter my PIN to receive money", "That's fraud. You never need your UPI PIN to receive money. Decline, then use Block & report."],
    ["What are the daily limits?", "Usually ₹1,00,000 a day per bank account for UPI payments. Some categories allow more. See Profile → Payment limits."],
    ["I lost my phone", "Call your bank to block UPI. Your Fortress account works only on your bound phone and SIM."],
  ];
  F.screens.help = (p) => {
    const q = (p.q || "").toLowerCase(), list = FAQ.filter(([a, b]) => (a + b).toLowerCase().includes(q));
    const cs = F.state.complaints;
    return {
      cls: "full",
      html: `${appbar(t("set.help"))}
        <div class="search-box">${I("search", 18)}<input type="search" placeholder="Search help" value="${esc(p.q || "")}" data-in="helpSearch" aria-label="Search help"></div>
        <div class="grid4" style="grid-template-columns:repeat(3,minmax(0,1fr))">
          <button class="act" data-a="tab" data-v="history"><span class="tile">${I("alert", 22)}</span>Payment issue</button>
          <button class="act" data-a="supportChat"><span class="tile">${I("chat", 22)}</span>Chat with us</button>
          <button class="act" data-a="go" data-v="accounts"><span class="tile">${I("key", 22)}</span>UPI PIN help</button></div>
        ${cs.length ? `<div class="eyebrow group-label">Your complaints</div><div class="list">${cs.map((c) => `<div class="li"><span class="lead">${I("doc", 18)}</span><div class="grow"><div class="t">${esc(c.reason)}</div><div class="s">${esc(c.id)} · ${F.inr(c.amount)} · ${esc(c.name)}</div></div><span class="pill warn">${c.status}</span></div>`).join("")}</div>` : ""}
        <div class="eyebrow group-label">Common questions</div>
        <div class="list faq" id="faqOut">${list.map(([a, b]) => `<details><summary>${a}${I("down", 16)}</summary><p>${b}</p></details>`).join("") || '<div class="li muted">No matching answers. Try “Chat with us”.</div>'}</div>
        <div class="card stack small" style="line-height:1.55"><div class="h2">Grievance redressal</div>
          <div class="muted">1. Fortress support, in the app (reply within 24 hours)<br>2. Grievance Officer (name and contact published on our website at launch)<br>3. If unresolved after 30 days: RBI Integrated Ombudsman at cms.rbi.org.in</div></div>`,
    };
  };

  F.screens.about = () => ({
    cls: "full",
    html: `${appbar(t("set.about"))}
      <div class="card center stack" style="justify-items:center"><img src="fortress-wordmark.png" alt="Fortress" style="width:70%;max-width:240px"><div class="small muted">Version 1.0.0 (preview build)</div></div>
      <div class="card flat small muted" style="line-height:1.6">Fortress is a UPI app (Third-Party Application Provider). Payments are processed by a sponsor PSP bank over NPCI's UPI network. The partner bank's name and licences will be listed here at launch.</div>
      <div class="list">
        ${row("doc", "Terms of service", "", "toast", "Terms open in the published app")}
        ${row("eyeoff", "Privacy policy", "", "toast", "The privacy policy opens in the published app")}
        ${row("help", "Grievance redressal", "", "go", "help")}
        ${row("chip", "Open-source licences", "", "toast", "Licences open in the published app")}
      </div>
      <div class="center small dim">Made in India 🇮🇳</div>`,
  });

  /* ══ Onboarding, lock, splash ════════════════════════════════ */
  const OB_BANKS = ["Fortress Bank", "Harbor Co-operative Bank", "Nova Small Finance Bank", "Sunrise Bank"];
  F.screens.onboarding = (p) => {
    p.step = p.step || 0;
    const dots = `<div class="dots-nav">${[0, 1, 2, 3, 4].map((i) => `<i class="${Math.min(p.step, 4) === i ? "on" : ""}"></i>`).join("")}</div>`;
    const shell = (body, cta, a, extra = "") => ({
      cls: "full", hero: p.step === 0,
      html: `${body}<div style="flex:1"></div>${dots}${extra}${cta ? `<button class="btn gold" data-a="${a}" ${p.busy ? "disabled" : ""}>${cta}</button>` : ""}`,
    });
    if (p.step === 0) {
      return { cls: "full flush", hero: true, html: `<div class="full-center lock-bg" style="align-content:center;gap:18px">
        <img class="mark-xl shimmer" src="fortress-mark.png" alt="Fortress"><img src="fortress-wordmark.png" alt="" style="width:62%;max-width:230px;margin:0 auto">
        <div class="muted" style="line-height:1.6">Bank-grade UPI that works even without internet.</div>
        <div style="display:grid;gap:10px;width:100%;align-self:end"><button class="btn gold" data-a="obNext">Get started</button><div class="small muted">By continuing you agree to the Terms and Privacy policy</div></div></div>` };
    }
    if (p.step === 1) {
      return shell(`<div class="h1" style="margin-top:10px">Choose your language</div><div class="muted">You can change this any time in settings.</div>
        <div class="list" role="radiogroup">${[["en", "English"], ["hi", "हिन्दी"]].map(([id, n]) => `<button class="li" role="radio" aria-checked="${F.state.lang === id}" data-a="obLang" data-v="${id}"><span class="lead" style="${F.state.lang === id ? "background:var(--gold-grad);color:var(--on-gold)" : ""}">${I(F.state.lang === id ? "check" : "globe", 18)}</span><div class="grow t">${n}</div></button>`).join("")}</div>`, t("common.continue"), "obNext");
    }
    if (p.step === 2) {
      return shell(`<div class="h1" style="margin-top:10px">Verify your mobile number</div><div class="muted" style="line-height:1.55">Fortress sends one encrypted SMS from your phone to confirm the SIM is really yours. This is how UPI keeps your account tied to your phone.</div>
        <div class="list">${[["sim", "SIM 1 · Nova Mobile", "+91 98765 43210"], ["sim", "SIM 2 · BharatTel", "+91 99887 76655"]].map(([ic, a, b], i) => `<button class="li" role="radio" aria-checked="${(p.sim || 0) === i}" data-a="obSim" data-v="${i}"><span class="lead" style="${(p.sim || 0) === i ? "background:var(--gold-grad);color:var(--on-gold)" : ""}">${I(ic, 18)}</span><div class="grow"><div class="t">${a}</div><div class="s">${b}</div></div></button>`).join("")}</div>
        ${p.busy ? `<div class="card stack">${stepList(["Sending encrypted SMS", "Bank's server received it", "Device keys created in StrongBox"], p.sub || 0)}</div>` : ""}
        <div class="card flat small muted" style="line-height:1.55">${I("info", 16)} Standard SMS charges may apply. We never read your messages.</div>`, p.busy ? "Verifying…" : "Verify by SMS", "obVerify");
    }
    if (p.step === 3) {
      return shell(`<div class="h1" style="margin-top:10px">Link your bank account</div><div class="muted">We'll find accounts linked to your number.</div>
        <div class="list">${OB_BANKS.map((b) => `<button class="li" data-a="obBank" data-v="${esc(b)}">${F.avatar(b, "sm", { biz: true, glyph: "bank" })}<div class="grow t">${esc(b)}</div>${p.bank === b && p.busy ? '<span class="spinner" style="width:22px;height:22px;margin:0;border-width:2px"></span>' : `<span class="trail">${I("chev", 18)}</span>`}</button>`).join("")}</div>`, "", "");
    }
    if (p.step === 4) {
      return shell(`<div class="h1" style="margin-top:10px">Account found</div>
        <div class="card row">${F.avatar(p.bank || OB_BANKS[0], "", { biz: true, glyph: "bank" })}<div class="grow"><div style="font:800 15px var(--text)">${esc(p.bank || OB_BANKS[0])}</div><div class="small muted">Savings •• 4402 · ${esc(F.state.user.name)}</div></div><span class="pill ok">Found</span></div>
        <div class="list">${sw("appLock", "Protect Fortress with app lock", "Fingerprint or phone PIN on open", "lock")}${sw("biometric", "Use fingerprint", "", "finger")}</div>
        <div class="card flat small muted" style="line-height:1.55">Next, set your UPI PIN on NPCI's secure screen. Have your debit card ready (last 6 digits and expiry).</div>`, "Set UPI PIN & finish", "obFinish");
    }
    return shell(`<div class="success-ring" id="ring" style="margin-top:40px">${I("check", 50, 2.6)}</div><div class="center stack"><div class="h1">You're all set</div><div class="muted">Your UPI ID is <b style="color:var(--ink)">${esc(F.state.user.vpa)}</b></div></div>`, "Start using Fortress", "obDone");
  };

  F.screens.lock = (p) => {
    const d = new Date();
    return {
      cls: "full flush", hero: true,
      html: `<div class="full-center lock-bg" style="align-content:space-between">
        <div class="stack" style="gap:6px"><div class="big-time">${d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false })}</div><div class="muted">${d.toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long" })}</div></div>
        <div class="stack" style="justify-items:center;gap:14px"><img src="fortress-mark.png" alt="" style="width:76px"><div style="font:700 18px var(--display)">Fortress is locked</div><div class="muted small">Unlock to see balances and pay</div></div>
        <div class="stack" style="justify-items:center;gap:14px"><button class="fp ${p.ok ? "ok" : ""}" data-a="unlock" aria-label="Unlock with fingerprint">${I(p.ok ? "check" : "finger", 40, 1.6)}</button><button class="link" style="color:#F0CF7E" data-a="unlock">Use phone PIN instead</button></div></div>`,
    };
  };

  F.screens.splash = () => ({
    cls: "full flush", hero: true,
    html: `<div class="full-center lock-bg" style="align-content:center;gap:20px"><img class="mark-xl shimmer" src="fortress-mark.png" alt="Fortress"><img src="fortress-wordmark.png" alt="" style="width:58%;max-width:220px;margin:0 auto"></div>`,
    mount: () => F.later(1200, () => (F.state.appLock ? F.replace("lock", {}) : F.tabTo("home"))),
  });
})();

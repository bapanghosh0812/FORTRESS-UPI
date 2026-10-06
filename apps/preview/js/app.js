/* Fortress preview — actions, payment pipeline, offline glue, settings, control rail, boot. */
(() => {
  "use strict";
  const F = window.F, t = (k, v) => F.t(k, v), I = F.icon, esc = F.esc;
  const P = () => F.top().params;
  const spin = `<span class="spinner" style="width:20px;height:20px;margin:0;border-width:2px"></span>`;

  /* ── Theme & language ─────────────────────────────────────── */
  const darkMq = matchMedia("(prefers-color-scheme: dark)");
  F.applyTheme = () => {
    const th = F.state.theme === "system" ? (darkMq.matches ? "midnight" : "pearl") : F.state.theme;
    F.$("#device").dataset.appTheme = th;
  };
  if (darkMq.addEventListener) darkMq.addEventListener("change", () => F.state.theme === "system" && F.applyTheme());
  F.applyLang = () => { document.documentElement.lang = F.state.lang === "hi" ? "hi" : "en"; };
  F.syncRail = () => {
    const s = F.state;
    F.$$("[data-r]").forEach((b) => {
      const r = b.dataset.r, v = b.dataset.v;
      if (r === "net") b.setAttribute("aria-pressed", String((v === "on") === s.online));
      if (r === "theme") b.setAttribute("aria-pressed", String(v === s.theme));
      if (r === "lang") b.setAttribute("aria-pressed", String(v === s.lang));
    });
  };

  /* ── Shared sheets ────────────────────────────────────────── */
  F.alert = (icon, title, body, actions, tone = "warn") => F.sheet(`<div class="center stack" style="justify-items:center">
      <span class="av lg" style="background:var(--${tone}-bg);color:var(--${tone})">${I(icon, 28)}</span>
      <div class="h2">${title}</div><p class="muted" style="margin:0;line-height:1.55">${body}</p></div>
      ${actions || `<button class="btn gold" data-a="sheetClose">OK</button>`}`);
  const offlineAlert = (what = "This needs internet") => F.alert("wifioff", what,
    `You're offline right now. You can still pay up to ${F.inrShort(F.state.limits.offlinePerTxn)} from your offline vault.`,
    `<div class="btn-row"><button class="btn ghost" data-a="sheetClose">${t("common.cancel")}</button><button class="btn gold" data-a="goOfflinePay" data-v="0">Pay offline</button></div>`);

  F.bio = (text, cb) => F.sheet(`<div class="center stack" style="justify-items:center;gap:14px">
      <div class="h2">Confirm it's you</div>
      <button class="fp" style="color:var(--gold-ink);border-color:color-mix(in srgb,var(--gold) 45%,transparent);background:var(--tile)" data-a="bioTouch" aria-label="Touch the fingerprint sensor">${I("finger", 40, 1.6)}</button>
      <div class="small muted">${text}</div></div>
      <button class="btn ghost" data-a="sheetClose">${t("common.cancel")}</button>`, cb);

  /* Simulated NPCI Common Library screen. The real app hands this to NPCI's CL;
     the preview never asks for, reads or stores a PIN. */
  F.authorize = ({ title, amount, bank, label, steps }, done) => {
    F._auth = { done, steps: steps || ["Encrypted with NPCI's public key", "Approved by your bank", "Confirmed on UPI"] };
    F.sheetLock = false;
    F.sheet(`<div class="secure-pin" role="group" aria-label="NPCI secure PIN screen, simulated">
        <div class="row" style="justify-content:space-between"><span style="font:800 10.5px var(--text);letter-spacing:.16em;opacity:.75">NPCI · UPI SECURE</span><span class="ellipsis" style="font:700 11.5px var(--text);opacity:.8">${esc(bank || "")}</span></div>
        <div style="font:700 15px var(--display)">${esc(title)}</div>
        ${amount ? `<div style="font:700 30px var(--display)" class="tnum">${F.inr(amount)}</div>` : ""}
        <div style="font:700 12.5px var(--text);opacity:.85">${label || t("pin.title")}</div>
        <div class="dots" id="pinDots">${"<i></i>".repeat(6)}</div>
        <div style="font:600 11.5px/1.5 var(--text);opacity:.72">${t("pin.sub")}</div></div>
      <button class="btn gold" id="pinGo" data-a="pinSim">${I("lock", 18)}${t("pin.simulate")}</button>
      <button class="btn ghost" data-a="sheetClose">${t("common.cancel")}</button>`);
  };

  /* ── Payments ─────────────────────────────────────────────── */
  F.startPay = (payee, o = {}) => {
    const params = { payee, kind: o.kind || "p2p", entry: o.entry || "", note: o.note || "", acct: F.primary().id, fixed: !!o.amount, amount: o.amount || 0, reqId: o.reqId, meta: o.meta };
    if (o.replace) F.replace("amount", params, "fwd"); else F.go("amount", params);
  };

  F.pay = (spec) => {
    const s = F.state, L = s.limits, amt = spec.amount, acct = F.account(spec.acctId);
    if (!s.online) return offlineAlert("You're offline");
    if (amt < 100) return F.toast("The minimum UPI payment is ₹1", "info");
    if (amt > L.perTxnUpi) return F.alert("gauge", "Above the per-payment limit", `UPI allows up to ${F.inrShort(L.perTxnUpi)} in a single payment. Split it, or ask your bank about higher-limit categories.`);
    const left = L.dailyUpi - L.usedToday;
    if (amt > left) return F.alert("gauge", "Daily UPI limit reached", `You can send ${F.inr(left)} more today. The ${F.inrShort(L.dailyUpi)} limit resets at midnight.`);
    if (s.personalLimit && L.usedToday + amt > s.personalLimit) return F.alert("lock", "Above your own daily limit", `You set a personal limit of ${F.inrShort(s.personalLimit)}. You can change it in Payment limits.`, `<div class="btn-row"><button class="btn ghost" data-a="sheetClose">OK</button><button class="btn gold" data-a="go" data-v="limits">Change limit</button></div>`);
    if (L.countToday >= L.dailyCount) return F.alert("gauge", "Too many payments today", `UPI allows ${L.dailyCount} payments a day from one account. Try again after midnight.`);
    const to = spec.kind === "self" ? F.account(spec.toAcctId) : null;
    const first = s.payeeWarnings && spec.kind === "p2p" && !s.txns.some((x) => x.vpa === spec.payee.vpa) && amt >= 200000;
    F.sheet(`<div class="h2">${t("pay.review")}</div>
      <div class="row">${F.avatar(spec.payee.name, "", { biz: spec.payee.biz, glyph: spec.payee.glyph, verified: spec.payee.verified })}
        <div class="grow"><div style="font:800 15px var(--text)" class="ellipsis">${esc(spec.payee.name)}</div><div class="small muted ellipsis">${esc(to ? "Self transfer · instant" : spec.payee.sub || spec.payee.vpa)}</div></div></div>
      <div class="amount center">${F.inr(amt)}</div>
      <div class="card flat"><dl class="kv"><dt>${t("pay.from")}</dt><dd>${esc(acct.bank)} •• ${acct.masked}</dd>
        ${to ? `<dt>To</dt><dd>${esc(to.bank)} •• ${to.masked}</dd>` : ""}${spec.note ? `<dt>${t("pay.note")}</dt><dd>${esc(spec.note)}</dd>` : ""}<dt>Charges</dt><dd>₹0.00</dd></dl></div>
      ${first ? `<div class="card flat row small" style="align-items:flex-start;line-height:1.5;background:var(--warn-bg);color:var(--warn);border:0">${I("alert", 16)}<span>First payment to ${esc(spec.payee.name.split(" ")[0])}. The name is verified with their bank. Only pay people you know.</span></div>` : ""}
      <button class="btn gold" data-a="sheetYes">${I("lock", 18)}${t("pay.pay")} ${F.inr(amt)}</button>`,
    () => F.authorize({ title: `${to ? "Transfer to" : "Paying"} ${to ? to.bank : spec.payee.name}`, amount: amt, bank: `${acct.bank} •• ${acct.masked}` }, () => F.commitPay(spec)));
  };

  const FLOW = ["amount", "billPay", "recharge", "self", "offPay", "offReceive"];
  F.showReceipt = (params) => (FLOW.includes(F.top().name) ? F.replace("receipt", params, "fade") : F.go("receipt", params, "fade"));
  const stamp = (ts) => new Date(ts).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

  F.commitPay = (spec) => {
    const s = F.state, L = s.limits, acct = F.account(spec.acctId), amt = spec.amount, now = Date.now();
    const to = spec.kind === "self" ? F.account(spec.toAcctId) : null;
    const tx = { id: F.uid("t"), name: spec.payee.name, vpa: spec.payee.vpa, note: spec.note || "", amount: amt, credit: false, status: "success", kind: spec.kind, at: now, ref: "6" + F.digits(11) };
    if (amt > acct.balance) {
      tx.status = "failed"; tx.reason = "Your bank declined it: not enough balance. No money was debited.";
    } else {
      acct.balance -= amt; L.usedToday += amt; L.countToday += 1;
      if (to) to.balance += amt;
      if (spec.reqId) s.requests = s.requests.filter((r) => r.id !== spec.reqId);
      s.notifications.unshift({ id: F.uid("n"), icon: "check", title: `${F.inr(amt)} paid to ${spec.payee.name}`, body: `From ${acct.bank} •• ${acct.masked} · UPI ref ${tx.ref}`, at: now, read: false });
    }
    s.txns.unshift(tx);
    const bill = spec.kind === "bill" || spec.kind === "recharge";
    const rows = [[bill ? "Biller" : "To", esc(to ? `${to.bank} •• ${to.masked}` : spec.payee.vpa)], [t("pay.from"), `${esc(acct.bank)} •• ${acct.masked}`], ["UPI transaction ID", tx.ref]];
    if (bill && tx.status === "success") rows.push(["BBPS reference", "BB" + F.digits(10)]);
    rows.push(["Date & time", stamp(now)]);
    if (spec.note) rows.push([t("pay.note"), esc(spec.note)]);
    F.showReceipt({ tx, rows, backTo: spec.backTo || "home" });
  };

  F.commitOffline = (p) => {
    const s = F.state, amt = F.entryPaise(p.entry), picked = F.selectNotes(s.vault, amt);
    if (!picked) { F.toast("Not enough notes in your vault", "alert"); return F.back(); }
    const idx = new Set(picked.map((x) => x.index)), used = picked.map((x) => x.denom);
    const change = used.reduce((a, b) => a + b, 0) - amt;
    s.vault = s.vault.filter((_, i) => !idx.has(i));
    const tx = { id: F.uid("t"), name: p.to.name, vpa: p.to.vpa, note: `Offline · ${p.mode === "tap" ? "Tap & Pay" : "Nearby"}`, amount: amt, credit: false, status: s.online ? "settled" : "settling", kind: "offline", at: Date.now(), ref: "OFF-" + Math.random().toString(16).slice(2, 8).toUpperCase(), change };
    if (s.online && change) F.primary().balance += change;
    s.txns.unshift(tx);
    s.notifications.unshift({ id: F.uid("n"), icon: "vault", title: `${F.inr(amt)} paid offline to ${p.to.name}`, body: s.online ? "Settled with your bank" : "Settles automatically when you're back online", at: Date.now(), read: false });
    F.showReceipt({
      tx, backTo: "offline", title: "Paid offline",
      rows: [["Offline payment ID", tx.ref], ["Notes used", used.map((d) => F.inrShort(d)).join(" + ")],
        ["Change", change ? `${F.inr(change)} ${s.online ? "returned to your bank" : "returns at settlement"}` : "None"],
        ["Settlement", s.online ? "Settled just now" : "When either phone is online"], ["Security", "Signed in StrongBox · single-use keys"]],
    });
  };

  F.commitReceive = (from, amt) => {
    const s = F.state, a = F.primary();
    const tx = { id: F.uid("t"), name: from.name, vpa: from.vpa, note: "Offline · received nearby", amount: amt, credit: true, status: s.online ? "settled" : "settling", kind: "offline", at: Date.now(), ref: "OFF-" + Math.random().toString(16).slice(2, 8).toUpperCase() };
    if (s.online) a.balance += amt;
    s.txns.unshift(tx);
    F.showReceipt({
      tx, backTo: "offline", title: "Received offline",
      rows: [["Offline payment ID", tx.ref], ["Credited to", s.online ? `${esc(a.bank)} •• ${a.masked}` : "Your bank, when either phone is online"], ["Security", "Every note verified against Fortress's signature"], ["Date & time", stamp(tx.at)]],
    });
  };

  F.setOnline = (on) => {
    const s = F.state;
    if (s.online === on) return;
    s.online = on;
    if (on) {
      const pend = s.txns.filter((x) => x.status === "settling");
      if (pend.length) {
        let back = 0;
        pend.forEach((x) => { x.status = "settled"; back += x.credit ? x.amount : x.change || 0; });
        F.primary().balance += back;
        s.notifications.unshift({ id: F.uid("n"), icon: "vault", title: `${pend.length} offline payment${pend.length > 1 ? "s" : ""} settled`, body: back ? `${F.inr(back)} credited to your bank` : "Everything is reconciled with your bank", at: Date.now(), read: false });
        F.toast(`${pend.length} offline payment${pend.length > 1 ? "s" : ""} settled${back ? ` · ${F.inr(back)} to your bank` : ""}`, "vault");
      } else F.toast("Back online", "wifi");
    } else F.toast("No internet. Your offline vault still works.", "wifioff");
    F.syncRail(); F.refresh();
  };

  /* ── Exports. The preview copies them; the Android app saves files and opens the share sheet. ── */
  const csvCell = (c) => { let v = String(c ?? ""); if (/^[=+\-@]/.test(v)) v = "'" + v; return `"${v.replace(/"/g, '""')}"`; };
  const shareText = (text, label) => F.copy(text, label);

  /* ── Support chat ─────────────────────────────────────────── */
  const CHAT = {
    "Payment failed": "If money left your account, it comes back automatically, usually within 1 working day. Open the payment in History and tap “Report a problem” to track it.",
    "Money not received": "Ask the payer for the UPI transaction ID, then check History. If it shows success on their side but you don't see it, raise a complaint from that payment.",
    "Change UPI PIN": "Go to Profile → Bank accounts → Change PIN. Forgot it? Tap “Forgot UPI PIN?” and verify with your debit card.",
    "Report fraud": "Don't share OTPs or your PIN with anyone. Use Block & report on the request or contact, then call your bank to block UPI if you've lost money.",
  };
  const chatHtml = (log) => `<div class="h2">Fortress support</div><div class="thread">${log.map(([me, m]) => `<div class="bubble ${me ? "me" : ""}" style="max-width:86%"><div style="font:600 13.5px/1.5 var(--text)">${esc(m)}</div></div>`).join("")}</div>
    <div class="chips" style="flex-wrap:wrap">${Object.keys(CHAT).map((k) => `<button class="chip" data-a="chatPick" data-v="${k}">${k}</button>`).join("")}</div>
    <button class="btn ghost" data-a="sheetClose">Close chat</button>`;

  /* ── Actions ──────────────────────────────────────────────── */
  const find = (arr, id) => arr.find((x) => x.id === id);
  const A = {
    back: () => F.back(),
    tab: (v) => F.tabTo(v),
    go: (v, el) => { const id = el && el.dataset.p; F.go(v, id !== undefined ? { id } : {}); },
    toast: (v) => F.toast(esc(v), "info"),
    sheetClose: () => { if (!F.sheetLock) F.closeSheet(); },
    sheetYes: () => { const cb = F.sheetCb; F.closeSheet(); if (cb) cb(); },
    key: (v, el, p) => { p.entry = F.applyKey(p.entry || "", v); F.refresh(); },
    quickAmt: (v, el, p) => { p.entry = String(v); F.refresh(); },
    copyVpa: () => F.copy(F.state.user.vpa, "UPI ID"),
    copyRef: (v) => F.copy(v, "transaction ID"),
    copyText: (v) => { F.copy(v); F.closeSheet(); },

    /* Secure screens */
    pinSim: () => {
      const dots = F.$$("#pinDots i"), go = F.$("#pinGo");
      if (!dots.length || go.disabled) return;
      go.disabled = true; F.sheetLock = true;
      let i = 0;
      const processing = () => {
        const { steps, done } = F._auth;
        let k = 0;
        const draw = () => F.sheetBody(`<div class="spinner"></div><div class="h2 center">Processing securely</div><div class="card flat">${F.stepList(steps, k)}</div><div class="center small muted">Please don't close the app</div>`);
        const tick = () => { k += 1; draw(); if (k < steps.length) F.later(420, tick); else F.later(280, () => { F.sheetLock = false; F.closeSheet(true); done(); }); };
        draw(); F.later(420, tick);
      };
      const fill = () => { dots[i].classList.add("on"); i += 1; if (i < dots.length) F.later(80, fill); else F.later(240, processing); };
      fill();
    },
    pinForgot: () => F.go("accounts"),
    bioTouch: (v, el) => {
      el.classList.add("ok"); el.innerHTML = I("check", 40, 1.8);
      F.later(360, () => { const cb = F.sheetCb; F.closeSheet(); if (cb) cb(); });
    },

    /* Amount screen */
    reviewPay: (v, el, p) => {
      const amt = p.fixed ? p.amount : F.entryPaise(p.entry);
      if (amt) F.pay({ payee: p.payee, amount: amt, kind: p.kind, note: p.note, acctId: p.acct, reqId: p.reqId });
    },
    editNote: (v, el, p) => F.sheet(`<div class="h2">${t("pay.addNote")}</div>
      <input id="noteIn" class="input" maxlength="50" value="${esc(p.note || "")}" placeholder="What's this for?" aria-label="${t("pay.note")}" autocomplete="off">
      <div class="chips" style="flex-wrap:wrap">${["Rent", "Food", "Groceries", "Travel", "Gift", "Bills"].map((c) => `<button class="chip" data-a="noteChip" data-v="${c}">${c}</button>`).join("")}</div>
      <button class="btn gold" data-a="saveNote">${t("common.save")}</button>`),
    noteChip: (v) => { F.$("#noteIn").value = v; },
    saveNote: (v, el, p) => { p.note = F.$("#noteIn").value.trim().slice(0, 50); F.closeSheet(); F.refresh(); },
    pickAccount: (v, el, p) => F.sheet(`<div class="h2">${t("pay.from")}</div><div class="list" role="radiogroup">${F.state.accounts.map((a) => `<button class="li" role="radio" aria-checked="${p.acct === a.id}" data-a="setPayAcct" data-v="${a.id}">
        ${F.avatar(a.bank, "sm", { biz: true, glyph: "bank" })}<div class="grow"><div class="t">${esc(a.bank)}</div><div class="s">${a.type} •• ${a.masked}${a.primary ? " · Primary" : ""}</div></div>${p.acct === a.id ? `<span style="color:var(--ok)">${I("check", 20, 2.6)}</span>` : ""}</button>`).join("")}</div>`),
    setPayAcct: (v, el, p) => { p.acct = v; F.closeSheet(); F.refresh(); },
    receiptDone: (v) => F.tabTo(v || "home"),
    shareTx: () => {
      const top = F.top(), x = top.name === "receipt" ? top.params.tx : find(F.state.txns, top.params.id);
      if (x) shareText(`${x.credit ? "Received" : "Paid"} ${F.inr(x.amount)} ${x.credit ? "from" : "to"} ${x.name} on Fortress UPI · Ref ${x.ref} · ${stamp(x.at)}`, "receipt details");
    },

    /* People */
    payContact: (v) => { const c = F.contact(v); F.startPay({ name: c.name, vpa: c.vpa, verified: true, bankName: c.vpa.includes("harbor") ? "Harbor Co-operative Bank" : "Fortress Bank" }); },
    requestFrom: (v) => F.go("request", { cid: v }),
    contactMenu: (v) => {
      const c = F.contact(v), first = esc(c.name.split(" ")[0]);
      F.sheet(`<div class="row">${F.avatar(c.name)}<div class="grow"><div class="h2">${esc(c.name)}</div><div class="small muted ellipsis">${esc(c.phone)} · ${esc(c.vpa)}</div></div></div>
        <div class="list">${F.settingRow("copy", "Copy UPI ID", "", "copyText", c.vpa, "")}${F.settingRow("split", "Split a bill", "", "go", "split", "")}
        ${F.settingRow("block", `Block ${first}`, "They won't be able to send you requests", "blockContact", c.id, "")}${F.settingRow("alert", "Report as fraud", "", "reportContact", c.id, "")}</div>`);
    },
    blockContact: (v) => {
      const c = F.contact(v);
      F.confirm({ title: `Block ${esc(c.name)}?`, body: "They won't be able to send you payment requests. You can unblock them in Security settings.", yes: "Block", danger: true },
        () => { F.state.blocked.push(v); F.toast(`${esc(c.name)} blocked`, "block"); F.tabTo("home"); });
    },
    reportContact: () => { F.closeSheet(); F.toast("Reported. Our fraud team will review it.", "shield"); },
    phoneContinue: (v, el, p) => { const h = F.lookupPhone(p.num); F.startPay({ name: h.name, vpa: h.vpa, verified: true, sub: `+91 ${p.num} · ${h.vpa}` }); },
    vpaVerify: (v, el, p) => {
      if (!F.vpaOk(p.vpa)) return;
      if (!F.state.online) return offlineAlert();
      el.disabled = true; el.innerHTML = `${spin}Checking with the bank…`;
      F.later(750, () => {
        if (F.state.spamShield && /kyc|refund|lottery|prize|reward|claim/i.test(p.vpa)) {
          F.refresh();
          return F.alert("alert", "Reported for fraud", `${esc(p.vpa)} has been reported by many UPI users. Fortress stopped this payment to protect you.`,
            `<div class="btn-row"><button class="btn ghost" data-a="sheetClose">Go back</button><button class="btn danger" data-a="blockVpa" data-v="${esc(p.vpa)}">Block ID</button></div>`, "bad");
        }
        p.verified = true; F.refresh();
      });
    },
    vpaContinue: (v, el, p) => F.startPay({ name: F.lookupVpa(p.vpa).name, vpa: p.vpa, verified: true }),
    blockVpa: (v) => { F.state.blocked.push(v); F.closeSheet(); F.toast("UPI ID blocked and reported", "block"); },
    payVpa: (v) => F.go("payUpi", { vpa: v }),
    payPhoneNum: (v) => F.go("payPhone", { num: v }),
    bankContinue: (v, el, p) => {
      const sub = `A/c •• ${p.acc.slice(-4)} · ${p.ifsc}`;
      F.startPay({ name: p.name.trim(), vpa: sub, sub, verified: true, bankName: F.ifscBank(p.ifsc) || "Bank verified" }, { kind: "bank" });
    },
    selfPick: (v, el, p) => F.sheet(`<div class="h2">${v === "from" ? "Transfer from" : "Transfer to"}</div><div class="list">${F.state.accounts.map((a) => `<button class="li" data-a="setSelf" data-v="${v}|${a.id}">${F.avatar(a.bank, "sm", { biz: true, glyph: "bank" })}<div class="grow"><div class="t">${esc(a.bank)}</div><div class="s">${a.type} •• ${a.masked}</div></div></button>`).join("")}</div>`),
    setSelf: (v, el, p) => {
      const [k, id] = v.split("|"), accts = F.state.accounts, other = k === "from" ? "to" : "from";
      p.from = p.from || accts[0].id; p.to = p.to || (accts[1] || accts[0]).id; p[k] = id;
      if (p[other] === id) p[other] = (accts.find((a) => a.id !== id) || accts[0]).id;
      F.closeSheet(); F.refresh();
    },
    selfPay: (v, el, p) => {
      const accts = F.state.accounts, from = F.account(p.from || accts[0].id), to = F.account(p.to || (accts[1] || accts[0]).id);
      F.pay({ payee: { name: `${to.bank} •• ${to.masked}`, vpa: "self", biz: true, glyph: "bank" }, amount: F.entryPaise(p.entry), kind: "self", note: "Self transfer", acctId: from.id, toAcctId: to.id });
    },

    /* Requests & split */
    sendRequest: (v, el, p) => {
      if (!F.state.online) return offlineAlert();
      const c = F.contact(p.cid), amt = F.entryPaise(p.entry);
      F.state.outgoing.push({ id: F.uid("o"), name: c.name, amount: amt, note: p.note || "", at: Date.now() });
      F.toast(`Requested ${F.inr(amt)} from ${esc(c.name.split(" ")[0])}`, "request");
      F.replace("thread", { id: c.id }, "fade");
    },
    declineReq: (v) => { F.state.requests = F.state.requests.filter((r) => r.id !== v); F.toast("Request declined", "close"); F.refresh(); },
    blockReq: (v) => {
      const r = find(F.state.requests, v);
      F.state.requests = F.state.requests.filter((x) => x.id !== v); F.state.blocked.push(r.vpa);
      F.toast("Blocked and reported to your bank", "shield"); F.refresh();
    },
    payReq: (v) => { const r = find(F.state.requests, v); F.startPay({ name: r.name, vpa: r.vpa, verified: true }, { amount: r.amount, note: r.note, reqId: r.id }); },
    splitToggle: (v, el, p) => { p.sel = (p.sel || []).includes(v) ? p.sel.filter((x) => x !== v) : [...(p.sel || []), v]; F.refresh(); },
    sendSplit: (v, el, p) => {
      if (!F.state.online) return offlineAlert();
      const share = Math.ceil(F.entryPaise(p.entry) / (p.sel.length + 1));
      p.sel.forEach((id) => F.state.outgoing.push({ id: F.uid("o"), name: F.contact(id).name, amount: share, note: "Split bill", at: Date.now() }));
      F.toast(`Sent ${p.sel.length} request${p.sel.length > 1 ? "s" : ""} of ${F.inr(share)}`, "split"); F.back();
    },

    /* QR */
    shareQr: () => shareText(`Pay ${F.state.user.name} on any UPI app: ${F.state.user.vpa}`, "UPI ID"),
    qrAmount: (v, el, p) => {
      if (p.amount) { p.amount = 0; return F.refresh(); }
      F.sheet(`<div class="h2">Add an amount</div><div class="search-box"><b style="font:800 18px var(--display)">₹</b><input id="qrAmtIn" inputmode="decimal" placeholder="0" maxlength="10" aria-label="Amount" autocomplete="off"></div>
        <div class="small muted">The payer sees this amount filled in. They can't change it.</div><button class="btn gold" data-a="setQrAmount">Show QR with amount</button>`);
    },
    setQrAmount: (v, el, p) => {
      const m = (F.$("#qrAmtIn").value || "").replace(/[^\d.]/g, "").match(/^\d{0,7}(\.\d{0,2})?/);
      const amt = F.entryPaise(m ? m[0] : "");
      if (!amt) return F.toast("Enter an amount", "info");
      p.amount = amt; F.closeSheet(); F.refresh();
    },
    torch: (v, el) => {
      const on = el.getAttribute("aria-pressed") !== "true";
      el.setAttribute("aria-pressed", String(on)); el.style.background = on ? "rgba(240,207,126,.4)" : "";
      F.toast(on ? "Torch on" : "Torch off", "bolt");
    },
    scanGallery: () => F.sheet(`<div class="h2">Upload a QR image</div><div class="small muted">Recent images that contain a UPI QR code</div>
      <div class="list">${[["b2", "Screenshot · 2 days ago"], ["b3", "Photo · last week"]].map(([id, w]) => { const b = find(F.state.businesses, id); return `<button class="li" data-a="payBiz" data-v="${id}"><span class="lead">${I("image", 19)}</span><div class="grow"><div class="t">${esc(b.name)}</div><div class="s">${w}</div></div></button>`; }).join("")}</div>`),
    payBiz: (v) => { const b = find(F.state.businesses, v); F.startPay({ name: b.name, vpa: b.vpa, verified: true, biz: true, glyph: b.glyph, merchant: true }, { kind: "p2m", replace: F.top().name === "scan" }); },

    /* Bills */
    bill: (v) => (v === "mobile" ? F.go("recharge", {}) : F.go("billPay", { cat: v })),
    billSaved: (v) => { const [cat, biller, num] = v.split("|"); F.go("billPay", { cat, biller, num }); F.later(250, () => A.fetchBill(null, F.$("#billGo"), P())); },
    pickBiller: (v, el, p) => F.go("billPay", { cat: p.cat, biller: v }),
    fetchBill: (v, el, p) => {
      if (!F.state.online) return offlineAlert();
      if (el) { el.disabled = true; el.innerHTML = `${spin}Fetching from BBPS…`; }
      F.later(900, () => {
        const h = F.hash(p.biller + p.num), now = new Date();
        const raw = /MetroPower/.test(p.biller) ? 261200 : 40000 + (h % 360000);
        p.bill = {
          holder: F.state.user.name.toUpperCase(), amount: raw - (raw % 100),
          due: new Date(Date.now() + (3 + (h % 9)) * 864e5).toLocaleDateString("en-IN", { day: "numeric", month: "short" }),
          period: new Date(now.getFullYear(), now.getMonth() - 1, 1).toLocaleDateString("en-IN", { month: "short", year: "numeric" }),
          no: "BL" + String(h).slice(0, 8),
        };
        F.refresh();
      });
    },
    payBill: (v, el, p) => {
      const cat = F.billCats.find((c) => c.id === p.cat);
      F.pay({ payee: { name: p.biller, vpa: p.biller.toLowerCase().replace(/[^a-z]/g, "").slice(0, 14) + "@bbps", biz: true, glyph: cat.icon, verified: true }, amount: p.bill.amount, kind: "bill", note: `${t("bill." + p.cat)} · ${p.num}`, acctId: F.primary().id });
    },
    planTab: (v, el, p) => { p.tab = v; F.refresh(); },
    pickPlan: (v, el, p) => {
      const [amt] = v.split("|"), num = p.num ?? "9876543210", op = F.operators[F.hash(num) % F.operators.length];
      F.pay({ payee: { name: `${op} Prepaid`, vpa: op.toLowerCase().replace(/\s/g, "") + "@bbps", biz: true, glyph: "mobile", verified: true }, amount: +amt, kind: "recharge", note: `Recharge · ${num.slice(0, 5)} ${num.slice(5)} · ${F.inrShort(+amt)} plan`, acctId: F.primary().id });
    },
    mandateToggle: (v) => {
      const m = find(F.state.mandates, v), pausing = m.status === "active", a = F.primary();
      if (!F.state.online) return offlineAlert();
      F.authorize({ title: `${pausing ? "Pause" : "Resume"} ${m.name}`, bank: `${a.bank} •• ${a.masked}`, steps: ["Encrypted with NPCI's public key", "Mandate updated by your bank"] }, () => {
        m.status = pausing ? "paused" : "active"; if (!pausing && m.next === "—") m.next = "5 Nov";
        F.toast(`${esc(m.name)} ${pausing ? "paused" : "resumed"}`, pausing ? "pause" : "play"); F.refresh();
      });
    },
    mandateRevoke: (v) => {
      const m = find(F.state.mandates, v);
      F.confirm({ title: `Cancel ${esc(m.name)}?`, body: "No more automatic debits will happen. You'd need to set it up again to restart.", yes: "Cancel mandate", danger: true },
        () => { m.status = "revoked"; F.toast("Mandate cancelled", "trash"); F.refresh(); });
    },

    /* Balance */
    checkBalance: (v) => {
      const accts = F.state.accounts;
      if (!v && accts.length > 1) {
        return F.sheet(`<div class="h2">${t("act.balance")}</div><div class="list">${accts.map((a) => `<button class="li" data-a="checkBalance" data-v="${a.id}">${F.avatar(a.bank, "sm", { biz: true, glyph: "bank" })}<div class="grow"><div class="t">${esc(a.bank)}</div><div class="s">${a.type} •• ${a.masked}</div></div>${a.primary ? '<span class="pill gold">Primary</span>' : ""}</button>`).join("")}</div>`);
      }
      const a = F.account(v);
      if (!F.state.online) return offlineAlert("Balance check needs internet");
      F.authorize({ title: t("act.balance"), bank: `${a.bank} •• ${a.masked}`, steps: ["Encrypted with NPCI's public key", "Balance fetched from your bank"] }, () => F.sheet(`<div class="center stack" style="justify-items:center">
          ${F.avatar(a.bank, "lg", { biz: true, glyph: "bank" })}<div class="small muted">${esc(a.bank)} •• ${a.masked}</div>
          <div class="eyebrow">Available balance</div><div class="amount">${F.inr(a.balance)}</div><div class="small dim">As of ${stamp(Date.now())}</div></div>
          <button class="btn gold" data-a="sheetClose">${t("common.done")}</button>`));
    },

    /* History & transaction */
    histFilter: (v, el, p) => { p.filter = v; F.refresh(); },
    statement: () => F.sheet(`<div class="h2">Statement</div>
      <div class="seg" role="group" aria-label="Period">${["This month", "Last 3 months", "This FY"].map((r, i) => `<button data-a="stmtRange" data-v="${r}" aria-pressed="${i === 0}">${r}</button>`).join("")}</div>
      <div class="small muted">Every UPI and offline payment as CSV, ready to paste into Excel or Google Sheets.</div>
      <button class="btn gold" data-a="stmtCopy">${I("copy", 18)}Copy statement (CSV)</button>`),
    stmtRange: (v, el) => F.$$("[data-a=stmtRange]").forEach((b) => b.setAttribute("aria-pressed", String(b === el))),
    stmtCopy: () => {
      const rows = [["Date", "Name", "UPI ID", "Type", "Status", "Amount (INR)", "Reference", "Note"]]
        .concat(F.state.txns.map((x) => [new Date(x.at).toISOString(), x.name, x.vpa, x.credit ? "Credit" : "Debit", x.status, (x.amount / 100).toFixed(2), x.ref, x.note]));
      F.closeSheet(true);
      F.copy(rows.map((r) => r.map(csvCell).join(",")).join(String.fromCharCode(13, 10)), "statement (CSV)");
    },
    payAgain: (v) => {
      const x = find(F.state.txns, v);
      F.startPay({ name: x.name, vpa: x.vpa, verified: true, biz: x.kind === "p2m", merchant: x.kind === "p2m" }, { kind: x.kind, entry: String(x.amount / 100), note: x.note });
    },
    disputeReason: (v, el, p) => { p.reason = +v; F.refresh(); },
    submitDispute: (v) => {
      if (!F.state.online) return offlineAlert();
      const x = find(F.state.txns, v), sel = F.$('[data-a="disputeReason"][aria-checked="true"] .t');
      const id = "UDIR" + F.digits(8);
      F.state.complaints.unshift({ id, reason: sel ? sel.textContent : "Payment issue", amount: x.amount, name: x.name, status: "Under review", at: Date.now() });
      F.sheet(`<div class="center stack" style="justify-items:center"><span class="av lg" style="background:var(--ok-bg);color:var(--ok)">${I("check", 28, 2.4)}</span>
        <div class="h2">Complaint raised</div><p class="muted" style="margin:0;line-height:1.55">Reference <b>${id}</b>. Your bank will update you here and by SMS. If it isn't resolved in 30 days, you can escalate to the RBI Ombudsman.</p></div>
        <button class="btn gold" data-a="disputeDone">${t("common.done")}</button>`);
    },
    disputeDone: () => { F.closeSheet(true); F.tabTo("profile"); F.go("help"); },
    readAll: () => { F.state.notifications.forEach((n) => { n.read = true; }); F.refresh(); F.toast("All caught up", "check"); },
    searchFill: (v, el, p) => { const i = F.$("#searchQ"); i.value = v; p.q = v; F.$("#searchOut").innerHTML = F.searchResults(v); },

    /* Offline vault */
    tabOfflineTap: () => { F.tabTo("offline"); F.go("offPay", { mode: "tap" }); },
    goOfflinePay: () => { F.tabTo("offline"); F.go("offPay", { mode: "tap" }); },
    eyeToggle: () => { F.state.hideBalance = !F.state.hideBalance; F.refresh(); },
    offContinue: (v, el, p) => { p.phase = "connect"; p.waiting = false; F.render("fade"); },
    offPickDevice: (v, el, p) => { p.to = F.OFF_DEVICES[+v]; p.phase = "sas"; F.render("fade"); },
    offConfirm: (v, el, p) => {
      const go = () => { p.phase = "sending"; p.step = 0; p.running = false; F.render("fade"); };
      if (F.state.biometric) F.bio(`Touch the sensor to pay ${F.inr(F.entryPaise(p.entry))} offline`, go); else go();
    },
    offAccept: (v, el, p) => { p.phase = "receiving"; p.step = 0; F.render("fade"); },
    loadVault: () => {
      const L = F.state.limits, room = L.offlineWallet - F.vaultTotal(), a = F.primary();
      if (!F.state.online) return F.alert("wifioff", "Loading needs internet", "You can pay from your vault offline, but adding money to it needs a connection to your bank.");
      if (room <= 0) return F.alert("vault", "Your vault is full", `It already holds the maximum ${F.inrShort(L.offlineWallet)} allowed by the RBI.`);
      const opts = [50000, 100000, 200000, room].filter((x, i, arr) => x <= room && arr.indexOf(x) === i);
      F.sheet(`<div class="h2">${t("offline.load")}</div><div class="small muted">You can add up to ${F.inrShort(room)}. The vault holds ${F.inrShort(L.offlineWallet)} at most.</div>
        <div class="chips" style="flex-wrap:wrap">${opts.map((x, i) => `<button class="chip" data-a="loadPick" data-v="${x}" aria-pressed="${i === 0}">${F.inrShort(x)}${x === room && i ? " · max" : ""}</button>`).join("")}</div>
        <div class="card flat"><dl class="kv"><dt>From</dt><dd>${esc(a.bank)} •• ${a.masked}</dd><dt>Notes</dt><dd id="loadNotes">${F.planNotes(opts[0]).length} hardware-sealed notes</dd></dl></div>
        <button class="btn gold" id="loadGo" data-a="loadConfirm" data-v="${opts[0]}">${I("lock", 18)}Load ${F.inrShort(opts[0])}</button>`);
    },
    loadPick: (v, el) => {
      F.$$("[data-a=loadPick]").forEach((b) => b.setAttribute("aria-pressed", String(b === el)));
      const g = F.$("#loadGo"); g.dataset.v = v; g.innerHTML = `${I("lock", 18)}Load ${F.inrShort(+v)}`;
      F.$("#loadNotes").textContent = `${F.planNotes(+v).length} hardware-sealed notes`;
    },
    loadConfirm: (v) => {
      const amt = +v, a = F.primary();
      F.authorize({ title: t("offline.load"), amount: amt, bank: `${a.bank} •• ${a.masked}`, steps: ["Encrypted with NPCI's public key", `Debited from ${a.bank}`, "Notes issued and sealed in StrongBox"] }, () => {
        a.balance -= amt;
        F.state.vault = F.state.vault.concat(F.planNotes(amt)).sort((x, y) => y - x);
        F.state.txns.unshift({ id: F.uid("t"), name: "Offline vault", vpa: "self", note: "Vault loaded", amount: amt, credit: false, status: "success", kind: "self", at: Date.now(), ref: "6" + F.digits(11) });
        F.toast(`${F.inr(amt)} added to your offline vault`, "vault"); F.refresh();
      });
    },
    unloadVault: () => {
      const total = F.vaultTotal(), a = F.primary();
      if (!total) return F.toast("Your vault is empty", "info");
      if (!F.state.online) return F.alert("wifioff", "Moving money needs internet", "Your notes stay safe in the vault. Try again when you're online.");
      F.confirm({ title: `Move ${F.inr(total)} to your bank?`, body: `The notes are destroyed inside StrongBox and ${F.inr(total)} is credited to ${esc(a.bank)} •• ${a.masked} right away.`, yes: "Move to bank" }, () => {
        a.balance += total; F.state.vault = [];
        F.state.txns.unshift({ id: F.uid("t"), name: "Offline vault", vpa: "self", note: "Moved to bank", amount: total, credit: true, status: "success", kind: "self", at: Date.now(), ref: "6" + F.digits(11) });
        F.toast(`${F.inr(total)} moved to ${esc(a.bank)}`, "bank"); F.refresh();
      });
    },

    /* Settings */
    toggle: (v) => {
      const parts = v.split("."), key = parts.pop(), obj = parts.reduce((o, k) => o[k], F.state);
      obj[key] = !obj[key]; F.refresh();
      if (v === "appLock") F.toast(obj[key] ? "App lock on" : "App lock off", "lock");
    },
    setAutoLock: (v) => { F.state.autoLock = v; F.refresh(); },
    personalLimit: (v) => {
      const nv = +v, cur = F.state.personalLimit || 0, set = () => { F.state.personalLimit = nv; F.refresh(); F.toast(nv ? `Your daily limit is now ${F.inrShort(nv)}` : "Using your bank's limit", "gauge"); };
      if (cur && (nv === 0 || nv > cur)) F.bio("Raising a limit needs your fingerprint", set); else set();
    },
    lockNow: () => { F.closeSheet(true); F.nav.stack = [{ name: "lock", params: {} }]; F.render("fade"); },
    unlock: (v, el, p) => { if (p.ok) return; p.ok = true; F.refresh(); F.later(450, () => F.tabTo("home")); },
    setTheme: (v) => { F.state.theme = v; F.savePrefs(); F.applyTheme(); F.syncRail(); F.refresh(); },
    setLang: (v) => { F.state.lang = v; F.savePrefs(); F.applyLang(); F.syncRail(); F.refresh(); },
    setPrimary: (v) => { F.state.accounts.forEach((a) => { a.primary = a.id === v; }); F.toast(`${esc(F.account(v).bank)} is now your primary account`, "bank"); F.refresh(); },
    changePin: (v) => {
      const a = F.account(v), bank = `${a.bank} •• ${a.masked}`;
      if (!F.state.online) return offlineAlert();
      F.authorize({ title: "Enter your current UPI PIN", bank, steps: ["Current PIN verified by your bank"] },
        () => F.authorize({ title: "Set a new UPI PIN", label: "Enter and confirm the new PIN", bank, steps: ["New PIN encrypted with NPCI's key", "Updated by your bank"] },
          () => F.toast(`UPI PIN changed for ${esc(a.bank)}`, "key")));
    },
    resetPin: (v) => {
      const a = F.account(v);
      F.sheet(`<div class="h2">${t("pin.forgot")}</div><div class="muted" style="line-height:1.55">You'll verify with your ${esc(a.bank)} debit card on NPCI's secure screen (last 6 digits and expiry), then an OTP from your bank.</div>
        <div class="card flat small muted" style="line-height:1.5">In this preview the secure screens are simulated. Never type real card details into a demo.</div>
        <button class="btn gold" data-a="resetGo" data-v="${a.id}">${t("common.continue")}</button>`);
    },
    resetGo: (v) => {
      const a = F.account(v), bank = `${a.bank} •• ${a.masked}`;
      F.authorize({ title: "Verify your debit card", label: "Card details & OTP on NPCI's screen", bank, steps: ["Card verified by your bank", "OTP verified"] },
        () => F.authorize({ title: "Set a new UPI PIN", label: "Enter and confirm the new PIN", bank, steps: ["New PIN encrypted with NPCI's key", "Updated by your bank"] }, () => F.toast("New UPI PIN set", "key")));
    },
    removeBank: (v) => {
      const a = F.account(v);
      F.confirm({ title: `Remove ${esc(a.bank)}?`, body: "You won't be able to pay from or receive into this account on Fortress. You can add it again later.", yes: "Remove", danger: true },
        () => { F.state.accounts = F.state.accounts.filter((x) => x.id !== v); F.toast("Account removed", "trash"); F.refresh(); });
    },
    addBank: () => {
      if (!F.state.online) return offlineAlert();
      const linked = F.state.accounts.map((a) => a.bank);
      const banks = ["Fortress Bank", "Harbor Co-operative Bank", "Nova Small Finance Bank", "Sunrise Bank"].filter((b) => !linked.includes(b));
      if (!banks.length) return F.toast("All available banks are linked", "info");
      F.sheet(`<div class="h2">Choose your bank</div><div class="list">${banks.map((b) => `<button class="li" data-a="addBankPick" data-v="${esc(b)}">${F.avatar(b, "sm", { biz: true, glyph: "bank" })}<div class="grow t">${esc(b)}</div><span class="trail">${I("chev", 18)}</span></button>`).join("")}</div>`);
    },
    addBankPick: (v) => {
      F.sheetLock = true;
      F.sheetBody(`<div class="spinner"></div><div class="h2 center">Finding your accounts</div><div class="small muted center">At ${esc(v)}, linked to ${esc(F.state.user.phone)}</div>`);
      F.later(1300, () => {
        F.sheetLock = false;
        const masked = String(1000 + (F.hash(v) % 9000));
        F.sheetBody(`<div class="h2">Account found</div><div class="card row">${F.avatar(v, "", { biz: true, glyph: "bank" })}<div class="grow"><div style="font:800 15px var(--text)">${esc(v)}</div><div class="small muted">Savings •• ${masked} · ${esc(F.state.user.name)}</div></div><span class="pill ok">Found</span></div>
          <button class="btn gold" data-a="addBankLink" data-v="${esc(v)}|${masked}">Link & set UPI PIN</button><button class="btn ghost" data-a="sheetClose">${t("common.cancel")}</button>`);
      });
    },
    addBankLink: (v) => {
      const [bank, masked] = v.split("|"), code = { "Nova Small Finance Bank": "NOVA", "Sunrise Bank": "SUNB", "Harbor Co-operative Bank": "HRBC", "Fortress Bank": "FTRS" }[bank] || "FTRS";
      F.authorize({ title: `Set UPI PIN · ${bank}`, label: "Debit card, OTP & new PIN on NPCI's screen", bank: `${bank} •• ${masked}`, steps: ["Card verified by your bank", "OTP verified", "UPI PIN set"] }, () => {
        F.state.accounts.push({ id: F.uid("a"), bank, masked, type: "Savings", primary: false, balance: 2000000 + (F.hash(bank) % 90000) * 100, ifsc: `${code}0${F.digits(6)}` });
        F.toast(`${esc(bank)} linked`, "bank"); F.refresh();
      });
    },
    createVpa: (v, el, p) => { const id = p.nv + "@fortress"; F.state.user.vpas.push(id); p.nv = ""; F.toast(`${esc(id)} created`, "at"); F.refresh(); },
    setPrimaryVpa: (v) => { F.state.user.vpa = v; F.toast(`${esc(v)} is now your primary UPI ID`, "at"); F.refresh(); },
    deleteVpa: (v) => F.confirm({ title: `Delete ${esc(v)}?`, body: "People who saved this UPI ID won't be able to pay you on it.", yes: "Delete", danger: true },
      () => { F.state.user.vpas = F.state.user.vpas.filter((x) => x !== v); F.toast("UPI ID deleted", "trash"); F.refresh(); }),
    unblock: (v) => { F.state.blocked = F.state.blocked.filter((x) => x !== v); F.toast("Unblocked", "check"); F.refresh(); },
    downloadData: () => {
      const s = F.state;
      F.copy(JSON.stringify({ exported: new Date().toISOString(), profile: { name: s.user.name, phone: s.user.phone, upiIds: s.user.vpas }, accounts: s.accounts.map(({ bank, masked, type, ifsc }) => ({ bank, account: "••" + masked, type, ifsc })), payments: s.txns, mandates: s.mandates }, null, 2), "your data (JSON)");
    },
    deleteAccount: () => F.confirm({ title: "Delete your Fortress account?", body: "Your UPI IDs are deregistered and app data is erased. Your bank accounts and money aren't affected. Payment records are kept only as long as the law requires.", yes: "Delete account", danger: true },
      () => { F.nav.stack = [{ name: "onboarding", params: {} }]; F.render("fade"); F.toast("Account deleted from this device", "trash"); }),
    signOut: () => F.confirm({ title: "Sign out from this device?", body: "To use UPI on this phone again you'll verify your SIM and set up Fortress from the start.", yes: "Sign out", danger: true },
      () => { F.nav.stack = [{ name: "onboarding", params: {} }]; F.render("fade"); }),
    supportChat: () => { F.chatLog = [[false, `Hi ${F.state.user.name.split(" ")[0]}, this is Fortress support. What can we help with?`]]; F.sheet(chatHtml(F.chatLog)); },
    chatPick: (v) => { F.chatLog.push([true, v], [false, CHAT[v]]); F.sheetBody(chatHtml(F.chatLog)); const s = F.$("#sheet"); s.scrollTop = s.scrollHeight; },

    /* Onboarding */
    obNext: (v, el, p) => { p.step = (p.step || 0) + 1; F.render("fwd"); },
    obLang: (v, el, p) => A.setLang(v),
    obSim: (v, el, p) => { if (!p.busy) { p.sim = +v; F.refresh(); } },
    obVerify: (v, el, p) => {
      if (p.busy) return;
      p.busy = true; p.sub = 0; F.refresh();
      const tick = () => { p.sub += 1; F.refresh(); if (p.sub < 3) F.later(650, tick); else F.later(420, () => { p.busy = false; p.step = 3; F.render("fwd"); }); };
      F.later(650, tick);
    },
    obBank: (v, el, p) => { if (p.busy) return; p.bank = v; p.busy = true; F.refresh(); F.later(1200, () => { p.busy = false; p.step = 4; F.render("fwd"); }); },
    obFinish: (v, el, p) => F.authorize({ title: "Set your UPI PIN", label: "Debit card, OTP & new PIN on NPCI's screen", bank: `${p.bank || "Fortress Bank"} •• 4402`, steps: ["Card verified by your bank", "OTP verified", "UPI PIN set"] },
      () => { p.step = 5; F.render("fwd"); F.later(220, () => F.burst(F.$("#ring"))); }),
    obDone: () => F.tabTo("home"),
  };
  F.actions = A;

  /* ── Text inputs ──────────────────────────────────────────── */
  const IN = {
    histSearch: (v, p) => { p.q = v; F.$("#histList").innerHTML = F.historyList(p); },
    search: (v, p) => { p.q = v; F.$("#searchOut").innerHTML = F.searchResults(v); },
    contactSearch: (v, p) => { p.q = v; F.$("#contactOut").innerHTML = F.contactList(v); },
    helpSearch: (v, p) => { p.q = v; return true; },
    phone: (v, p) => { p.num = v.replace(/\D/g, "").slice(0, 10); return true; },
    vpa: (v, p) => { p.vpa = v.trim(); p.verified = false; return true; },
    acc: (v, p) => { p.acc = v.replace(/\D/g, ""); return true; },
    acc2: (v, p) => { p.acc2 = v.replace(/\D/g, ""); return true; },
    ifsc: (v, p) => { p.ifsc = v.toUpperCase().replace(/[^A-Z0-9]/g, ""); return true; },
    accName: (v, p) => { p.name = v.replace(/[^\p{L} .'-]/gu, ""); return true; },
    billNum: (v, p) => { p.num = v.replace(/[^\w -]/g, ""); p.bill = null; return true; },
    rechargeNum: (v, p) => { p.num = v.replace(/\D/g, "").slice(0, 10); return true; },
    newVpa: (v, p) => { p.nv = v.toLowerCase().replace(/[^a-z0-9.]/g, ""); return true; },
    noop: () => {},
  };

  /* ── Wiring ───────────────────────────────────────────────── */
  const device = F.$("#device");
  device.addEventListener("click", (e) => {
    const el = e.target.closest("[data-a]");
    if (!el) { if (e.target.id === "sheetLayer" && !F.sheetLock) F.closeSheet(); return; }
    if (el.disabled || el.getAttribute("aria-disabled") === "true") return;
    const fn = A[el.dataset.a];
    if (fn) fn(el.dataset.v, el, P());
  });
  device.addEventListener("input", (e) => {
    const el = e.target.closest("[data-in]");
    if (!el) return;
    const name = el.dataset.in, fn = IN[name];
    if (!fn) return;
    const pos = el.selectionStart;
    if (fn(el.value, P()) === true) {
      F.refresh();
      const ne = F.$(`#viewport [data-in="${name}"]`);
      if (ne) {
        ne.focus({ preventScroll: true });
        try { const n = ne.value.length, at = Math.min(pos ?? n, n); ne.setSelectionRange(at, at); } catch (err) { /* not a text field */ }
      }
    }
  });

  document.addEventListener("keydown", (e) => {
    const sheetOpen = F.$("#sheetLayer").classList.contains("open");
    if (e.key === "Escape") { if (sheetOpen && !F.sheetLock) F.closeSheet(); return; }
    if (e.target.matches && e.target.matches("input, textarea, select")) {
      if (e.key === "Enter" && e.target.closest("#sheet")) { const b = F.$("#sheet .btn.gold"); if (b && !b.disabled) { e.preventDefault(); b.click(); } }
      return;
    }
    if (sheetOpen || e.metaKey || e.ctrlKey || e.altKey) return;
    if (!F.$("#viewport .keypad")) return;
    let k = null;
    if (/^[0-9]$/.test(e.key)) k = e.key;
    else if (e.key === ".") k = ".";
    else if (e.key === "Backspace") k = "del";
    else if (e.key === "Enter") { const b = F.$("#payBtn"); if (b && !b.disabled) { e.preventDefault(); b.click(); } return; }
    if (k) { e.preventDefault(); A.key(k, null, P()); }
  });

  /* Control rail (outside the phone) */
  const JUMP = {
    home: () => F.tabTo("home"),
    scan: () => { F.tabTo("home"); F.go("scan"); },
    offline: () => F.tabTo("offline"),
    nearby: () => { F.tabTo("offline"); F.go("offPay", { mode: "nearby" }); },
    receive: () => { F.tabTo("offline"); F.go("offReceive"); },
    bills: () => { F.tabTo("home"); F.go("bills"); },
    bank: () => { F.tabTo("home"); F.go("bank"); },
    requests: () => { F.tabTo("home"); F.go("requests"); },
    history: () => F.tabTo("history"),
    settings: () => F.tabTo("profile"),
    security: () => { F.tabTo("profile"); F.go("security"); },
    limits: () => { F.tabTo("profile"); F.go("limits"); },
    lock: () => A.lockNow(),
    onboarding: () => { F.closeSheet(true); F.nav.stack = [{ name: "onboarding", params: {} }]; F.render("fade"); },
  };
  const rail = F.$("#rail");
  if (rail) rail.addEventListener("click", (e) => {
    const b = e.target.closest("[data-r]");
    if (!b) return;
    const r = b.dataset.r, v = b.dataset.v;
    if (r === "net") F.setOnline(v === "on");
    else if (r === "theme") A.setTheme(v);
    else if (r === "lang") A.setLang(v);
    else if (r === "jump" && JUMP[v]) { F.sheetLock = false; JUMP[v](); }
  });

  /* ── Boot ─────────────────────────────────────────────────── */
  F.state.contactSync = true;
  const clock = () => { const d = new Date(), el = F.$("#sbTime"); if (el) el.textContent = d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false }); };
  clock(); setInterval(clock, 15000);
  F.applyTheme(); F.applyLang(); F.syncRail();
  F.nav.stack = [{ name: "splash", params: {} }];
  F.render("fade");
})();

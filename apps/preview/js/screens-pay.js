/* Fortress preview — payment flows. */
(() => {
  "use strict";
  const F = window.F, t = (k, v) => F.t(k, v), I = F.icon, esc = F.esc;

  const appbar = (title, right = "") => F.appbar(title, { right });

  /* ── Scanner ─────────────────────────────────────────────── */
  F.screens.scan = () => ({
    cls: "full flush", hero: true,
    html: `<div class="scanner" id="scanner">
      <div class="feed"></div>
      <div class="standee" id="standee"><div class="qr" id="standeeQr"></div><p>Asha Kirana Store · Scan & pay with any UPI app</p></div>
      <canvas class="scrim" id="scrim"></canvas>
      <div id="brackets"><i class="bracket tl"></i><i class="bracket tr"></i><i class="bracket bl"></i><i class="bracket br"></i></div>
      <div class="scan-top"><button class="icon-btn" data-a="back" aria-label="Close scanner">${I("close", 18)}</button><button class="icon-btn" data-a="torch" aria-pressed="false" aria-label="Torch">${I("torch", 18)}</button></div>
      <div class="scan-bottom"><div class="hint-t" id="scanHint">Point at any UPI QR code</div>
        <div class="scan-actions"><button data-a="scanGallery">${I("image", 20)}Upload QR</button><button data-a="go" data-v="myqr">${I("qr", 20)}My QR</button><button data-a="go" data-v="payUpi">${I("at", 20)}Enter UPI ID</button></div></div>
    </div>`,
    mount: F.mountScanner,
  });

  F.qrSvg = (text) => {
    try {
      const q = window.qrcode(0, "M");
      q.addData(text); q.make();
      return q.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
    } catch (e) {
      return `<svg viewBox="0 0 29 29"><rect width="29" height="29" fill="#fff"/><path d="M1 1h7v7H1zM21 1h7v7h-7zM1 21h7v7H1z" fill="none" stroke="#0C1633" stroke-width="2"/></svg>`;
    }
  };

  F.mountScanner = () => {
    F.$("#standeeQr").innerHTML = F.qrSvg("upi://pay?pa=asha.store@fortresspsp&pn=Asha%20Kirana%20Store&mc=5411&cu=INR");
    const host = F.$("#scanner"), scrim = F.$("#scrim"), br = F.$$(".bracket");
    const W = host.clientWidth, H = host.clientHeight, side = W * 0.68;
    const rect = { x: (W - side) / 2, y: H * 0.38 - side / 2, w: side, h: side }, vel = { x: 0, y: 0, w: 0, h: 0 };
    const dpr = Math.min(devicePixelRatio || 1, 2);
    scrim.width = W * dpr; scrim.height = H * dpr;
    const g = scrim.getContext("2d"); g.scale(dpr, dpr);
    const draw = () => {
      g.clearRect(0, 0, W, H); g.fillStyle = "rgba(4,8,20,.58)"; g.fillRect(0, 0, W, H);
      g.globalCompositeOperation = "destination-out"; g.beginPath();
      if (g.roundRect) g.roundRect(rect.x, rect.y, rect.w, rect.h, 24); else g.rect(rect.x, rect.y, rect.w, rect.h);
      g.fill(); g.globalCompositeOperation = "source-over";
      const len = Math.max(26, rect.w * 0.18);
      br.forEach((b) => { b.style.width = b.style.height = len + "px"; });
      br[0].style.left = br[2].style.left = rect.x + "px"; br[1].style.left = br[3].style.left = rect.x + rect.w - len + "px";
      br[0].style.top = br[1].style.top = rect.y + "px"; br[2].style.top = br[3].style.top = rect.y + rect.h - len + "px";
    };
    draw();
    const springTo = (target, done) => {
      const k = 1400, c = 2 * 0.82 * Math.sqrt(k); let last = performance.now();
      const step = (tm) => {
        if (!document.body.contains(host)) return;
        const dt = Math.min((tm - last) / 1000, 1 / 30); last = tm; let settled = true;
        for (const key of ["x", "y", "w", "h"]) {
          const a = -k * (rect[key] - target[key]) - c * vel[key];
          vel[key] += a * dt; rect[key] += vel[key] * dt;
          if (Math.abs(rect[key] - target[key]) > 0.4 || Math.abs(vel[key]) > 4) settled = false;
        }
        draw();
        if (settled || F.reduceMotion) { Object.assign(rect, target); draw(); done(); } else requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    F.later(1700, () => {
      const hr = host.getBoundingClientRect(), q = F.$("#standeeQr").getBoundingClientRect(), pad = 8;
      host.classList.add("locked");
      F.$("#scanHint").innerHTML = `<b style="color:#35D49A">Signed merchant QR verified</b><div style="font:600 12.5px var(--text);opacity:.8;margin-top:4px">asha.store@fortresspsp</div>`;
      springTo({ x: q.left - hr.left - pad, y: q.top - hr.top - pad, w: q.width + pad * 2, h: q.height + pad * 2 }, () => F.later(260, () => {
        F.startPay({ name: "Asha Kirana Store", vpa: "asha.store@fortresspsp", verified: true, biz: true, glyph: "store", merchant: true }, { kind: "p2m", replace: true });
      }));
    });
  };

  /* ── Amount entry (shared by every pay flow) ─────────────── */
  F.screens.amount = (p) => {
    const s = F.state, acct = F.account(p.acct), lim = s.limits;
    const amt = p.fixed ? p.amount : F.entryPaise(p.entry);
    const left = lim.dailyUpi - lim.usedToday;
    const newPayee = !s.txns.some((x) => x.vpa === p.payee.vpa) && !p.fixed;
    return {
      cls: "full",
      html: `<div class="appbar"><button class="icon-btn" data-a="back" aria-label="${t("common.back")}">${I("close", 18)}</button><div class="grow"></div>
          ${p.payee.merchant ? `<span class="pill ok">${I("shield", 12, 2.4)} Verified merchant</span>` : ""}</div>
        <div class="pay-head">
          ${F.avatar(p.payee.name, "lg", { verified: p.payee.verified, biz: p.payee.biz, glyph: p.payee.glyph })}
          <div class="h2">${p.kind === "self" ? "To " : "Paying "}${esc(p.payee.name)}</div>
          <div class="small muted">${esc(p.payee.sub || p.payee.vpa)}</div>
          ${p.payee.verified && p.kind !== "self" ? `<span class="pill ok">${t("pay.verified")} · ${esc(p.payee.bankName || "Bank verified")}</span>` : ""}
        </div>
        <div class="amount-entry ${amt ? "" : "empty"}" id="amt" aria-live="polite"><span class="cur">₹</span><span>${p.fixed ? F.inr(amt).replace("₹", "") : F.entryDisplay(p.entry)}</span>${p.fixed ? "" : '<i class="caret"></i>'}</div>
        <div class="row" style="justify-content:center;gap:8px;flex-wrap:wrap">
          <button class="note-chip" data-a="editNote">${I("chat", 15)}<span id="noteLbl">${esc(p.note || t("pay.addNote"))}</span></button>
          ${p.meta ? `<span class="note-chip">${esc(p.meta)}</span>` : ""}
        </div>
        ${newPayee && p.kind === "p2p" ? `<div class="card flat row small" style="padding:10px 12px;border-color:color-mix(in srgb,var(--warn) 35%,transparent);background:var(--warn-bg);color:var(--warn)">${I("alert", 16)}<span>First payment to this UPI ID. Check the verified name before you pay.</span></div>` : ""}
        <div style="flex:1"></div>
        ${p.kind === "self" ? "" : `<button class="acct-pick" data-a="pickAccount">${F.avatar(acct.bank, "sm", { glyph: "bank" })}<div class="grow"><div style="font:800 13.5px var(--text)">${t("pay.from")}: ${esc(acct.bank)} •• ${acct.masked}</div><div class="small muted">${F.inrShort(left)} of ₹1,00,000 daily UPI limit left</div></div>${I("down", 18)}</button>`}
        ${p.fixed ? "" : F.keypad()}
        <button class="btn gold" id="payBtn" data-a="reviewPay" ${amt ? "" : "disabled"}>${I("lock", 18)}<span id="payLbl">${amt ? `${t("pay.pay")} ${F.inr(amt)}` : t("pay.enterAmount")}</span></button>`,
    };
  };

  /* ── Receipt ─────────────────────────────────────────────── */
  F.screens.receipt = (p) => {
    const x = p.tx, failed = x.status === "failed", pending = x.status === "pending";
    return {
      cls: "full",
      html: `<div style="height:8px"></div>
        <div class="success-ring ${failed ? "fail" : pending ? "pending" : ""}" id="ring">${I(failed ? "close" : pending ? "clock" : "check", 50, 2.6)}</div>
        <div class="center stack" style="gap:6px">
          <div class="h1">${p.title || (failed ? t("pay.failed") : pending ? t("pay.pending") : x.credit ? t("pay.received") : t("pay.success"))}</div>
          <div class="amount">${F.inr(x.amount)}</div>
          <div class="muted">${x.credit ? "from" : "to"} <b style="color:var(--ink)">${esc(x.name)}</b></div>
          ${x.reason ? `<div class="small" style="color:var(--bad)">${esc(x.reason)}</div>` : ""}
        </div>
        <div class="card"><dl class="kv">${p.rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("")}</dl></div>
        <div style="flex:1"></div>
        <div class="btn-row"><button class="btn ghost" data-a="shareTx">${I("share", 18)}${t("common.share")}</button><button class="btn gold" data-a="receiptDone" data-v="${p.backTo || "home"}">${t("common.done")}</button></div>`,
      mount: () => { if (!failed) F.later(200, () => F.burst(F.$("#ring"))); },
    };
  };

  /* ── Contacts & chat thread ──────────────────────────────── */
  F.contactList = (q) => {
    q = (q || "").toLowerCase();
    const list = F.state.contacts.filter((c) => !F.state.blocked.includes(c.id) && (c.name + c.vpa + c.phone).toLowerCase().includes(q));
    return list.length ? `<div class="list">${list.map((c) => `<button class="li" data-a="go" data-v="thread" data-p="${c.id}">${F.avatar(c.name, "sm")}<div class="grow"><div class="t">${esc(c.name)}</div><div class="s">${esc(c.phone)} · ${esc(c.vpa)}</div></div><span class="trail">${I("chev", 18)}</span></button>`).join("")}</div>`
      : `<div class="card center muted">No contacts match. Pay by <button class="link" data-a="go" data-v="payPhone">phone number</button> or <button class="link" data-a="go" data-v="payUpi">UPI ID</button>.</div>`;
  };
  F.screens.contacts = (p) => ({
    cls: "full",
    html: `${appbar(t("act.contacts"))}
      <div class="search-box">${I("search", 18)}<input type="search" placeholder="Search name, number or UPI ID" value="${esc(p.q || "")}" data-in="contactSearch" aria-label="Search contacts" autocomplete="off"></div>
      <div class="grid4" style="grid-template-columns:repeat(3,minmax(0,1fr))">
        <button class="act" data-a="go" data-v="payPhone"><span class="tile">${I("phone", 22)}</span>Phone number</button>
        <button class="act" data-a="go" data-v="payUpi"><span class="tile">${I("at", 22)}</span>UPI ID</button>
        <button class="act" data-a="go" data-v="bank"><span class="tile">${I("bank", 22)}</span>Bank account</button></div>
      <div class="eyebrow">All contacts on UPI</div><div id="contactOut">${F.contactList(p.q)}</div>`,
  });

  F.screens.thread = (p) => {
    const c = F.contact(p.id) || F.state.contacts[0];
    const items = F.state.txns.filter((x) => x.name === c.name).slice().reverse();
    const reqs = F.state.outgoing.filter((r) => r.name === c.name);
    return {
      cls: "full",
      html: `<div class="appbar"><button class="icon-btn" data-a="back" aria-label="${t("common.back")}">${I("back", 18)}</button>${F.avatar(c.name, "sm", { verified: true })}
          <div class="grow"><div style="font:800 15px var(--text)" class="ellipsis">${esc(c.name)}</div><div class="small muted ellipsis">${esc(c.vpa)}</div></div>
          <button class="icon-btn" data-a="contactMenu" data-v="${c.id}" aria-label="More options">${I("gear", 18)}</button></div>
        <div class="thread">
          <div class="center small dim" style="margin:6px 0">Payments with ${esc(c.name.split(" ")[0])} are end-to-end signed by your device</div>
          ${items.map((x) => `<button class="bubble ${x.credit ? "" : "me"}" data-a="go" data-v="txn" data-p="${x.id}" style="text-align:left">
            <div class="bs">${x.credit ? "Received" : "Paid"} · ${F.when(x.at)}</div><div class="ba">${F.inr(x.amount)}</div><div class="bs">${esc(x.note || "")}${x.status === "failed" ? " · Failed" : ""}</div></button>`).join("")}
          ${reqs.map((r) => `<div class="bubble me"><div class="bs">You requested</div><div class="ba">${F.inr(r.amount)}</div><div class="bs">${esc(r.note || "")} · Waiting</div></div>`).join("")}
          ${!items.length && !reqs.length ? `<div class="card center muted">No payments yet. Say hello with your first payment.</div>` : ""}
        </div>
        <div style="flex:1"></div>
        <div class="btn-row"><button class="btn ghost" data-a="requestFrom" data-v="${c.id}">${I("request", 18)}Request</button><button class="btn gold" data-a="payContact" data-v="${c.id}">${I("lock", 18)}${t("pay.pay")}</button></div>`,
    };
  };

  /* ── Pay phone number / UPI ID ───────────────────────────── */
  F.screens.payPhone = (p) => {
    const d = (p.num || "").replace(/\D/g, "");
    const ok = /^[6-9]\d{9}$/.test(d), hit = ok ? F.lookupPhone(d) : null;
    return {
      cls: "full",
      html: `${appbar(t("act.phone"))}
        <div class="field"><label for="phoneIn">Mobile number linked to UPI</label>
          <div class="search-box"><b style="font:800 15px var(--text)">+91</b><input id="phoneIn" inputmode="numeric" maxlength="10" placeholder="98765 43210" value="${esc(d)}" data-in="phone" aria-label="Mobile number" autocomplete="off"></div>
          <div id="phoneHint" class="hint ${ok ? "ok" : ""}">${ok ? "Registered on UPI" : "Enter a 10-digit Indian mobile number"}</div></div>
        <div id="phoneOut">${hit ? `<div class="card row">${F.avatar(hit.name, "", { verified: true })}<div class="grow"><div style="font:800 15px var(--text)">${esc(hit.name)}</div><div class="small muted">${esc(hit.vpa)}</div></div><span class="pill ok">Verified</span></div>` : ""}</div>
        <div class="eyebrow">Recent</div>
        <div class="list">${F.state.contacts.slice(0, 4).map((c) => `<button class="li" data-a="payContact" data-v="${c.id}">${F.avatar(c.name, "sm")}<div class="grow"><div class="t">${esc(c.name)}</div><div class="s">${esc(c.phone)}</div></div></button>`).join("")}</div>
        <div style="flex:1"></div>
        <button class="btn gold" id="phoneGo" data-a="phoneContinue" ${ok ? "" : "disabled"}>${t("common.continue")}</button>`,
    };
  };

  F.screens.payUpi = (p) => {
    const v = (p.vpa || "").trim(), ok = F.vpaOk(v), hit = ok && p.verified ? F.lookupVpa(v) : null;
    return {
      cls: "full",
      html: `${appbar(t("act.upi"))}
        <div class="field"><label for="vpaIn">UPI ID</label>
          <div class="search-box">${I("at", 18)}<input id="vpaIn" placeholder="name@bank" value="${esc(v)}" data-in="vpa" aria-label="UPI ID" autocomplete="off" autocapitalize="off" spellcheck="false"></div>
          <div class="hint ${hit ? "ok" : ""}" id="vpaHint">${hit ? "Name verified with the payee's bank" : "UPI IDs look like name@bank"}</div></div>
        ${hit ? `<div class="card row">${F.avatar(hit.name, "", { verified: true })}<div class="grow"><div style="font:800 15px var(--text)">${esc(hit.name)}</div><div class="small muted">${esc(v)}</div></div><span class="pill ok">Verified</span></div>` : ""}
        <div class="card flat small muted" style="line-height:1.55">${I("shield", 16)} Fortress checks every UPI ID with the payee's bank and warns you about reported fraud handles before you pay.</div>
        <div style="flex:1"></div>
        <button class="btn gold" id="vpaGo" data-a="${hit ? "vpaContinue" : "vpaVerify"}" ${ok ? "" : "disabled"}>${hit ? t("common.continue") : "Verify UPI ID"}</button>`,
    };
  };

  /* ── Bank transfer (account + IFSC) ──────────────────────── */
  F.screens.bank = (p) => {
    const acc = p.acc || "", acc2 = p.acc2 || "", ifsc = (p.ifsc || "").toUpperCase(), name = p.name || "";
    const accOk = /^\d{9,18}$/.test(acc), match = accOk && acc === acc2, ifscOk = F.ifscOk(ifsc), bank = ifscOk ? F.ifscBank(ifsc) : null;
    const ready = match && ifscOk && name.trim().length >= 3;
    return {
      cls: "full",
      html: `${appbar(t("act.bank"))}
        <div class="field"><label for="accIn">Account number</label><input id="accIn" class="input big" inputmode="numeric" maxlength="18" value="${esc(acc)}" data-in="acc" autocomplete="off" type="password" aria-describedby="accHint"></div>
        <div class="field"><label for="acc2In">Re-enter account number</label><input id="acc2In" class="input big" inputmode="numeric" maxlength="18" value="${esc(acc2)}" data-in="acc2" autocomplete="off">
          <div id="accHint" class="hint ${acc2 ? (match ? "ok" : "bad") : ""}">${acc2 ? (match ? "Account numbers match" : "Account numbers don't match") : "9–18 digits"}</div></div>
        <div class="field"><label for="ifscIn">IFSC</label><input id="ifscIn" class="input big" maxlength="11" value="${esc(ifsc)}" data-in="ifsc" autocomplete="off" style="text-transform:uppercase" placeholder="FTRS0000402">
          <div class="hint ${ifsc.length === 11 ? (ifscOk ? "ok" : "bad") : ""}" id="ifscHint">${ifsc.length === 11 ? (ifscOk ? (bank ? `${bank} · branch verified` : "Valid IFSC") : "Invalid IFSC format") : "11 characters, e.g. FTRS0000402"}</div></div>
        <div class="field"><label for="nameIn">Account holder name</label><input id="nameIn" class="input" maxlength="60" value="${esc(name)}" data-in="accName" autocomplete="off"></div>
        <div class="card flat small muted" style="line-height:1.55">Sent instantly over IMPS/UPI, 24×7. The bank verifies the account holder's name before the money moves.</div>
        <div style="flex:1"></div>
        <button class="btn gold" id="bankGo" data-a="bankContinue" ${ready ? "" : "disabled"}>${t("common.continue")}</button>`,
    };
  };

  /* ── Self transfer ───────────────────────────────────────── */
  F.screens.self = (p) => {
    const accts = F.state.accounts, from = F.account(p.from || accts[0].id), to = F.account(p.to || accts[1].id);
    const amt = F.entryPaise(p.entry);
    return {
      cls: "full",
      html: `${appbar(t("act.self"))}
        <div class="card stack">
          <button class="acct-pick" data-a="selfPick" data-v="from">${F.avatar(from.bank, "sm", { glyph: "bank" })}<div class="grow"><div class="small muted">From</div><div style="font:800 14px var(--text)">${esc(from.bank)} •• ${from.masked}</div></div>${I("down", 18)}</button>
          <div class="center">${I("swap", 20)}</div>
          <button class="acct-pick" data-a="selfPick" data-v="to">${F.avatar(to.bank, "sm", { glyph: "bank" })}<div class="grow"><div class="small muted">To</div><div style="font:800 14px var(--text)">${esc(to.bank)} •• ${to.masked}</div></div>${I("down", 18)}</button>
        </div>
        <div class="amount-entry ${amt ? "" : "empty"}" id="amt"><span class="cur">₹</span><span>${F.entryDisplay(p.entry)}</span><i class="caret"></i></div>
        <div style="flex:1"></div>
        ${F.keypad()}
        <button class="btn gold" id="payBtn" data-a="selfPay" ${amt && from.id !== to.id ? "" : "disabled"}>${I("lock", 18)}<span id="payLbl">${amt ? `Transfer ${F.inr(amt)}` : t("pay.enterAmount")}</span></button>`,
    };
  };

  /* ── Request money, incoming requests, split ─────────────── */
  F.screens.request = (p) => {
    p.cid = p.cid || p.id;
    const c = p.cid ? F.contact(p.cid) : null, amt = F.entryPaise(p.entry);
    if (!c) {
      return {
        cls: "full",
        html: `${appbar(t("act.request"))}
          ${F.state.requests.length ? `<button class="request" data-a="go" data-v="requests" style="text-align:left">${I("bell", 20)}<div class="grow"><div style="font:800 13.5px var(--text)">${F.state.requests.length} request${F.state.requests.length > 1 ? "s" : ""} waiting for you</div><div class="small muted">Review before you pay</div></div>${I("chev", 18)}</button>` : ""}
          <div class="eyebrow">Request from</div>${F.contactList("").replaceAll('data-v="thread"', 'data-v="request"')}`,
      };
    }
    return {
      cls: "full",
      html: `${appbar(t("act.request"))}
        <div class="pay-head">${F.avatar(c.name, "lg", { verified: true })}<div class="h2">Request from ${esc(c.name)}</div><div class="small muted">${esc(c.vpa)}</div></div>
        <div class="amount-entry ${amt ? "" : "empty"}" id="amt"><span class="cur">₹</span><span>${F.entryDisplay(p.entry)}</span><i class="caret"></i></div>
        <div class="row" style="justify-content:center"><button class="note-chip" data-a="editNote">${I("chat", 15)}<span id="noteLbl">${esc(p.note || t("pay.addNote"))}</span></button></div>
        <div style="flex:1"></div>${F.keypad()}
        <button class="btn gold" id="payBtn" data-a="sendRequest" ${amt ? "" : "disabled"}>${I("request", 18)}<span id="payLbl">${amt ? `Request ${F.inr(amt)}` : t("pay.enterAmount")}</span></button>`,
    };
  };

  F.screens.requests = () => ({
    cls: "full",
    html: `${appbar("Payment requests")}
      <div class="card flat small" style="line-height:1.55;background:var(--info-bg);color:var(--info);border:0">${I("shield", 16)} You never need to enter your UPI PIN to <b>receive</b> money. Requests that ask you to "approve to receive" are fraud.</div>
      ${F.state.requests.length ? F.state.requests.map((r) => `<div class="card stack">
        <div class="row">${F.avatar(r.name, "", { verified: r.known })}<div class="grow"><div style="font:800 15px var(--text)">${esc(r.name)}</div><div class="small muted ellipsis">${esc(r.vpa)}</div></div><div style="font:800 17px var(--display)" class="tnum">${F.inr(r.amount)}</div></div>
        <div class="small muted">${esc(r.note)} · ${F.when(r.at)}</div>
        ${r.known ? "" : `<div class="row small" style="color:var(--bad);font-weight:700">${I("alert", 16)}Flagged: unknown requester, reported handle pattern</div>`}
        <div class="btn-row">${r.known ? `<button class="btn ghost" data-a="declineReq" data-v="${r.id}">Decline</button><button class="btn gold" data-a="payReq" data-v="${r.id}">${t("pay.pay")}</button>`
          : `<button class="btn ghost" data-a="declineReq" data-v="${r.id}">Decline</button><button class="btn danger" data-a="blockReq" data-v="${r.id}">Block & report</button>`}</div></div>`).join("")
        : `<div class="card center muted">No pending requests.</div>`}`,
  });

  F.screens.split = (p) => {
    p.sel = p.sel || [];
    const total = F.entryPaise(p.entry), n = p.sel.length + 1, share = total && p.sel.length ? Math.ceil(total / n) : 0;
    return {
      cls: "full",
      html: `${appbar(t("manage.split"))}
        <div class="amount-entry ${total ? "" : "empty"}" id="amt"><span class="cur">₹</span><span>${F.entryDisplay(p.entry)}</span><i class="caret"></i></div>
        <div class="center small muted" id="splitInfo">${share ? `${F.inr(share)} each · ${n} people including you` : "Enter the total, then pick friends"}</div>
        <div class="chips" style="flex-wrap:wrap">${F.state.contacts.slice(0, 8).map((c) => `<button class="chip" data-a="splitToggle" data-v="${c.id}" aria-pressed="${p.sel.includes(c.id)}">${esc(c.name.split(" ")[0])}</button>`).join("")}</div>
        <div style="flex:1"></div>${F.keypad()}
        <button class="btn gold" id="payBtn" data-a="sendSplit" ${share ? "" : "disabled"}>${I("split", 18)}<span id="payLbl">${share ? `Send ${p.sel.length} request${p.sel.length > 1 ? "s" : ""}` : "Split"}</span></button>`,
    };
  };

  /* ── My QR ───────────────────────────────────────────────── */
  F.screens.myqr = (p) => {
    const u = F.state.user, acct = F.primary();
    const amt = p.amount || 0;
    const uri = `upi://pay?pa=${encodeURIComponent(u.vpa)}&pn=${encodeURIComponent(u.name)}&cu=INR${amt ? `&am=${(amt / 100).toFixed(2)}` : ""}`;
    return {
      cls: "full",
      html: `${appbar(t("home.myQr"))}
        <div class="qr-card">
          ${F.avatar(u.name, "lg")}
          <div><div style="font:800 17px var(--display)">${esc(u.name)}</div><div style="font:600 13px var(--text);opacity:.8;margin-top:4px">${esc(u.vpa)}</div></div>
          <div class="qrbox">${F.qrSvg(uri)}<img src="fortress-mark.png" alt=""></div>
          ${amt ? `<div style="font:700 22px var(--display)" class="tnum">${F.inr(amt)}</div>` : ""}
          <div style="font:600 12px var(--text);opacity:.75">Scan with any UPI app to pay · Money goes to ${esc(acct.bank)} •• ${acct.masked}</div>
        </div>
        <div class="btn-row"><button class="btn ghost" data-a="copyVpa">${I("copy", 18)}Copy UPI ID</button><button class="btn ghost" data-a="shareQr">${I("share", 18)}Share QR</button></div>
        <button class="btn ${amt ? "ghost" : "gold"}" data-a="qrAmount">${amt ? "Remove amount" : "Add an amount"}</button>`,
    };
  };

  /* ── Bills & recharges ───────────────────────────────────── */
  F.screens.bills = () => ({
    cls: "full",
    html: `${appbar(t("sec.bills"))}
      <div class="card"><div class="grid4">${F.billCats.map((c) => `<button class="act" data-a="bill" data-v="${c.id}"><span class="tile">${I(c.icon, 22)}</span>${t("bill." + c.id)}</button>`).join("")}</div></div>
      <div class="eyebrow">Saved billers</div>
      <div class="list">
        <button class="li" data-a="billSaved" data-v="electricity|MetroPower Distribution|4410 2291">${F.avatar("MetroPower", "sm", { biz: true, glyph: "bolt" })}<div class="grow"><div class="t">MetroPower Distribution</div><div class="s">Consumer 4410 2291 · Due in 3 days</div></div><span class="pill warn">₹2,612 due</span></button>
        <button class="li" data-a="bill" data-v="mobile">${F.avatar("Nova Mobile", "sm", { biz: true, glyph: "mobile" })}<div class="grow"><div class="t">Nova Mobile · 98765 43210</div><div class="s">₹479 plan expires in 6 days</div></div><span class="trail">${I("chev", 18)}</span></button>
      </div>
      <div class="card flat small muted" style="line-height:1.55">${t("bill.powered")} You get an instant BBPS receipt, and complaints go through BBPS with tracked resolution.</div>`,
  });

  F.screens.billPay = (p) => {
    const cat = p.cat, name = t("bill." + cat), billers = F.billers[cat] || [];
    if (!p.biller) {
      return {
        cls: "full",
        html: `${appbar(name)}<div class="search-box">${I("search", 18)}<input type="search" placeholder="Search billers" aria-label="Search billers" data-in="noop"></div>
          <div class="list">${billers.map((b) => `<button class="li" data-a="pickBiller" data-v="${esc(b)}">${F.avatar(b, "sm", { biz: true, glyph: F.billCats.find((c) => c.id === cat).icon })}<div class="grow t">${esc(b)}</div><span class="trail">${I("chev", 18)}</span></button>`).join("")}</div>`,
      };
    }
    const num = p.num || "";
    return {
      cls: "full",
      html: `${appbar(esc(p.biller))}
        <div class="field"><label for="billNum">${F.consumerLabel[cat] || "Consumer number"}</label><input id="billNum" class="input big" value="${esc(num)}" data-in="billNum" autocomplete="off" maxlength="20"></div>
        <div id="billOut">${p.bill ? `<div class="card stack">
          <div class="row"><div class="grow"><div class="eyebrow">Bill for</div><div style="font:800 15px var(--text)">${esc(p.bill.holder)}</div></div><span class="pill warn">Due ${esc(p.bill.due)}</span></div>
          <dl class="kv"><dt>Bill period</dt><dd>${esc(p.bill.period)}</dd><dt>Bill number</dt><dd>${esc(p.bill.no)}</dd><dt>Amount due</dt><dd style="font:800 18px var(--display)">${F.inr(p.bill.amount)}</dd></dl>
          <div class="small muted">Fetched live from ${esc(p.biller)} via BBPS</div></div>` : ""}</div>
        <div style="flex:1"></div>
        <button class="btn gold" id="billGo" data-a="${p.bill ? "payBill" : "fetchBill"}" ${num.length >= 6 ? "" : "disabled"}>${p.bill ? `${t("pay.pay")} ${F.inr(p.bill.amount)}` : "Fetch bill"}</button>`,
    };
  };

  F.screens.recharge = (p) => {
    const num = p.num ?? "9876543210", ok = /^[6-9]\d{9}$/.test(num);
    const op = ok ? F.operators[F.hash(num) % F.operators.length] : null;
    p.tab = p.tab || "Popular";
    return {
      cls: "full",
      html: `${appbar(t("bill.mobile"))}
        <div class="search-box"><b style="font:800 15px var(--text)">+91</b><input inputmode="numeric" maxlength="10" value="${esc(num)}" data-in="rechargeNum" aria-label="Mobile number"></div>
        <div id="rcOut">${ok ? `<div class="card row">${F.avatar(op, "sm", { biz: true, glyph: "mobile" })}<div class="grow"><div style="font:800 14px var(--text)">${esc(op)} · Prepaid</div><div class="small muted">Maharashtra circle · detected automatically</div></div><button class="link" data-a="toast" data-v="Operator can be changed in the full app">Change</button></div>
          <div class="chips">${Object.keys(F.plans).map((k) => `<button class="chip" data-a="planTab" data-v="${k}" aria-pressed="${p.tab === k}">${k}</button>`).join("")}</div>
          <div class="stack">${F.plans[p.tab].map(([amt, val, desc]) => `<button class="plan" data-a="pickPlan" data-v="${amt}|${esc(desc)}"><div class="pa">${F.inrShort(amt)}</div><div class="grow"><div style="font:800 13.5px var(--text)">${val}</div><div class="small muted">${desc}</div></div>${I("chev", 18)}</button>`).join("")}</div>`
          : `<div class="card center muted">Enter a 10-digit mobile number</div>`}</div>`,
    };
  };

  /* ── Autopay ─────────────────────────────────────────────── */
  F.screens.autopay = () => ({
    cls: "full",
    html: `${appbar(t("set.autopay"))}
      <div class="card flat small muted" style="line-height:1.55">UPI AutoPay mandates you approved with your UPI PIN. You get a reminder 24 hours before every debit and can pause or cancel any time.</div>
      <div class="stack">${F.state.mandates.map((m) => `<div class="card stack">
        <div class="row">${F.avatar(m.name, "sm", { biz: true, glyph: m.glyph })}<div class="grow"><div style="font:800 14.5px var(--text)" class="ellipsis">${esc(m.name)}</div><div class="small muted">${m.upto ? "Up to " : ""}${F.inr(m.amount)} · ${m.freq}</div></div>
          <span class="pill ${m.status === "active" ? "ok" : m.status === "paused" ? "warn" : "bad"}">${m.status}</span></div>
        ${m.status !== "revoked" ? `<div class="small muted">Next debit: ${m.next}</div>
        <div class="btn-row"><button class="btn sm ghost" data-a="mandateToggle" data-v="${m.id}">${I(m.status === "active" ? "pause" : "play", 16)}${m.status === "active" ? "Pause" : "Resume"}</button>
          <button class="btn sm danger" data-a="mandateRevoke" data-v="${m.id}">${I("trash", 16)}Cancel mandate</button></div>` : `<div class="small muted">Cancelled. No further debits.</div>`}
      </div>`).join("")}</div>`,
  });
})();

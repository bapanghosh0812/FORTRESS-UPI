/* Fortress preview — Home, History, transaction detail, disputes, notifications, search, insights. */
(() => {
  "use strict";
  const F = window.F, t = (k, v) => F.t(k, v), I = F.icon, esc = F.esc;
  F.screens = F.screens || {};

  const appbar = (title, opts = {}) => `<div class="appbar">
    <button class="icon-btn" data-a="${opts.close ? "back" : "back"}" aria-label="${t("common.back")}">${I(opts.close ? "close" : "back", 18)}</button>
    <div class="title grow ellipsis">${title}</div>${opts.right || ""}</div>`;
  F.appbar = appbar;

  const statusPill = (tx) => {
    if (tx.status === "failed") return `<span class="pill bad">${t("f.failed")}</span>`;
    if (tx.status === "pending") return `<span class="pill warn">Pending</span>`;
    if (tx.status === "settling") return `<span class="pill info">Settles on sync</span>`;
    if (tx.kind === "offline") return `<span class="pill ok">Offline · settled</span>`;
    if (tx.kind === "request") return `<span class="pill gold">Requested</span>`;
    return "";
  };
  F.txRow = (tx, opts = {}) => `<button class="li" data-a="go" data-v="txn" data-p="${tx.id}">
      ${F.avatar(tx.name, "sm", { biz: ["bill", "recharge", "p2m"].includes(tx.kind), glyph: tx.kind === "self" ? "swap" : null })}
      <div class="grow"><div class="t ellipsis">${esc(tx.name)}</div><div class="s ellipsis">${esc(opts.time === false ? tx.note : F.when(tx.at))}</div></div>
      <div style="display:grid;justify-items:end;gap:4px"><div class="amt ${tx.credit ? "cr" : ""} ${tx.status === "failed" ? "fail" : ""}">${tx.credit ? "+ " : ""}${F.inr(tx.amount)}</div>${statusPill(tx)}</div>
    </button>`;

  /* ── Home ─────────────────────────────────────────────────── */
  F.screens.home = () => {
    const s = F.state, u = s.user, hour = new Date().getHours();
    const greet = t(hour < 12 ? "home.morning" : hour < 17 ? "home.afternoon" : "home.evening");
    const unread = s.notifications.filter((n) => !n.read).length;
    const act = (id, icon, key, gold, badge) => `<button class="act" data-a="${id.startsWith("go:") ? "go" : id}" ${id.startsWith("go:") ? `data-v="${id.slice(3)}"` : ""}>
        <span class="tile ${gold ? "gold" : ""}">${I(icon, 24)}${badge ? `<span class="badge">${badge}</span>` : ""}</span>${t(key)}</button>`;
    const req = s.requests[0];
    const vt = F.vaultTotal();
    const monthBars = [38, 52, 30, 70, 44, 58, 90, 47, 36, 62, 28, 75];
    return {
      tab: "home", hero: true, cls: "flush",
      html: `
      <section class="hero">
        <div class="row">
          <div class="brand grow"><img src="fortress-mark.png" alt="Fortress"><div class="wm">FORTRESS<small>UPI · PAY ANYTIME</small></div></div>
          <button class="hero-btn" data-a="go" data-v="notifications" aria-label="Notifications${unread ? `, ${unread} unread` : ""}">${I("bell", 20)}${unread ? '<span class="dot"></span>' : ""}</button>
          <button class="hero-btn" data-a="tab" data-v="profile" aria-label="${t("nav.profile")}" style="overflow:hidden;padding:0;border:0">${F.avatar(u.name, "sm")}</button>
        </div>
        <div style="margin-top:20px;font:600 14px var(--text);opacity:.82">${greet}, ${esc(u.name.split(" ")[0])}</div>
        <button class="search" data-a="go" data-v="search">${I("search", 18)}<span class="ellipsis">${t("home.search")}</span></button>
        <div class="upi-id"><span class="ellipsis">UPI ID: <b>${esc(u.vpa)}</b></span>
          <span class="row" style="gap:6px;flex:none"><button data-a="copyVpa">${I("copy", 13)}Copy</button><button data-a="go" data-v="myqr">${I("qr", 13)}${t("home.myQr")}</button></span></div>
      </section>

      <section class="card float" aria-label="Payments">
        <div class="grid4">
          ${act("go:scan", "scan", "act.scan", true)}
          ${act("go:contacts", "contacts", "act.contacts")}
          ${act("go:payPhone", "phone", "act.phone")}
          ${act("go:bank", "bank", "act.bank")}
          ${act("go:payUpi", "at", "act.upi")}
          ${act("go:self", "swap", "act.self")}
          ${act("checkBalance", "wallet", "act.balance")}
          ${act("go:request", "request", "act.request", false, s.requests.length || "")}
        </div>
      </section>

      ${req ? `<section class="request" aria-label="Payment request">
        ${F.avatar(req.name, "sm")}
        <div class="grow"><div style="font:800 13.5px var(--text)" class="ellipsis">${esc(req.name)} requested ${F.inr(req.amount)}</div>
          <div class="small muted ellipsis">${esc(req.note)}${s.requests.length > 1 ? ` · +${s.requests.length - 1} more` : ""}</div></div>
        <button class="btn sm ghost" data-a="go" data-v="requests">View</button></section>` : ""}

      <section class="vault" aria-label="${t("offline.title")}">
        <div class="row"><div class="grow"><div class="lbl">${t("offline.balance").toUpperCase()}</div><div class="bal">${s.hideBalance ? "₹ ••••" : F.inr(vt)}</div></div>
          <span class="pill" style="background:rgba(255,255,255,.12);color:${s.online ? "#B9F5DD" : "#F2D488"}">${s.online ? "Ready" : "Offline mode"}</span></div>
        <div class="small" style="opacity:.8;margin-top:6px">${t("offline.ready")} · ${s.vault.length} hardware-sealed notes</div>
        <div class="row" style="margin-top:14px;gap:8px">
          <button class="btn sm gold" data-a="tabOfflineTap">${I("nfc", 16)}${t("offline.tap")}</button>
          <button class="btn sm" data-a="tab" data-v="offline">${t("offline.manage")}</button>
        </div>
      </section>

      <section class="stack" aria-label="${t("sec.people")}">
        <div class="section-head"><div class="h2">${t("sec.people")}</div><button class="link" data-a="go" data-v="contacts">${t("common.seeAll")}</button></div>
        <div class="people">${s.contacts.slice(0, 7).map((c) => `<button class="person" data-a="go" data-v="thread" data-p="${c.id}">${F.avatar(c.name)}<span class="ellipsis">${esc(c.name.split(" ")[0])}</span></button>`).join("")}
          <button class="person" data-a="go" data-v="contacts"><span class="av" style="background:var(--tile);color:var(--tile-ink)">${I("plus", 20)}</span>${t("common.more")}</button></div>
      </section>

      <section class="stack" aria-label="${t("sec.business")}">
        <div class="section-head"><div class="h2">${t("sec.business")}</div></div>
        <div class="people">${s.businesses.map((b) => `<button class="person" data-a="payBiz" data-v="${b.id}">${F.avatar(b.name, "", { biz: true, glyph: b.glyph })}<span class="ellipsis">${esc(b.name.split(" ")[0])}</span></button>`).join("")}</div>
      </section>

      <section class="card" aria-label="${t("sec.bills")}">
        <div class="section-head" style="margin-bottom:14px"><div class="h2">${t("sec.bills")}</div><button class="link" data-a="go" data-v="bills">${t("common.seeAll")}</button></div>
        <div class="grid4">${F.billCats.slice(0, 8).map((c) => `<button class="act" data-a="bill" data-v="${c.id}"><span class="tile">${I(c.icon, 22)}</span>${t("bill." + c.id)}</button>`).join("")}</div>
      </section>

      <section class="stack" aria-label="${t("sec.manage")}">
        <div class="h2">${t("sec.manage")}</div>
        <button class="card" data-a="go" data-v="insights" style="text-align:left;display:grid;gap:12px">
          <div class="row"><div class="grow"><div class="eyebrow">This month</div><div style="font:700 24px var(--display)" class="tnum">₹46,210</div></div>
            <span class="pill ok">8% less than Sept</span></div>
          <div class="bars" aria-hidden="true">${monthBars.map((h, i) => `<i class="${i === 6 ? "on" : ""}" style="height:${h}%"></i>`).join("")}</div>
        </button>
        <div class="list">
          <button class="li" data-a="go" data-v="autopay"><span class="lead">${I("repeat", 20)}</span><div class="grow"><div class="t">${t("manage.autopay")}</div><div class="s">${s.mandates.filter((m) => m.status === "active").length} active · next StreamMax on 12 Oct</div></div><span class="trail">${I("chev", 18)}</span></button>
          <button class="li" data-a="go" data-v="split"><span class="lead">${I("split", 20)}</span><div class="grow"><div class="t">${t("manage.split")}</div><div class="s">Split with friends, track who paid</div></div><span class="trail">${I("chev", 18)}</span></button>
          <button class="li" data-a="tab" data-v="history"><span class="lead">${I("history", 20)}</span><div class="grow"><div class="t">${t("manage.history")}</div><div class="s">Search, filter, export statements</div></div><span class="trail">${I("chev", 18)}</span></button>
        </div>
      </section>

      <section class="shield-foot"><img src="fortress-mark.png" alt=""><div class="grow"><div style="font:800 13px var(--text)">${t("home.secured")}</div><div class="small muted">${t("home.securedSub")}</div></div></section>`,
    };
  };

  /* ── History ─────────────────────────────────────────────── */
  const filterTx = (p) => {
    const q = (p.q || "").trim().toLowerCase();
    return F.state.txns.filter((x) => {
      const okF = p.filter === "all" || (p.filter === "sent" && !x.credit) || (p.filter === "received" && x.credit) ||
        (p.filter === "offline" && x.kind === "offline") || (p.filter === "bills" && ["bill", "recharge"].includes(x.kind)) || (p.filter === "failed" && x.status === "failed");
      const okQ = !q || [x.name, x.vpa, x.note, (x.amount / 100).toFixed(2), x.ref].join(" ").toLowerCase().includes(q);
      return okF && okQ;
    });
  };
  F.historyList = (p) => {
    const list = filterTx(p);
    if (!list.length) return `<div class="card center muted">${t("history.empty")}</div>`;
    const groups = [];
    list.forEach((x) => { const g = F.dayGroup(x.at); let grp = groups.find((y) => y.label === g); if (!grp) groups.push((grp = { label: g, items: [] })); grp.items.push(x); });
    return groups.map((g) => `<div class="eyebrow group-label">${g.label}</div><div class="list">${g.items.map((x) => F.txRow(x)).join("")}</div>`).join("");
  };
  F.screens.history = (p) => {
    p.filter = p.filter || "all";
    const f = (id) => `<button class="chip" data-a="histFilter" data-v="${id}" aria-pressed="${p.filter === id}">${t("f." + id)}</button>`;
    const spent = F.state.txns.filter((x) => !x.credit && x.status !== "failed").reduce((a, x) => a + x.amount, 0);
    return {
      tab: "history",
      html: `<div class="appbar"><div class="title grow">${t("history.title")}</div><button class="icon-btn" data-a="statement" aria-label="Statement">${I("download", 18)}</button></div>
        <div class="search-box">${I("search", 18)}<input id="histQ" type="search" placeholder="${t("history.search")}" value="${esc(p.q || "")}" data-in="histSearch" aria-label="${t("history.search")}"></div>
        <div class="chips" role="group" aria-label="Filter">${f("all")}${f("sent")}${f("received")}${f("offline")}${f("bills")}${f("failed")}</div>
        <div class="card flat row" style="padding:14px 16px"><div class="grow"><div class="eyebrow">Spent · last 7 days</div><div style="font:700 20px var(--display)" class="tnum">${F.inr(spent)}</div></div><button class="btn sm ghost" data-a="go" data-v="insights">Insights</button></div>
        <div id="histList" class="stack">${F.historyList(p)}</div>`,
    };
  };

  /* ── Transaction detail ──────────────────────────────────── */
  F.screens.txn = (p) => {
    const x = F.state.txns.find((y) => y.id === p.id);
    if (!x) return F.screens.history({});
    const failed = x.status === "failed", pending = x.status === "pending" || x.status === "settling";
    const acct = F.primary();
    const steps = failed
      ? [["Payment started", true], ["Bank couldn't complete it", true], ["Money not debited", true]]
      : x.kind === "offline"
        ? [["Signed with single-use StrongBox notes", true], ["Delivered over NFC/Bluetooth", true], [x.status === "settling" ? "Waiting to settle (needs internet)" : "Settled with Fortress issuer", x.status !== "settling"]]
        : [["Payment initiated", true], [x.credit ? "Received in your bank" : "Debited from " + acct.bank, true], [pending ? "Waiting for bank confirmation" : x.credit ? "Credited" : "Credited to " + x.name.split(" ")[0], !pending]];
    return {
      cls: "full",
      html: `${appbar("Payment details", { right: `<button class="icon-btn" data-a="shareTx" aria-label="${t("common.share")}">${I("share", 18)}</button>` })}
      <div class="card center" style="display:grid;gap:8px;justify-items:center">
        ${F.avatar(x.name, "lg", { biz: ["bill", "recharge", "p2m"].includes(x.kind), verified: x.kind !== "self" })}
        <div class="h2">${x.credit ? "From" : "To"} ${esc(x.name)}</div><div class="small muted">${esc(x.vpa === "self" ? "Between your accounts" : x.vpa)}</div>
        <div class="amount" style="${failed ? "text-decoration:line-through;color:var(--ink-3)" : ""}">${F.inr(x.amount)}</div>
        <div>${failed ? `<span class="pill bad">${t("pay.failed")}</span>` : pending ? `<span class="pill warn">${t("pay.pending")}</span>` : `<span class="pill ok">${x.credit ? t("pay.received") : t("pay.success")}</span>`}</div>
        ${x.reason ? `<div class="small" style="color:var(--bad)">${esc(x.reason)}</div>` : ""}
      </div>
      <div class="card"><div class="timeline">${steps.map(([l, ok]) => `<div class="tl"><span class="d ${ok ? "" : "wait"}">${I(ok ? "check" : "clock", 12, 3)}</span><div style="font:700 13.5px var(--text)">${l}</div></div>`).join("")}</div></div>
      <div class="card"><dl class="kv">
        <dt>${x.kind === "offline" ? "Offline payment ID" : "UPI transaction ID"}</dt><dd>${esc(x.ref)} <button class="link" style="padding:0 0 0 6px" data-a="copyRef" data-v="${esc(x.ref)}">Copy</button></dd>
        <dt>${x.credit ? "Credited to" : "Debited from"}</dt><dd>${acct.bank} •• ${acct.masked}</dd>
        <dt>Date & time</dt><dd>${new Date(x.at).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</dd>
        ${x.note ? `<dt>Note</dt><dd>${esc(x.note)}</dd>` : ""}
      </dl></div>
      <div class="stack">
        ${!x.credit && ["p2p", "p2m"].includes(x.kind) ? `<button class="btn gold" data-a="payAgain" data-v="${x.id}">${failed ? "Try again" : "Pay again"}</button>` : ""}
        <button class="btn ghost" data-a="go" data-v="dispute" data-p="${x.id}">${I("help", 18)}Report a problem</button>
      </div>`,
    };
  };

  /* ── Dispute (UPI dispute & resolution) ──────────────────── */
  F.screens.dispute = (p) => {
    const x = F.state.txns.find((y) => y.id === p.id) || F.state.txns[0];
    const reasons = ["Money debited but payee didn't receive it", "Paid to the wrong person", "Merchant didn't deliver goods or service", "Debited twice for one payment", "I didn't make this payment"];
    p.reason = p.reason ?? 0;
    return {
      cls: "full",
      html: `${appbar("Report a problem")}
      <div class="card row">${F.avatar(x.name, "sm")}<div class="grow"><div style="font:800 14px var(--text)">${esc(x.name)}</div><div class="small muted">${F.inr(x.amount)} · ${F.when(x.at)} · ${esc(x.ref)}</div></div></div>
      <div class="eyebrow">What went wrong?</div>
      <div class="list" role="radiogroup">${reasons.map((r, i) => `<button class="li" role="radio" aria-checked="${p.reason === i}" data-a="disputeReason" data-v="${i}">
        <span class="lead" style="${p.reason === i ? "background:var(--gold-grad);color:var(--on-gold)" : ""}">${I(p.reason === i ? "check" : "info", 18)}</span><div class="grow t">${r}</div></button>`).join("")}</div>
      <div class="field"><label for="disputeText">Details (optional)</label><textarea id="disputeText" class="input" style="height:96px;padding:12px" maxlength="300" placeholder="Anything that helps your bank resolve this"></textarea></div>
      <div class="card flat small muted" style="line-height:1.55">Your complaint is raised with your bank through NPCI's UPI dispute system (UDIR). Failed debits are usually reversed automatically within T+1 working day. You'll get updates here and by SMS.</div>
      <button class="btn gold" data-a="submitDispute" data-v="${x.id}">Raise complaint</button>`,
    };
  };

  /* ── Notifications ───────────────────────────────────────── */
  F.screens.notifications = () => {
    const ns = F.state.notifications;
    return {
      cls: "full",
      html: `${appbar("Notifications", { right: `<button class="link" data-a="readAll">Mark all read</button>` })}
      <div class="list">${ns.map((n) => `<div class="li" style="${n.read ? "" : "background:color-mix(in srgb,var(--gold) 7%,transparent)"}">
        <span class="lead" style="${n.tone === "bad" ? "background:var(--bad-bg);color:var(--bad)" : ""}">${I(n.icon, 19)}</span>
        <div class="grow"><div class="t">${esc(n.title)}</div><div class="s" style="line-height:1.45">${esc(n.body)}</div><div class="s dim">${F.when(n.at)}</div></div></div>`).join("")}</div>`,
    };
  };

  /* ── Search ───────────────────────────────────────────────── */
  const FEATURES = [
    ["Scan & pay", "go", "scan", "scan"], ["Pay phone number", "go", "payPhone", "phone"], ["Bank transfer (account + IFSC)", "go", "bank", "bank"],
    ["Pay UPI ID", "go", "payUpi", "at"], ["Self transfer", "go", "self", "swap"], ["Check balance", "checkBalance", "", "wallet"],
    ["Request money", "go", "request", "request"], ["Split bills", "go", "split", "split"], ["My QR code", "go", "myqr", "qr"],
    ["Offline vault", "tab", "offline", "vault"], ["Autopay & mandates", "go", "autopay", "repeat"], ["Change UPI PIN", "go", "accounts", "key"],
    ["Payment limits", "go", "limits", "gauge"], ["Help & complaints", "go", "help", "help"], ["Security settings", "go", "security", "lock"],
  ];
  F.searchResults = (q) => {
    q = q.trim().toLowerCase();
    if (!q) return `<div class="eyebrow">Try “electricity”, “Ananya”, “bank transfer”</div>
      <div class="chips">${["Electricity", "Recharge", "Bank transfer", "My QR", "Offline"].map((s) => `<button class="chip" data-a="searchFill" data-v="${s}">${s}</button>`).join("")}</div>`;
    const people = F.state.contacts.filter((c) => (c.name + c.vpa + c.phone).toLowerCase().includes(q)).slice(0, 5);
    const biz = F.state.businesses.filter((c) => (c.name + c.vpa).toLowerCase().includes(q));
    const bills = F.billCats.filter((c) => (t("bill." + c.id) + c.id).toLowerCase().includes(q));
    const feats = FEATURES.filter((f) => f[0].toLowerCase().includes(q));
    const pays = F.state.txns.filter((x) => (x.name + x.note + x.ref).toLowerCase().includes(q)).slice(0, 4);
    const block = (title, html) => html ? `<div class="eyebrow group-label">${title}</div><div class="list">${html}</div>` : "";
    const out = block("People", people.map((c) => `<button class="li" data-a="go" data-v="thread" data-p="${c.id}">${F.avatar(c.name, "sm")}<div class="grow"><div class="t">${esc(c.name)}</div><div class="s">${esc(c.vpa)}</div></div></button>`).join("")) +
      block("Businesses", biz.map((b) => `<button class="li" data-a="payBiz" data-v="${b.id}">${F.avatar(b.name, "sm", { biz: true, glyph: b.glyph })}<div class="grow"><div class="t">${esc(b.name)}</div><div class="s">${esc(b.vpa)}</div></div></button>`).join("")) +
      block("Bills & recharges", bills.map((c) => `<button class="li" data-a="bill" data-v="${c.id}"><span class="lead">${I(c.icon, 19)}</span><div class="grow t">${t("bill." + c.id)}</div></button>`).join("")) +
      block("Features", feats.map((f) => `<button class="li" data-a="${f[1]}" data-v="${f[2]}"><span class="lead">${I(f[3], 19)}</span><div class="grow t">${f[0]}</div></button>`).join("")) +
      block("Payments", pays.map((x) => F.txRow(x)).join(""));
    if (out) return out;
    if (F.vpaOk(q)) return `<div class="list"><button class="li" data-a="payVpa" data-v="${esc(q)}"><span class="lead">${I("at", 19)}</span><div class="grow"><div class="t">Pay ${esc(q)}</div><div class="s">UPI ID</div></div></button></div>`;
    if (/^[6-9]\d{9}$/.test(q)) return `<div class="list"><button class="li" data-a="payPhoneNum" data-v="${q}"><span class="lead">${I("phone", 19)}</span><div class="grow"><div class="t">Pay +91 ${q}</div><div class="s">Phone number</div></div></button></div>`;
    return `<div class="card center muted">No results for “${esc(q)}”.</div>`;
  };
  F.screens.search = (p) => ({
    cls: "full",
    html: `<div class="appbar"><button class="icon-btn" data-a="back" aria-label="${t("common.back")}">${I("back", 18)}</button>
        <div class="search-box grow">${I("search", 18)}<input id="searchQ" type="search" placeholder="${t("home.search")}" value="${esc(p.q || "")}" data-in="search" aria-label="${t("common.search")}" autocomplete="off"></div></div>
      <div id="searchOut" class="stack">${F.searchResults(p.q || "")}</div>`,
    mount: (root) => { const i = F.$("#searchQ", root); i && i.focus({ preventScroll: true }); },
  });

  /* ── Insights ─────────────────────────────────────────────── */
  F.screens.insights = () => {
    const cats = [["Bills & utilities", 1384000, "bolt"], ["Rent & transfers", 1800000, "users"], ["Food & groceries", 762000, "store"], ["Shopping", 451000, "gift"], ["Travel", 224000, "car"]];
    const total = cats.reduce((a, c) => a + c[1], 0);
    return {
      cls: "full",
      html: `${appbar("Spending insights")}
      <div class="card"><div class="eyebrow">October so far</div><div class="amount tnum">${F.inr(total)}</div><div class="small muted" style="margin-top:4px">8% less than September at this point · Daily average ₹1,154</div></div>
      <div class="card stack">${cats.map(([n, v, ic]) => `<div class="row"><span class="lead" style="width:36px;height:36px;border-radius:12px;display:grid;place-items:center;background:var(--tile);color:var(--tile-ink)">${I(ic, 18)}</span>
        <div class="grow"><div class="row"><span class="grow" style="font:700 13.5px var(--text)">${n}</span><span class="tnum" style="font:800 13.5px var(--text)">${F.inrShort(v)}</span></div>
        <div style="height:8px;border-radius:4px;background:var(--bg-2);margin-top:6px;overflow:hidden"><i style="display:block;height:100%;width:${Math.round((v / total) * 100)}%;background:var(--gold-grad);border-radius:4px"></i></div></div></div>`).join("")}</div>
      <div class="eyebrow">Top payees</div>
      <div class="list">${["Vikram Sethi", "MetroPower Distribution", "Meera Nair"].map((n, i) => `<div class="li">${F.avatar(n, "sm")}<div class="grow t">${n}</div><div class="amt">${F.inrShort([1800000, 284000, 200000][i])}</div></div>`).join("")}</div>`,
    };
  };
})();

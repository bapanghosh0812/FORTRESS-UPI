/* Fortress preview — core: helpers, i18n, icons, router, sheets, toast, particles. */
(() => {
  "use strict";
  const F = (window.F = window.F || {});

  F.$ = (s, r = document) => r.querySelector(s);
  F.$$ = (s, r = document) => [...r.querySelectorAll(s)];
  F.esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  F.reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

  const money = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2, maximumFractionDigits: 2 });
  F.inr = (p) => money.format((p || 0) / 100);
  F.inrShort = (p) => F.inr(p).replace(/\.00$/, "");
  F.initials = (n) => String(n).split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join("");
  F.hash = (s) => [...String(s)].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  F.digits = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join("");
  F.uid = (p) => p + Date.now().toString(36) + F.digits(4);

  /* Premium avatar tones (navy, plum, teal, bronze, slate, forest) */
  const TONES = [["#13275A", "#2C4C9E"], ["#4A1D4F", "#8A3E7A"], ["#0D4D4A", "#1E8A7A"], ["#6B4211", "#B07A2A"], ["#2F3B52", "#5B6B8C"], ["#16452B", "#2F7D4F"], ["#5A1E2B", "#A23B52"]];
  F.avatar = (name, cls = "", opts = {}) => {
    const [a, b] = TONES[F.hash(name) % TONES.length];
    return `<span class="av ${cls} ${opts.biz ? "biz" : ""}" style="background:linear-gradient(135deg,${a},${b})" aria-hidden="true">${opts.glyph ? F.icon(opts.glyph, 20) : F.esc(F.initials(name))}${opts.verified ? `<span class="vbadge">${F.icon("check", 11, 3)}</span>` : ""}</span>`;
  };

  /* ── i18n ── */
  F.t = (key, vars) => {
    const dict = (F.i18n && F.i18n[F.state?.lang]) || {};
    let s = dict[key] ?? (F.i18n && F.i18n.en[key]) ?? key;
    if (vars) for (const k in vars) s = s.replaceAll(`{${k}}`, vars[k]);
    return s;
  };

  /* ── Icons (24px line set) ── */
  const P = {
    home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h14V9.5"/><path d="M10 20v-5h4v5"/>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/>',
    scan: '<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3"/><path d="M4 12h16"/>',
    qr: '<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h2v2h-2zM18 14h2M14 18h2M18 18h2v2"/>',
    vault: '<path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3z"/><circle cx="12" cy="10.5" r="1.7"/><path d="M12 12.2V15"/>',
    shield: '<path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.3 7.5 9.5 4.4-1.2 7.5-4.9 7.5-9.5V6L12 3z"/><path d="M8.8 12l2.2 2.2 4.4-4.4"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c1.2-3.4 3.7-5 6.5-5s5.3 1.6 6.5 5"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M18 15c1.8.7 3 2.3 3.6 5"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    close: '<path d="M6 6l12 12M18 6 6 18"/>',
    chev: '<path d="M9 6l6 6-6 6"/>',
    down: '<path d="M6 9l6 6 6-6"/>',
    eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    eyeoff: '<path d="M3 3l18 18"/><path d="M10.6 5.1A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6C3.9 8.4 2 12 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
    phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/>',
    bank: '<path d="M3 10 12 4l9 6"/><path d="M5 10v8M9.5 10v8M14.5 10v8M19 10v8"/><path d="M3 20h18"/>',
    at: '<circle cx="12" cy="12" r="4"/><path d="M16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.5 7.1"/>',
    swap: '<path d="M7 4 3 8l4 4"/><path d="M3 8h14"/><path d="M17 20l4-4-4-4"/><path d="M21 16H7"/>',
    wallet: '<path d="M4 7a2 2 0 0 1 2-2h11v4"/><path d="M4 7v11a2 2 0 0 0 2 2h14V9H6a2 2 0 0 1-2-2z"/><circle cx="16.5" cy="14.5" r="1.2"/>',
    request: '<path d="M12 20V8"/><path d="M6.5 13.5 12 8l5.5 5.5"/><path d="M5 4h14"/>',
    receive: '<path d="M12 4v12"/><path d="M6.5 10.5 12 16l5.5-5.5"/><path d="M5 20h14"/>',
    split: '<path d="M6 4v5a6 6 0 0 0 6 6 6 6 0 0 0 6-6V4"/><path d="M12 15v5"/>',
    nfc: '<path d="M6 8.5a5 5 0 0 1 0 7"/><path d="M9.5 6a8.5 8.5 0 0 1 0 12"/><path d="M13 3.5a12 12 0 0 1 0 17"/>',
    bt: '<path d="M7 7l10 10-5 4V3l5 4L7 17"/>',
    finger: '<path d="M12 11v4"/><path d="M8.5 8.8A4 4 0 0 1 16 11v1.5"/><path d="M5.6 7.2A7 7 0 0 1 19 11v2"/><path d="M8 12v1a8 8 0 0 0 1.5 4.7"/><path d="M16 15.5a10 10 0 0 1-.9 3.5"/><path d="M12 15a6 6 0 0 0 1 4"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    bolt: '<path d="M13 3 5 14h6l-1 7 8-11h-6l1-7z"/>',
    drop: '<path d="M12 3.5s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z"/>',
    flame: '<path d="M12 21a6 6 0 0 0 6-6c0-4-3-6-4-9-1.5 2-2 3-2 5-1-1-2-2-2-4-2 2-4 5-4 8a6 6 0 0 0 6 6z"/>',
    wifi: '<path d="M2.5 9a14 14 0 0 1 19 0"/><path d="M5.5 12.5a9.5 9.5 0 0 1 13 0"/><path d="M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19.2" r=".8"/>',
    wifioff: '<path d="M3 3l18 18"/><path d="M8.5 16a5 5 0 0 1 7 0"/><path d="M5.5 12.5a9.5 9.5 0 0 1 4.3-2.4M14.5 10.2a9.5 9.5 0 0 1 4 2.3"/><path d="M2.5 9a14 14 0 0 1 4.2-2.7M11 5.1A14 14 0 0 1 21.5 9"/>',
    tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8M12 17v4"/>',
    car: '<path d="M5 16V11l2-5h10l2 5v5"/><path d="M3 16h18v3H3z"/><circle cx="7.5" cy="13" r="1"/><circle cx="16.5" cy="13" r="1"/>',
    card: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M3 10h18M7 15h4"/>',
    mobile: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
    percent: '<path d="M19 5 5 19"/><circle cx="7" cy="7" r="2.5"/><circle cx="17" cy="17" r="2.5"/>',
    umbrella: '<path d="M3 12a9 9 0 0 1 18 0z"/><path d="M12 12v7a2 2 0 0 0 4 0"/>',
    building: '<rect x="5" y="3" width="14" height="18" rx="1"/><path d="M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2M11 21v-3h2v3"/>',
    grad: '<path d="M2 9 12 4l10 5-10 5z"/><path d="M6 11v5c3 2.5 9 2.5 12 0v-5"/>',
    repeat: '<path d="M17 2l4 4-4 4"/><path d="M3 11V9a3 3 0 0 1 3-3h15"/><path d="M7 22l-4-4 4-4"/><path d="M21 13v2a3 3 0 0 1-3 3H3"/>',
    chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    bell: '<path d="M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    share: '<circle cx="18" cy="5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="M8.3 10.8l7.4-4.3M8.3 13.2l7.4 4.3"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.7 3.7 5.7 3.7 9s-1.2 6.3-3.7 9c-2.5-2.7-3.7-5.7-3.7-9s1.2-6.3 3.7-9z"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 1-1 1.7V14"/><circle cx="12" cy="17" r=".8"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6"/><circle cx="12" cy="7.5" r=".8"/>',
    logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/><path d="M10 16l4-4-4-4M14 12H4"/>',
    block: '<circle cx="12" cy="12" r="9"/><path d="M5.6 5.6l12.8 12.8"/>',
    download: '<path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    alert: '<path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4"/><circle cx="12" cy="17" r=".8"/>',
    chat: '<path d="M4 5h16v11H9l-5 4z"/>',
    key: '<circle cx="8" cy="15" r="4"/><path d="M11 12l8-8M16 7l2 2M14 9l2 2"/>',
    chip: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
    sim: '<path d="M7 3h7l4 4v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"/><rect x="9" y="11" width="6" height="6" rx="1"/>',
    gauge: '<path d="M4 18a8 8 0 1 1 16 0"/><path d="M12 18l4-6"/>',
    pause: '<path d="M9 5v14M15 5v14"/>',
    play: '<path d="M7 4l12 8-12 8z"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    star: '<path d="M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z"/>',
    doc: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12h6M9 16h6"/>',
    store: '<path d="M4 9l1.5-5h13L20 9"/><path d="M4 9a2.7 2.7 0 0 0 5.3 0 2.7 2.7 0 0 0 5.4 0 2.7 2.7 0 0 0 5.3 0"/><path d="M5 11v9h14v-9"/>',
    gift: '<rect x="3" y="8" width="18" height="5" rx="1"/><path d="M5 13v8h14v-8M12 8v13M12 8S9 3 7 5s5 3 5 3 5-1 3-3-3 3-3 3"/>',
    torch: '<path d="M13 3 5 14h6l-1 7 8-11h-6l1-7z"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 17l-5-5-8 8"/>',
    contacts: '<rect x="4" y="3" width="16" height="18" rx="2"/><circle cx="12" cy="10" r="3"/><path d="M8 17c.8-2 2.2-3 4-3s3.2 1 4 3"/>',
  };
  F.icon = (n, s = 22, w = 1.8) => `<svg class="ic" width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[n] || P.info}</svg>`;

  /* ── Router ── */
  F.nav = { stack: [{ name: "home", params: {} }] };
  F.timers = [];
  F.later = (ms, fn) => { const id = setTimeout(fn, F.reduceMotion ? Math.min(ms, 80) : ms); F.timers.push(id); return id; };
  const clearTimers = () => { F.timers.forEach(clearTimeout); F.timers = []; };
  F.top = () => F.nav.stack[F.nav.stack.length - 1];

  F.go = (name, params = {}, dir = "fwd") => { clearTimers(); F.closeSheet(true); F.nav.stack.push({ name, params }); F.render(dir); };
  F.replace = (name, params = {}, dir = "fade") => { clearTimers(); F.closeSheet(true); F.nav.stack[F.nav.stack.length - 1] = { name, params }; F.render(dir); };
  F.back = () => {
    clearTimers(); F.closeSheet(true);
    if (F.nav.stack.length > 1) F.nav.stack.pop(); else F.nav.stack = [{ name: "home", params: {} }];
    F.render("back");
  };
  F.tabTo = (name) => { clearTimers(); F.closeSheet(true); F.nav.stack = [{ name, params: {} }]; F.render("fade"); };
  F.refresh = () => F.render("none");

  F.render = (dir = "fade") => {
    const device = F.$("#device"), viewport = F.$("#viewport");
    const top = F.top();
    const screen = F.screens[top.name] || F.screens.home;
    const old = F.$(".page", viewport);
    const keep = dir === "none" && old ? old.scrollTop : 0;
    const v = screen(top.params);
    viewport.innerHTML = `<div class="page ${v.cls || ""} ${dir === "none" ? "" : "enter-" + dir}">${v.html}</div>`;
    const page = F.$(".page", viewport);
    if (keep) page.scrollTop = keep;
    F.$("#tabbar").hidden = !v.tab;
    device.classList.toggle("hero-top", !!v.hero);
    const edge = v.hero ? 230 : 6, onScroll = () => device.classList.toggle("scrolled", page.scrollTop > edge);
    page.addEventListener("scroll", onScroll, { passive: true }); onScroll();
    F.renderTabs(v.tab);
    F.renderStatus();
    if (v.mount) v.mount(F.$(".page", viewport), top.params);
  };

  F.renderTabs = (active) => {
    if (!active) return;
    const t = (id, icon) => `<button class="tab" data-a="tab" data-v="${id}" ${active === id ? 'aria-current="page"' : ""}>${F.icon(icon)}${F.t("nav." + id)}</button>`;
    F.$("#tabbar").innerHTML = t("home", "home") + t("history", "history") +
      `<button class="fab" data-a="go" data-v="scan" aria-label="${F.t("nav.scan")}">${F.icon("scan", 26, 2.2)}</button>` +
      t("offline", "vault") + t("profile", "user");
  };

  F.renderStatus = () => {
    const on = F.state.online;
    const bars = [4, 7, 10, 13].map((h, i) => `<rect x="${i * 4.5}" y="${13 - h}" width="3" height="${h}" rx="1" fill="currentColor" opacity="${on ? 1 : 0.25}"/>`).join("");
    F.$("#sbIcons").innerHTML = (on ? "" : `<span class="nosvc">${F.t("status.noService")}</span>`) +
      `<svg width="17" height="13" viewBox="0 0 17 13" aria-hidden="true">${bars}</svg>` +
      (on ? `<svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor" aria-hidden="true"><path d="M8 2.5c2.3 0 4.4.9 6 2.4l1.2-1.3A10.2 10.2 0 0 0 8 .7 10.2 10.2 0 0 0 .8 3.6L2 4.9a8.4 8.4 0 0 1 6-2.4zm0 3.6c1.4 0 2.7.5 3.6 1.4l1.3-1.3A7 7 0 0 0 8 4.3a7 7 0 0 0-4.9 1.9l1.3 1.3A5.2 5.2 0 0 1 8 6.1zm0 3.5c-.6 0-1.1.2-1.4.6L8 11.6l1.4-1.4c-.4-.4-.9-.6-1.4-.6z"/></svg>` : "") +
      `<svg width="27" height="13" viewBox="0 0 27 13" aria-hidden="true"><rect x=".5" y=".5" width="23" height="12" rx="3.5" fill="none" stroke="currentColor" opacity=".45"/><rect x="2" y="2" width="16" height="9" rx="2" fill="currentColor"/><rect x="24.5" y="4.5" width="1.6" height="4" rx=".8" fill="currentColor" opacity=".45"/></svg>`;
  };

  /* ── Sheets & toast ── */
  F.sheetCb = null;
  F.sheet = (html, cb) => {
    F.sheetCb = cb || null;
    const s = F.$("#sheet");
    s.innerHTML = `<div class="grabber"></div>${html}`;
    s.scrollTop = 0;
    F.$("#sheetLayer").classList.add("open");
    const first = s.querySelector("input, button");
    if (first) setTimeout(() => first.focus({ preventScroll: true }), 120);
  };
  F.sheetBody = (html) => { F.$("#sheet").innerHTML = `<div class="grabber"></div>${html}`; };
  F.closeSheet = (silent) => { F.$("#sheetLayer").classList.remove("open"); if (!silent) F.sheetCb = null; };

  F.confirm = ({ title, body, yes, danger }, onYes) => {
    F.sheet(`<div class="h2">${title}</div><p class="muted" style="margin:0;line-height:1.55">${body}</p>
      <div class="btn-row"><button class="btn ghost" data-a="sheetClose">${F.t("common.cancel")}</button>
      <button class="btn ${danger ? "danger" : "gold"}" data-a="sheetYes">${yes}</button></div>`, onYes);
  };

  let toastTimer = 0;
  F.toast = (text, icon = "check") => {
    const t = F.$("#toast");
    t.innerHTML = `${F.icon(icon, 20)}<span>${text}</span>`;
    t.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("show"), 3200);
  };

  /* Clipboard write must run inside the click handler; if the host refuses it,
     show the text selected so the viewer can copy it themselves. */
  F.copy = (text, label) => {
    const ok = () => F.toast(F.t("toast.copied", { x: F.esc(label || text) }), "copy");
    const manual = () => {
      F.sheet(`<div class="h2">Copy ${F.esc(label || "text")}</div>
        <textarea id="copyBox" class="input" style="height:${text.length > 90 ? 180 : 64}px;padding:12px;font:600 12.5px/1.5 var(--text);resize:none" readonly aria-label="Text to copy">${F.esc(text)}</textarea>
        <div class="small muted">The text is selected. Copy it with your keyboard or long-press.</div>
        <button class="btn gold" data-a="sheetClose">${F.t("common.done")}</button>`);
      setTimeout(() => { const b = F.$("#copyBox"); if (b) { b.focus(); b.select(); } }, 160);
    };
    try { navigator.clipboard.writeText(text).then(ok, manual); } catch (e) { manual(); }
  };

  /* ── Particles: success bloom (zero allocation per frame) ── */
  let raf = 0;
  F.burst = (el, palette = ["#0FA36F", "#F0CF7E", "#FFFFFF", "#D7A54A", "#4BE3A9"]) => {
    if (F.reduceMotion || !el) return;
    const device = F.$("#device"), fx = F.$("#fx"), g = fx.getContext("2d");
    const dr = device.getBoundingClientRect(), er = el.getBoundingClientRect();
    const dpr = Math.min(devicePixelRatio || 1, 2);
    fx.width = dr.width * dpr; fx.height = dr.height * dpr; g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const cx = er.left - dr.left + er.width / 2, cy = er.top - dr.top + er.height / 2, n = 170;
    const x = new Float32Array(n), y = new Float32Array(n), vx = new Float32Array(n), vy = new Float32Array(n), life = new Float32Array(n), ttl = new Float32Array(n), r = new Float32Array(n), c = new Uint8Array(n);
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2, sp = 220 + Math.random() * 520;
      x[i] = cx; y[i] = cy; vx[i] = Math.cos(a) * sp; vy[i] = Math.sin(a) * sp - 240;
      ttl[i] = life[i] = 0.9 + Math.random() * 1.1; r[i] = 1.2 + Math.random() * 3.2; c[i] = (Math.random() * palette.length) | 0;
    }
    let last = performance.now();
    cancelAnimationFrame(raf);
    const frame = (t) => {
      const dt = Math.min((t - last) / 1000, 1 / 30); last = t;
      g.clearRect(0, 0, dr.width, dr.height);
      let alive = false; const drag = 1 - 1.8 * dt;
      for (let i = 0; i < n; i++) {
        if (life[i] <= 0) continue;
        vx[i] *= drag; vy[i] = vy[i] * drag + 900 * dt; x[i] += vx[i] * dt; y[i] += vy[i] * dt; life[i] -= dt;
        if (life[i] <= 0) continue; alive = true;
        const k = life[i] / ttl[i]; g.globalAlpha = k * k; g.fillStyle = palette[c[i]];
        g.beginPath(); g.arc(x[i], y[i], r[i] * (0.4 + 0.6 * k), 0, Math.PI * 2); g.fill();
      }
      g.globalAlpha = 1;
      if (alive) raf = requestAnimationFrame(frame); else g.clearRect(0, 0, dr.width, dr.height);
    };
    raf = requestAnimationFrame(frame);
  };

  /* ── Keypad entry helpers ── */
  F.keypad = (decimal = true) => `<div class="keypad" role="group" aria-label="${F.t("a11y.keypad")}">${["1", "2", "3", "4", "5", "6", "7", "8", "9", decimal ? "." : "", "0", "del"].map((k) =>
    k === "" ? "<span></span>" : `<button class="key" data-a="key" data-v="${k}" aria-label="${k === "del" ? F.t("a11y.delete") : k}">${k === "del" ? "⌫" : k}</button>`).join("")}</div>`;
  F.applyKey = (cur, k, maxInt = 7) => {
    if (k === "del") return cur.slice(0, -1);
    if (k === "." && (cur.includes(".") || !cur)) return cur;
    if (cur.includes(".") && cur.split(".")[1].length >= 2) return cur;
    if (!cur.includes(".") && k !== "." && cur.length >= maxInt) return cur;
    if (cur === "0" && k !== ".") return k;
    return cur + k;
  };
  F.entryPaise = (t) => {
    if (!t || t === ".") return 0;
    const [r, f = ""] = t.split(".");
    return parseInt(r || "0", 10) * 100 + parseInt((f + "00").slice(0, 2), 10);
  };
  F.entryDisplay = (t) => {
    if (!t) return "0";
    const [r, f] = t.split(".");
    const grouped = new Intl.NumberFormat("en-IN").format(parseInt(r || "0", 10));
    return f !== undefined ? `${grouped}.${f}` : grouped;
  };

  /* ── Time ── */
  F.when = (ts) => {
    const d = new Date(ts), now = new Date();
    const day = Math.floor((new Date(now.toDateString()) - new Date(d.toDateString())) / 864e5);
    const time = d.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
    if (day === 0) return `${F.t("time.today")}, ${time}`;
    if (day === 1) return `${F.t("time.yesterday")}, ${time}`;
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short" }) + `, ${time}`;
  };
  F.dayGroup = (ts) => {
    const d = new Date(ts), now = new Date();
    const day = Math.floor((new Date(now.toDateString()) - new Date(d.toDateString())) / 864e5);
    return day === 0 ? F.t("time.today") : day === 1 ? F.t("time.yesterday") : d.toLocaleDateString("en-IN", { day: "numeric", month: "long" });
  };
})();

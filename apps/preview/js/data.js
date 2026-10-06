/* Fortress preview — example data and domain rules (mirror of the Kotlin/Go logic).
   All people, handles, billers and operators are fictional. */
(() => {
  "use strict";
  const F = window.F;
  const H = 36e5, D = 864e5, now = Date.now();

  /* Offline note planner & selector — same algorithm as core/offline (Kotlin). */
  F.planNotes = (total) => {
    const change = [1000, 2000, 2000, 5000, 10000], ladder = [50000, 20000, 10000, 5000, 2000, 1000];
    const out = []; let r = total;
    for (const d of change) if (r >= d) { out.push(d); r -= d; }
    for (const d of ladder) while (r >= d) { out.push(d); r -= d; }
    return out.sort((a, b) => b - a);
  };
  F.selectNotes = (notes, amount, maxNotes = 16) => {
    if (amount <= 0 || !notes.length) return null;
    const total = notes.reduce((a, b) => a + b, 0);
    if (total < amount) return null;
    const gcd = (a, b) => (b ? gcd(b, a % b) : a);
    const unit = notes.reduce((g, n) => gcd(g, n), 0);
    const maxSum = total / unit, target = Math.ceil(amount / unit), n = notes.length, INF = 1e9;
    const best = [new Array(maxSum + 1).fill(INF)]; best[0][0] = 0;
    for (let i = 1; i <= n; i++) {
      const d = notes[i - 1] / unit, prev = best[i - 1], cur = new Array(maxSum + 1);
      for (let s = 0; s <= maxSum; s++) { let b = prev[s]; if (s >= d && prev[s - d] !== INF) b = Math.min(b, prev[s - d] + 1); cur[s] = b; }
      best.push(cur);
    }
    let chosen = -1;
    for (let s = target; s <= maxSum; s++) if (best[n][s] <= maxNotes) { chosen = s; break; }
    if (chosen < 0) return null;
    const picked = []; let s = chosen;
    for (let i = n; i >= 1; i--) { if (best[i][s] === best[i - 1][s]) continue; picked.push(i - 1); s -= notes[i - 1] / unit; }
    picked.sort((a, b) => notes[b] - notes[a]);
    let rem = amount;
    return picked.map((i) => { const applied = Math.min(notes[i], rem); rem -= applied; return { index: i, denom: notes[i], applied }; });
  };

  let saved = {};
  try { saved = JSON.parse(localStorage.getItem("fortress.prefs") || "{}"); } catch (e) { saved = {}; }

  F.state = {
    lang: saved.lang || "en",
    theme: saved.theme || "pearl",
    online: true,
    hideBalance: true,
    appLock: true,
    biometric: true,
    autoLock: "1 minute",
    spamShield: true,
    payeeWarnings: true,
    notif: { payments: true, requests: true, bills: true, security: true, offline: true, offers: false },
    user: { name: "Aarav Mehta", vpa: "aarav@fortress", phone: "+91 98765 43210", vpas: ["aarav@fortress", "9876543210@fortress"] },
    accounts: [
      { id: "a1", bank: "Fortress Bank", masked: "4402", type: "Savings", primary: true, balance: 18425500, ifsc: "FTRS0000402" },
      { id: "a2", bank: "Harbor Co-operative Bank", masked: "1187", type: "Savings", primary: false, balance: 6230000, ifsc: "HRBC0001187" },
    ],
    /* Limits come from the server policy (NPCI/bank rules), never hard-coded in the client. */
    limits: { dailyUpi: 10000000, usedToday: 189000, perTxnUpi: 10000000, offlineWallet: 500000, offlinePerTxn: 100000, dailyCount: 20, countToday: 4 },
    vault: F.planNotes(200000),
    contacts: [
      ["Ananya Rao", "ananya.rao@fortresspsp", "98201 11842"], ["Vikram Sethi", "vsethi@fortresspsp", "99300 22719"],
      ["Meera Nair", "meera.n@harborbank", "98450 33126"], ["Kabir Das", "kabir@fortresspsp", "97690 44508"],
      ["Rohan Iyer", "rohan.iyer@fortresspsp", "98190 55831"], ["Zara Khan", "zara.k@harborbank", "99870 66204"],
      ["Priya Sharma", "priya.s@fortresspsp", "98110 77395"], ["Arjun Malhotra", "arjun.m@harborbank", "99580 88017"],
      ["Neha Kapoor", "neha.kapoor@fortresspsp", "98990 99462"], ["Ishaan Verma", "ishaan@fortresspsp", "97170 10388"],
      ["Sana Qureshi", "sana.q@harborbank", "98330 21954"], ["Dev Patel", "devpatel@fortresspsp", "99090 32671"],
    ].map(([name, vpa, phone], i) => ({ id: "c" + i, name, vpa, phone: "+91 " + phone })),
    businesses: [
      { id: "b1", name: "Asha Kirana Store", vpa: "asha.store@fortresspsp", glyph: "store" },
      { id: "b2", name: "Chai Point Café", vpa: "chaipoint@harborbank", glyph: "store" },
      { id: "b3", name: "CityCare Pharmacy", vpa: "citycare@fortresspsp", glyph: "plus" },
      { id: "b4", name: "Metro Rail Card", vpa: "metrorail@fortresspsp", glyph: "card" },
    ],
    txns: [
      { id: "t1", name: "Ananya Rao", vpa: "ananya.rao@fortresspsp", note: "Dinner split", amount: 125000, credit: true, status: "success", kind: "p2p", at: now - 2 * H, ref: "627114820933" },
      { id: "t2", name: "MetroPower Distribution", vpa: "metropower@bbps", note: "Electricity · Consumer 4410 2291", amount: 284000, credit: false, status: "success", kind: "bill", at: now - 5 * H, ref: "627114702201" },
      { id: "t3", name: "Asha Kirana Store", vpa: "asha.store@fortresspsp", note: "Offline · Tap & Pay", amount: 7300, credit: false, status: "settled", kind: "offline", at: now - 9 * H, ref: "OFF-8C21F0" },
      { id: "t4", name: "Vikram Sethi", vpa: "vsethi@fortresspsp", note: "Rent share", amount: 1800000, credit: false, status: "success", kind: "p2p", at: now - 26 * H, ref: "627013377410" },
      { id: "t5", name: "Nova Mobile Prepaid", vpa: "novamobile@bbps", note: "Recharge · 98765 43210 · ₹479 plan", amount: 47900, credit: false, status: "success", kind: "recharge", at: now - 30 * H, ref: "627013090124" },
      { id: "t6", name: "Chai Point Café", vpa: "chaipoint@harborbank", note: "Merchant QR", amount: 14000, credit: false, status: "success", kind: "p2m", at: now - 2 * D, ref: "626912881036" },
      { id: "t7", name: "Kabir Das", vpa: "kabir@fortresspsp", note: "Movie tickets", amount: 64000, credit: false, status: "failed", kind: "p2p", at: now - 2 * D - 3 * H, ref: "626912450092", reason: "Bank server timeout. Money was not debited." },
      { id: "t8", name: "Salary · Fortress Labs", vpa: "payroll@fortressbank", note: "NEFT credit", amount: 14200000, credit: true, status: "success", kind: "bank", at: now - 4 * D, ref: "NEFT0049921" },
      { id: "t9", name: "Fortress Bank → Harbor Co-op", vpa: "self", note: "Self transfer", amount: 2500000, credit: false, status: "success", kind: "self", at: now - 5 * D, ref: "626617220455" },
      { id: "t10", name: "Meera Nair", vpa: "meera.n@harborbank", note: "Birthday gift", amount: 200000, credit: false, status: "success", kind: "p2p", at: now - 6 * D, ref: "626511904473" },
    ],
    requests: [
      { id: "r1", name: "Vikram Sethi", vpa: "vsethi@fortresspsp", amount: 45000, note: "Lunch at Bandra", at: now - 40 * 60e3, known: true },
      { id: "r2", name: "Unknown requester", vpa: "kyc.refund.help@harborbank", amount: 999900, note: "KYC refund — approve to receive", at: now - 3 * H, known: false },
    ],
    outgoing: [],
    mandates: [
      { id: "m1", name: "StreamMax Premium", glyph: "tv", amount: 64900, freq: "Monthly", next: "12 Oct", status: "active", upto: false },
      { id: "m2", name: "Growth Index Fund SIP", glyph: "chart", amount: 500000, freq: "Monthly", next: "5 Nov", status: "active", upto: false },
      { id: "m3", name: "MetroPower bill autopay", glyph: "bolt", amount: 300000, freq: "When billed", next: "18 Oct", status: "active", upto: true },
      { id: "m4", name: "CloudBox storage", glyph: "doc", amount: 13000, freq: "Monthly", next: "—", status: "paused", upto: false },
    ],
    notifications: [
      { id: "n1", icon: "receive", title: "₹1,250 received from Ananya Rao", body: "Credited to Fortress Bank •• 4402", at: now - 2 * H, read: false },
      { id: "n2", icon: "alert", title: "Possible fraud request blocked", body: "A request for ₹9,999 asking you to 'approve to receive' was flagged. You never need a PIN to receive money.", at: now - 3 * H, read: false, tone: "bad" },
      { id: "n3", icon: "bolt", title: "Electricity bill paid", body: "MetroPower · ₹2,840 · UPI ref 627114702201", at: now - 5 * H, read: true },
      { id: "n4", icon: "vault", title: "Offline payment settled", body: "₹73 to Asha Kirana Store · ₹7 change returned to your bank", at: now - 8 * H, read: true },
      { id: "n5", icon: "shield", title: "Sign-in attempt from another phone blocked", body: "Your account works only on this device. No action needed.", at: now - D, read: true },
    ],
    complaints: [],
    blocked: [],
  };

  F.savePrefs = () => {
    try { localStorage.setItem("fortress.prefs", JSON.stringify({ lang: F.state.lang, theme: F.state.theme })); } catch (e) { /* storage unavailable */ }
  };

  F.primary = () => F.state.accounts.find((a) => a.primary) || F.state.accounts[0];
  F.account = (id) => F.state.accounts.find((a) => a.id === id) || F.primary();
  F.vaultTotal = () => F.state.vault.reduce((a, b) => a + b, 0);
  F.contact = (id) => F.state.contacts.find((c) => c.id === id);

  /* Bills & recharge catalogue */
  F.billCats = [
    { id: "mobile", icon: "mobile" }, { id: "electricity", icon: "bolt" }, { id: "dth", icon: "tv" }, { id: "fastag", icon: "car" },
    { id: "card", icon: "card" }, { id: "broadband", icon: "wifi" }, { id: "water", icon: "drop" }, { id: "gas", icon: "flame" },
    { id: "loan", icon: "percent" }, { id: "insurance", icon: "umbrella" }, { id: "tax", icon: "building" }, { id: "education", icon: "grad" },
  ];
  F.billers = {
    electricity: ["MetroPower Distribution", "Coastal Electric Supply", "Northern Grid Power"],
    dth: ["StarDish DTH", "SkyView TV"], fastag: ["Fortress Bank FASTag", "Harbor FASTag"],
    card: ["Fortress Credit Card", "Harbor Platinum Card"], broadband: ["SkyNet Fiber", "LinkHome Broadband"],
    water: ["CityWater Board", "Jal Seva Nigam"], gas: ["IndiFlame LPG", "CityGas Pipeline"],
    loan: ["Fortress Home Loan", "QuickCredit Finance"], insurance: ["SecureLife Insurance", "ShieldCare Health"],
    tax: ["Municipal Corporation Property Tax"], education: ["Greenfield Public School", "Apex University"],
  };
  F.consumerLabel = { electricity: "Consumer number", dth: "Subscriber ID", fastag: "Vehicle number", card: "Card number (last 4) & mobile", broadband: "Account ID", water: "Connection number", gas: "Consumer ID", loan: "Loan account number", insurance: "Policy number", tax: "Property ID", education: "Student ID" };
  F.operators = ["Nova Mobile", "BharatTel", "Zing Mobile", "AirLink"];
  F.plans = {
    Popular: [[29900, "28 days", "2 GB/day · Unlimited calls · 100 SMS/day"], [47900, "56 days", "1.5 GB/day · Unlimited calls"], [71900, "84 days", "2 GB/day · Unlimited calls · OTT pack"], [299900, "365 days", "2.5 GB/day · Unlimited calls"]],
    Data: [[1900, "1 day", "1 GB data add-on"], [6100, "Plan validity", "6 GB data add-on"], [18100, "30 days", "30 GB data add-on"]],
    "Top-up": [[1000, "—", "₹7.47 talk-time"], [5000, "—", "₹39.37 talk-time"], [10000, "—", "₹81.75 talk-time"]],
  };

  F.vpaOk = (v) => /^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,64}$/.test(v);
  F.ifscOk = (v) => /^[A-Z]{4}0[A-Z0-9]{6}$/.test(v);
  F.ifscBank = (v) => ({ FTRS: "Fortress Bank", HRBC: "Harbor Co-operative Bank", NOVA: "Nova Small Finance Bank", SUNB: "Sunrise Bank" }[v.slice(0, 4)] || null);
  /* Directory lookup (simulates the PSP's validate-address / mobile-mapper call). */
  F.lookupVpa = (v) => {
    const c = [...F.state.contacts, ...F.state.businesses].find((x) => x.vpa.toLowerCase() === v.toLowerCase());
    if (c) return { name: c.name, verified: true };
    const local = v.split("@")[0].replace(/[._-]+/g, " ").replace(/\d+/g, "").trim();
    return local ? { name: local.replace(/\b\w/g, (m) => m.toUpperCase()), verified: true } : { name: "UPI user", verified: true };
  };
  F.lookupPhone = (digits) => {
    const c = F.state.contacts.find((x) => x.phone.replace(/\D/g, "").endsWith(digits));
    if (c) return { name: c.name, vpa: c.vpa };
    return { name: "R••••• K••••", vpa: digits + "@harborbank" };
  };
})();

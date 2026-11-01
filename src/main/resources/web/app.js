"use strict";
/*
 * Ledger Console front end. No frameworks or libraries: plain DOM, SVG charts, and
 * the browser's own WebCrypto for proof verification.
 *
 * Structure:
 *   helpers and API      formatting, fetch wrapper
 *   crypto               SHA-256 Merkle verification and Ed25519, done in THIS browser
 *   router               one view at a time; each view polls only while visible
 *   customer / operator / regulator / simulator views
 */

// ============================== helpers and API ==============================
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const wholeMoney = new Intl.NumberFormat("en-IE", { style: "currency", currency: "EUR", maximumFractionDigits: 0 });
const eur = minor => {
  const amount = typeof minor === "number" ? BigInt(Math.round(minor)) : BigInt(minor ?? 0);
  const absolute = amount < 0n ? -amount : amount;
  return `${amount < 0n ? "-" : ""}${wholeMoney.format(absolute / 100n)}.${String(absolute % 100n).padStart(2, "0")}`;
};
const odds = h => (h / 100).toFixed(2);
const int = new Intl.NumberFormat("en-IE");
const short = h => h ? h.slice(0, 10) + "…" : "–";
const toMinor = v => {
  const match = /^([+-]?)(\d+)(?:\.(\d{0,2}))?$/.exec(String(v).trim());
  if (!match) throw new Error("Enter an amount with at most two decimal places.");
  const absolute = BigInt(match[2]) * 100n + BigInt((match[3] || "").padEnd(2, "0"));
  const amount = match[1] === "-" ? -absolute : absolute;
  if (amount > 9223372036854775807n || amount < -9223372036854775808n) {
    throw new Error("Amount exceeds the supported range.");
  }
  return amount.toString();
};
const timeOf = iso => new Date(iso).toLocaleTimeString([], { hour12: false });
const uuid = () => {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const value = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
};

/** Call the API. Resolves with the JSON body; rejects with {status, error, message} for non-2xx. */
async function api(path, { method = "GET", params } = {}) {
  const body = params ? new URLSearchParams(params) : undefined;
  const res = await fetch("/api/" + path, { method, body });
  if (res.ok) return res.json();
  const data = await res.json().catch(() => ({}));
  throw { status: res.status, error: data.error || "error", message: data.message || res.statusText };
}
const say = (el, ok, html) => { el.className = el.className.replace(/\b(ok|bad)\b/g, "").trim() + (ok === null ? "" : ok ? " ok" : " bad"); el.innerHTML = html; };
const errText = e => esc(e.message || String(e));

/** Polling must not restart keyboard navigation when the same control still exists. */
function renderControls(el, html, keys) {
  const selector = keys.map(k => `[data-${k}]`).join("");
  const active = document.activeElement;
  const identity = el.contains(active) && active.matches(selector) ? keys.map(k => active.getAttribute(`data-${k}`)) : null;
  el.innerHTML = html;
  if (identity) {
    const replacement = Array.from(el.querySelectorAll(selector)).find(b => keys.every((k, i) => b.getAttribute(`data-${k}`) === identity[i]));
    replacement?.focus({ preventScroll: true });
  }
}

// ============================== crypto: verification in the browser ==============================
// These mirror Hashes.kt and MerkleVerify (RFC 9162). The server supplies evidence;
// every check below is computed here, so the verdict does not rely on the server.
const b64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
const hex = b => Array.from(b, x => x.toString(16).padStart(2, "0")).join("");
const fromHex = h => Uint8Array.from(h.match(/../g).map(x => parseInt(x, 16)));
const sameBytes = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const WEB_LEDGER_ID = "sportsbook-web";

async function sha256(...parts) {
  const buf = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { buf.set(p, o); o += p.length; }
  return new Uint8Array(await crypto.subtle.digest("SHA-256", buf));
}
const leafHash = bytes => sha256(new Uint8Array([0]), bytes);
const nodeHash = (l, r) => sha256(new Uint8Array([1]), l, r);
// Indexes can exceed 2^31, so use arithmetic rather than JavaScript's 32-bit bit operators.
const odd = n => n % 2 === 1;
const half = n => Math.floor(n / 2);

/** RFC 9162 inclusion: the root implied by a leaf and its audit path, or null if the proof is malformed. */
async function inclusionRoot(leaf, index, size, path) {
  if (index < 0 || index >= size) return null;
  let fn = index, sn = size - 1, r = leaf;
  for (const p of path) {
    if (sn === 0) return null;
    if (odd(fn) || fn === sn) {
      r = await nodeHash(p, r);
      while (!odd(fn) && fn !== 0) { fn = half(fn); sn = half(sn); }
    } else r = await nodeHash(r, p);
    fn = half(fn); sn = half(sn);
  }
  return sn === 0 ? r : null;
}

/** RFC 9162 consistency: true if the size-m tree with oldRoot is a prefix of the size-n tree with newRoot. */
async function consistent(m, n, oldRoot, newRoot, proof) {
  if (m <= 0 || m > n) return false;
  if (m === n) return proof.length === 0 && sameBytes(oldRoot, newRoot);
  if (!proof.length) return false;
  // If the old tree is a perfect power of two, its root is itself a node of the new tree.
  const path = Number.isInteger(Math.log2(m)) ? [oldRoot, ...proof] : proof;
  let fn = m - 1, sn = n - 1;
  while (odd(fn)) { fn = half(fn); sn = half(sn); }
  let fr = path[0], sr = path[0];
  for (const c of path.slice(1)) {
    if (sn === 0) return false;
    if (odd(fn) || fn === sn) {
      fr = await nodeHash(c, fr); sr = await nodeHash(c, sr);
      while (!odd(fn) && fn !== 0) { fn = half(fn); sn = half(sn); }
    } else sr = await nodeHash(sr, c);
    fn = half(fn); sn = half(sn);
  }
  return sameBytes(fr, oldRoot) && sameBytes(sr, newRoot) && sn === 0;
}

/** Parse TreeHead.signingBytes(), so we can see for ourselves what the signature commits to. */
function parseTreeHead(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let o = 0;
  const need = n => { if (n < 0 || o + n > bytes.length) throw new Error("malformed signed tree head"); };
  const str = () => {
    need(4); const n = v.getInt32(o); o += 4; need(n);
    const s = new TextDecoder("utf-8", { fatal: true }).decode(bytes.slice(o, o + n)); o += n; return s;
  };
  const long = () => { need(8); const x = v.getBigInt64(o); o += 8; return Number(x); };
  const dig = () => { need(32); const d = bytes.slice(o, o + 32); o += 32; return d; };
  const head = { tag: str(), ledgerId: str(), treeSize: long(), root: dig(), chainHead: dig(), timestampMicros: long() };
  if (o !== bytes.length) throw new Error("trailing bytes in signed tree head");
  return head;
}

/** Decode EntryCodec v1 without losing precision in signed 64-bit amounts. */
function parseEntry(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let o = 0;
  const need = n => { if (n < 0 || n > bytes.length - o) throw new Error("Truncated entry bytes"); };
  const i32 = () => { need(4); const n = v.getInt32(o); o += 4; return n; };
  const long = () => { need(8); const n = v.getBigInt64(o); o += 8; return n; };
  const raw = n => { need(n); const b = bytes.slice(o, o + n); o += n; return b; };
  const str = (max = 65536) => {
    const n = i32();
    if (n < 0 || n > max) throw new Error("Entry string length out of range");
    return new TextDecoder("utf-8", { fatal: true }).decode(raw(n));
  };
  const count = () => { const n = i32(); if (n < 0 || n > 64) throw new Error("Entry count out of range"); return n; };
  if (i32() !== 0x50414d4c || raw(1)[0] !== 1) throw new Error("Unsupported entry format");
  const seq = long(), timestampMicros = long(), prevChainHash = hex(raw(32)), txId = hex(raw(16));
  const key = str(), kind = str();
  const postings = Array.from({ length: count() }, () => ({ account: str(), currency: str(16), amount: long() }));
  const metadata = Array.from({ length: count() }, () => ({ key: str(), value: str() }));
  if (o !== bytes.length) throw new Error("Trailing entry bytes");
  return { seq, timestampMicros, prevChainHash, txId, key, kind, postings, metadata };
}

/** Ed25519 signature check with WebCrypto. Returns true/false, or null if this browser lacks Ed25519. */
async function verifySignature(publicKeyB64, signatureB64, messageBytes) {
  try {
    const key = await crypto.subtle.importKey("spki", b64(publicKeyB64), { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, b64(signatureB64), messageBytes);
  } catch (e) {
    return null;
  }
}

const step = (ok, html) => `<li class="${ok ? "ok" : "bad"}">${html}</li>`;

/** Verify entry #seq end to end in this browser and render the reasoning into `target`. */
async function verifyEntry(seq, target) {
  target.hidden = false;
  target.innerHTML = `<h2>Verifying entry #${seq}…</h2>`;
  try {
    const p = await api(`proof/inclusion?seq=${seq}`);
    const steps = [];
    const bytes = b64(p.entryBytes);
    const e = parseEntry(bytes);
    const leaf = await leafHash(bytes);
    const leafOk = hex(leaf) === p.leafHash && e.seq === BigInt(seq);
    steps.push(step(leafOk, `Hashed the entry's ${bytes.length} canonical bytes here: leaf <code>${short(hex(leaf))}</code>${leafOk ? "" : " does not match the server's"}`));
    const root = await inclusionRoot(leaf, seq, p.treeSize, p.path.map(fromHex));
    steps.push(step(!!root, root ? `Combined it with ${p.path.length} sibling hashes from the audit path: root <code>${short(hex(root))}</code>` : "The audit path is malformed"));
    const head = parseTreeHead(b64(p.sth.signingBytes));
    const headOk = !!root && head.treeSize === p.treeSize && sameBytes(head.root, root) &&
      head.tag === "PAM-LEDGER/tree-head/v1" && head.ledgerId === p.ledgerId && head.ledgerId === WEB_LEDGER_ID;
    steps.push(step(headOk, `Read the signed checkpoint's own bytes: it covers ${int.format(head.treeSize)} entries with root <code>${short(hex(head.root))}</code>${headOk ? ", the root computed above" : ", which does NOT match"}`));
    const sig = await verifySignature(p.publicKey, p.sth.signature, b64(p.sth.signingBytes));
    steps.push(sig === null
      ? `<li>This browser has no Ed25519 support, so the signature could not be checked here (the server reports it ${p.sth.verified ? "valid" : "invalid"}).</li>`
      : step(sig, `Checked the Ed25519 signature on those bytes with the ledger's public key (key ${esc(p.sth.keyId)}): ${sig ? "valid" : "INVALID"}`));
    const ok = leafOk && !!root && headOk && sig === true;
    target.innerHTML = `
      <div class="panel-head"><h2>Entry #${e.seq}: ${esc(e.kind)}</h2><button type="button" onclick="this.closest('.panel-head').parentElement.hidden=true">Close</button></div>
      <div class="verdict ${ok ? "ok" : "bad"}">${ok ? "Verified in this browser: this entry is part of the signed ledger, unaltered." : "Verification FAILED."}</div>
      <dl class="kv"><dt>key</dt><dd>${esc(e.key)}</dd><dt>time</dt><dd>${esc(new Date(Number(e.timestampMicros / 1000n)).toISOString())}</dd>
        ${e.postings.map(x => `<dt>${esc(x.account)}</dt><dd class="${x.amount > 0n ? "pos" : "neg"}">${x.amount > 0n ? "+" : ""}${int.format(x.amount)} ${esc(x.currency)} minor units</dd>`).join("")}</dl>
      <ol class="steps">${steps.join("")}</ol>`;
  } catch (e) {
    target.innerHTML = `<div class="verdict bad">${errText(e)}</div>`;
  }
}

// ============================== router ==============================
const views = {};
let current = null;
function show(name) {
  if (!views[name]) name = "about";
  if (current && views[current].leave) views[current].leave();
  current = name;
  document.querySelectorAll(".view").forEach(v => { v.hidden = v.id !== "view-" + name; });
  document.querySelectorAll(".personas a").forEach(a => a.setAttribute("aria-selected", String(a.dataset.view === name)));
  views[name].enter();
}
window.addEventListener("hashchange", () => show(location.hash.slice(1)));
views.about = { enter() {} };

/** setInterval that runs immediately, skips overlapping calls, and can be stopped. */
function poll(fn, ms) {
  let busy = false;
  const run = async () => { if (busy) return; busy = true; try { await fn(); } catch (e) { /* shown by the view */ } finally { busy = false; } };
  run();
  const id = setInterval(run, ms);
  return () => clearInterval(id);
}

// Header: the ledger's live tip, always on.
poll(async () => {
  const o = await api("overview");
  $("ledgerId").textContent = o.ledgerId;
  $("tipEntries").textContent = o.entries == null ? "–" : int.format(o.entries);
  $("tipChain").textContent = short(o.chainHead);
  $("tipSigned").textContent = o.latestCheckpoint == null ? "–" : int.format(o.latestCheckpoint);
  $("simDot").className = "simdot " + o.simulator;
  $("storeStatus").textContent = o.available === false ?
    "Main ledger stopped. Open Administrator to clear storage and start again; damaged files remain for inspection." :
    "Demonstration only: no authentication. The tabs switch views, they do not control access.";
}, 1000);

// ============================== customer ==============================
let player = "alice";
let selection = null; // {fixture, sel, odds, label, betId, params}
let slipSubmitting = false;
let walletRequest = null;
let walletSubmitting = false;
let fixturesCache = [];

function updateSlipControls() {
  const unresolved = !!selection?.params;
  $("slip").querySelector('button[type="submit"]').disabled = slipSubmitting;
  $("slipStake").disabled = unresolved;
  $("slipCancel").disabled = unresolved;
  $("custFixtures").querySelectorAll("[data-fx]").forEach(b => { b.disabled = unresolved; });
}

async function loadPlayers() {
  const q = $("playerSearch").value;
  const r = await api("players?q=" + encodeURIComponent(q));
  renderControls($("playerList"), r.players.map(p =>
    `<button type="button" data-player="${esc(p.id)}" aria-current="${p.id === player}"><span>${esc(p.id)}</span><span class="amt">${eur(p.cash)}</span></button>`).join(""), ["player"]);
  $("playerCount").textContent = r.total > r.players.length ? `Showing ${r.players.length} of ${int.format(r.total)}. Search to narrow.` : `${int.format(r.total)} customers`;
}
$("playerList").addEventListener("click", e => {
  const b = e.target.closest("[data-player]");
  if (b) selectPlayer(b.dataset.player);
});
$("playerSearch").addEventListener("input", () => loadPlayers());

function selectPlayer(id) {
  player = id;
  $("custProof").hidden = true;
  $("raceResult").innerHTML = "";
  if (walletRequest) {
    say($("walletResult"), false, `Pending ${walletRequest.kind} for ${esc(walletRequest.player)}: retry to check its original reference.`);
  } else say($("walletResult"), null, "");
  say($("betResult"), null, "");
  document.querySelectorAll("#playerList [data-player]").forEach(b => b.setAttribute("aria-current", String(b.dataset.player === id)));
  refreshPlayer();
}

async function refreshPlayer() {
  const requestedPlayer = player;
  const p = await api("players/" + encodeURIComponent(requestedPlayer));
  if (player !== requestedPlayer) return;
  $("accName").textContent = p.id;
  $("accAutomated").textContent = p.automated ? "Automated customer (simulator)" : "Customer";
  $("accCash").textContent = eur(p.cash);
  $("accEscrow").textContent = eur(p.openBets);
  $("accEntries").textContent = int.format(p.entries);
  renderControls($("betTable"), `<tr><th>Bet</th><th>Pick</th><th class="num">Stake</th><th class="num">Returns</th><th></th></tr>` +
    (p.bets.length ? p.bets.map(b => `<tr>
      <td>${esc(b.fixture)}</td><td>${esc(b.selection)} @ ${odds(b.odds)}</td><td class="num">${eur(b.stake)}</td>
      <td class="num">${b.open ? `<span class="hint">to win ${eur(b.potentialPayout)}</span>` : eur(b.payout)}</td>
      <td>${b.open ? `<button type="button" data-player="${esc(p.id)}" data-cashout="${esc(b.betId)}">Cash out</button>` : `<span class="pill ${b.outcome === "WON" ? "ok" : b.outcome === "LOST" ? "bad" : "mut"}">${esc(b.outcome)}</span>`}</td></tr>`).join("")
      : `<tr><td colspan="5" class="hint">No bets yet.</td></tr>`), ["player", "cashout"]);
  renderControls($("historyTable"), `<tr><th>#</th><th>Type</th><th class="num">Cash</th><th>Time</th><th></th></tr>` +
    p.history.map(h => {
      const cash = h.postings.find(x => x.account === `player:${p.id}:cash:EUR`);
      return `<tr><td class="num">${h.seq}</td><td>${esc(h.kind)}</td>
        <td class="num ${cash ? (BigInt(cash.amount) > 0n ? "pos" : "neg") : ""}">${cash ? (BigInt(cash.amount) > 0n ? "+" : "") + eur(cash.amount) : "–"}</td>
        <td>${timeOf(h.time)}</td><td><button type="button" data-player="${esc(p.id)}" data-verify="${h.seq}">Verify</button></td></tr>`;
    }).join(""), ["player", "verify"]);
}
$("betTable").addEventListener("click", async e => {
  const b = e.target.closest("[data-cashout]");
  if (!b) return;
  const requestedPlayer = player;
  b.disabled = true;
  try {
    const offer = await api(`bets/${encodeURIComponent(b.dataset.cashout)}/cashout`);
    await api(`bets/${encodeURIComponent(b.dataset.cashout)}/cashout`, { method: "POST" });
    if (player === requestedPlayer) say($("betResult"), true, `Cashed out for ${eur(offer.offer)}.`);
  } catch (err) {
    if (player === requestedPlayer) say($("betResult"), false, err.error === "idempotency_conflict" ? "Too late: this bet has already been settled." : errText(err));
  }
  if (player === requestedPlayer) refreshPlayer();
});
$("historyTable").addEventListener("click", e => {
  const b = e.target.closest("[data-verify]");
  if (b) verifyEntry(Number(b.dataset.verify), $("custProof"));
});

$("depositForm").addEventListener("submit", e => { e.preventDefault(); walletAction("deposit"); });
$("withdrawBtn").addEventListener("click", () => walletAction("withdraw"));
async function walletAction(kind) {
  if (walletSubmitting) return;
  if (!walletRequest) {
    let amount;
    try { amount = toMinor($("depAmount").value); }
    catch (e) { return say($("walletResult"), false, esc(e.message)); }
    if (BigInt(amount) <= 0n) return say($("walletResult"), false, "Enter an amount above zero.");
    walletRequest = { kind, player, amount, ref: `web-${kind}-${uuid()}` };
  }
  const request = walletRequest;
  walletSubmitting = true;
  try {
    const r = await api(`players/${encodeURIComponent(request.player)}/${request.kind}`, {
      method: "POST", params: { amount: request.amount, ref: request.ref },
    });
    walletRequest = null;
    say($("walletResult"), true, `${request.kind === "deposit" ? "Deposited" : "Withdrew"} ${eur(request.amount)} for ${esc(request.player)}. Entry #${r.seq}, chain <code>${short(r.chainHash)}</code>`);
  } catch (err) {
    if (Number.isInteger(err.status) && err.status < 500 && err.error !== "outcome_unknown") walletRequest = null;
    const uncertain = walletRequest === request;
    say($("walletResult"), false, err.error === "insufficient_funds"
      ? "Refused: insufficient funds. The ledger never lets a balance go negative."
      : uncertain
        ? `Outcome unknown. Retry to check the original ${request.kind} for ${esc(request.player)}; its amount and reference are retained.`
        : errText(err));
  } finally {
    walletSubmitting = false;
  }
  if (player === request.player) refreshPlayer();
  loadPlayers();
}

async function loadCustFixtures() {
  fixturesCache = await api("fixtures");
  renderControls($("custFixtures"), fixturesCache.filter(f => !f.result).slice(0, 9).map(f => `
    <div class="fx"><div class="teams">${esc(f.home)} v ${esc(f.away)} <span>${esc(f.id)}</span></div>
      <div class="prices">${["HOME", "DRAW", "AWAY"].map(s => `<button type="button" data-fx="${esc(f.id)}" data-sel="${s}" aria-pressed="${selection && selection.fixture === f.id && selection.sel === s}"><span>${s.toLowerCase()}</span><b>${odds(f.odds[s])}</b></button>`).join("")}</div>
    </div>`).join("") || `<p class="hint">No open fixtures. The operator can add one.</p>`, ["fx", "sel"]);
  updateSlipControls();
}
$("custFixtures").addEventListener("click", e => {
  const b = e.target.closest("[data-fx]");
  if (!b || selection?.params) return;
  const f = fixturesCache.find(x => x.id === b.dataset.fx);
  selection = { fixture: f.id, sel: b.dataset.sel, odds: f.odds[b.dataset.sel], label: `${f.home} v ${f.away}`, betId: uuid(), params: null };
  $("slipText").innerHTML = `<b>${esc(selection.sel)}</b> in ${esc(selection.label)} @ <b>${odds(selection.odds)}</b>`;
  $("slip").hidden = false;
  updateSlipControls();
  loadCustFixtures();
});
$("slipCancel").addEventListener("click", () => {
  if (selection?.params) return;
  selection = null; $("slip").hidden = true; loadCustFixtures();
});
$("slip").addEventListener("submit", async e => {
  e.preventDefault();
  if (!selection || slipSubmitting) return;
  const submittedSelection = selection;
  // A lost response must retry the same player, stake and key, not silently create another bet.
  if (!submittedSelection.params) {
    let stake;
    try { stake = toMinor($("slipStake").value); }
    catch (e) { return say($("betResult"), false, esc(e.message)); }
    if (BigInt(stake) <= 0n) return say($("betResult"), false, "Enter a stake above zero.");
    submittedSelection.player = player;
    submittedSelection.params = { fixture: submittedSelection.fixture, selection: submittedSelection.sel, stake, betId: submittedSelection.betId };
  }
  const { stake } = submittedSelection.params;
  slipSubmitting = true;
  updateSlipControls();
  try {
    const r = await api(`players/${encodeURIComponent(submittedSelection.player)}/bets`, { method: "POST", params: submittedSelection.params });
    if (player === submittedSelection.player) say($("betResult"), true, `Bet placed: ${eur(stake)} on ${esc(submittedSelection.sel)} @ ${odds(submittedSelection.odds)}. Entry #${r.seq}.`);
    if (selection === submittedSelection) { selection = null; $("slip").hidden = true; }
  } catch (err) {
    if (player === submittedSelection.player) say($("betResult"), false, err.error === "insufficient_funds" ? "Refused: insufficient funds." : errText(err));
    const definitiveErrors = {
      400: ["bad_request"],
      403: ["invalid_host", "cross_origin"],
      404: ["not_found"],
      409: ["insufficient_funds", "idempotency_conflict", "version_conflict", "storage_reset"],
      413: ["payload_too_large"],
      422: ["rejected"],
    };
    if (Number.isInteger(err.status) && definitiveErrors[err.status]?.includes(err.error)) {
      submittedSelection.params = null;
      submittedSelection.betId = uuid();
    }
  } finally {
    slipSubmitting = false;
    updateSlipControls();
  }
  if (player === submittedSelection.player) { refreshPlayer(); loadCustFixtures(); loadPlayers(); }
});

// Five devices at once: genuinely concurrent requests from this browser.
$("raceBtn").addEventListener("click", async () => {
  const requestedPlayer = player;
  const p = await api("players/" + encodeURIComponent(requestedPlayer));
  if (player !== requestedPlayer) return;
  const f = fixturesCache.find(x => !x.result);
  if (!f) return ($("raceResult").innerHTML = `<p class="hint">No open fixture to bet on.</p>`);
  if (BigInt(p.cash) <= 0n) return ($("raceResult").innerHTML = `<p class="hint">${esc(requestedPlayer)} has no cash. Deposit first.</p>`);
  $("raceResult").innerHTML = "";
  const attempts = await Promise.allSettled([1, 2, 3, 4, 5].map(() =>
    api(`players/${encodeURIComponent(requestedPlayer)}/bets`, { method: "POST", params: { fixture: f.id, selection: "HOME", stake: p.cash, betId: uuid() } })));
  if (player !== requestedPlayer) return;
  $("raceResult").innerHTML = attempts.map((a, i) => a.status === "fulfilled"
    ? `<div class="dev ok"><b>Device ${i + 1}</b>accepted<span class="hint">entry #${a.value.seq}</span></div>`
    : `<div class="dev bad"><b>Device ${i + 1}</b>${a.reason.error === "insufficient_funds" ? "refused" : esc(a.reason.error)}<span class="hint">no funds left</span></div>`).join("");
  refreshPlayer(); loadPlayers();
});

let stopCustomer = [];
views.customer = {
  enter() {
    loadPlayers().catch(e => { $("playerCount").textContent = e.message || String(e); });
    loadCustFixtures().catch(e => say($("betResult"), false, errText(e)));
    stopCustomer = [poll(refreshPlayer, 1500), poll(loadCustFixtures, 4000), poll(loadPlayers, 5000)];
  },
  leave() { stopCustomer.forEach(f => f()); },
};

// ============================== operator ==============================
/** Draw a line chart sized to the SVG's real width (so text is never stretched). */
function lineChart(svg, values, fmt, age = "3 min ago") {
  const W = Math.max(200, svg.clientWidth || 400), H = 160, padL = 64, padB = 18, padT = 10;
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("preserveAspectRatio", "none");
  if (values.length < 2) { svg.innerHTML = `<text x="${padL}" y="${H / 2}">collecting…</text>`; return; }
  let lo = Math.min(...values), hi = Math.max(...values);
  if (lo > 0) lo = 0; if (hi < 0) hi = 0;
  if (hi === lo) hi = lo + 1;
  const x = i => padL + (W - padL - 8) * i / (values.length - 1);
  const y = v => padT + (H - padT - padB) * (1 - (v - lo) / (hi - lo));
  const pts = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const ticks = [hi, (hi + lo) / 2, lo];
  svg.innerHTML =
    ticks.map(t => `<line class="grid" x1="${padL}" x2="${W - 8}" y1="${y(t)}" y2="${y(t)}"/><text x="${padL - 6}" y="${y(t) + 4}" text-anchor="end">${esc(fmt(t))}</text>`).join("") +
    (lo < 0 ? `<line class="zero" x1="${padL}" x2="${W - 8}" y1="${y(0)}" y2="${y(0)}"/>` : "") +
    `<polygon class="area" points="${x(0)},${y(Math.max(lo, 0))} ${pts} ${x(values.length - 1)},${y(Math.max(lo, 0))}"/>` +
    `<polyline class="line" points="${pts}"/>` +
    `<circle class="last" cx="${x(values.length - 1)}" cy="${y(values[values.length - 1])}" r="3.5"/>` +
    `<text x="${padL}" y="${H - 2}">${esc(age)}</text><text x="${W - 8}" y="${H - 2}" text-anchor="end">now</text>`;
}

async function refreshOperator() {
  const o = await api("operator");
  const last = o.series.length ? o.series[o.series.length - 1] : { tps: 0 };
  const kpi = (label, value, sub = "") => `<div class="kpi"><span>${label}</span><b>${value}</b><small>${sub}</small></div>`;
  $("kpis").innerHTML = [
    kpi("Gross gaming revenue", eur(o.ggr), "stakes kept minus winnings paid"),
    kpi("Exposure", eur(o.exposure), "if every open bet won"),
    kpi("Open bets", int.format(o.openBets), eur(o.openStakes) + " staked"),
    kpi("Deposits", eur(o.deposits), ""),
    kpi("Withdrawals", eur(o.withdrawals), ""),
    kpi("Ledger entries", int.format(o.entries), ""),
    kpi("Throughput", int.format(last.tps) + "/s", "entries in the last second"),
    kpi("Customers", int.format(o.players), ""),
  ].join("");
  lineChart($("chTps"), o.series.map(p => p.tps), v => int.format(Math.round(v)));
  lineChart($("chGgr"), o.series.map(p => p.ggr), v => eur(v));
  lineChart($("chExp"), o.series.map(p => p.exposure), v => eur(v));
  const kinds = Object.entries(o.kinds).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...kinds.map(k => k[1]));
  $("kindBars").innerHTML = kinds.map(([k, n]) => `<div class="bar"><span>${esc(k)}</span><div class="track"><i style="width:${(100 * n / max).toFixed(1)}%"></i></div><span class="n">${int.format(n)}</span></div>`).join("");
  $("feed").innerHTML = o.feed.map(e => {
    const plus = e.postings.find(p => BigInt(p.amount) > 0n);
    return `<div><span class="seq">#${e.seq}</span><span class="who">${esc(e.metadata.playerId || "")}</span><span>${esc(e.kind)} ${plus ? eur(plus.amount) : ""}</span></div>`;
  }).join("");
}

async function refreshFixtures() {
  const fx = await api("fixtures");
  renderControls($("fixtureTable"), `<tr><th>Fixture</th><th class="num">Home / Draw / Away</th><th class="num">Open bets</th><th class="num">Staked</th><th class="num">Liability H / D / A</th><th>Result</th></tr>` +
    fx.map(f => `<tr>
      <td><b>${esc(f.id)}</b> ${esc(f.home)} v ${esc(f.away)}</td>
      <td class="num">${odds(f.odds.HOME)} / ${odds(f.odds.DRAW)} / ${odds(f.odds.AWAY)}</td>
      <td class="num">${int.format(f.openBets)}</td><td class="num">${eur(f.staked)}</td>
      <td class="num liab">${eur(f.liability.HOME)} / ${eur(f.liability.DRAW)} / ${eur(f.liability.AWAY)}</td>
      <td>${!f.result
        ? `<span class="settle">${["HOME", "DRAW", "AWAY", "VOID"].map(r => `<button type="button" data-settle="${esc(f.id)}" data-result="${r}">${r[0] + r.slice(1).toLowerCase()}</button>`).join("")}</span>`
        : f.result !== "LEGACY_UNRESOLVED" && !f.settlementComplete
          ? `<span class="settle"><span class="pill mut">${esc(f.result)} · settlement pending</span><button type="button" data-settle="${esc(f.id)}" data-result="${esc(f.result)}">Resume</button></span>`
          : `<span class="pill mut">${esc(f.result)}</span>`}</td></tr>`).join(""), ["settle", "result"]);
}
$("fixtureTable").addEventListener("click", async e => {
  const b = e.target.closest("[data-settle]");
  if (!b) return;
  b.closest(".settle").querySelectorAll("button").forEach(x => { x.disabled = true; });
  try {
    const r = await api(`fixtures/${b.dataset.settle}/settle`, { method: "POST", params: { result: b.dataset.result } });
    say($("settleResult"), true, `${esc(r.fixture)} settled as ${esc(r.result)}: ${r.won} won, ${r.lost} lost, ${r.voided} voided${r.alreadySettled ? `, ${r.alreadySettled} already cashed out` : ""}.`);
  } catch (err) { say($("settleResult"), false, errText(err)); }
  refreshFixtures();
});
$("addFixture").addEventListener("click", async () => { await api("fixtures", { method: "POST" }); refreshFixtures(); });

let stopOperator = [];
views.operator = {
  enter() { stopOperator = [poll(refreshOperator, 1000), poll(refreshFixtures, 2000)]; },
  leave() { stopOperator.forEach(f => f()); },
};

// ============================== regulator ==============================
const sigCache = new Map(); // Exact signed bytes and key -> true/false/null, verified in this browser
let publicKey = null;
const checkpointKey = (c, key) => JSON.stringify([c.signingBytes, c.signature, key, c.ledgerId, c.treeSize, c.rootHash, c.chainHead]);

async function refreshCheckpoints() {
  const r = await api("regulator/checkpoints");
  publicKey = r.publicKey;
  for (const c of r.checkpoints) {
    const k = checkpointKey(c, r.publicKey);
    if (!sigCache.has(k)) {
      const head = parseTreeHead(b64(c.signingBytes));
      const consistentFields = head.tag === "PAM-LEDGER/tree-head/v1" && head.ledgerId === c.ledgerId &&
        head.ledgerId === WEB_LEDGER_ID && head.treeSize === c.treeSize &&
        hex(head.root) === c.rootHash && hex(head.chainHead) === c.chainHead;
      const sig = await verifySignature(r.publicKey, c.signature, b64(c.signingBytes));
      sigCache.set(k, sig === null ? null : sig && consistentFields);
    }
  }
  const checkpointTime = c => timeOf(parseTreeHead(b64(c.signingBytes)).timestampMicros / 1000);
  $("sthTable").innerHTML = `<tr><th class="num">Entries covered</th><th>Merkle root</th><th>Signed</th><th>Signature (checked here)</th></tr>` +
    r.checkpoints.slice(0, 50).map(c => {
      const v = sigCache.get(checkpointKey(c, r.publicKey));
      return `<tr><td class="num">${int.format(c.treeSize)}</td><td><code>${short(c.rootHash)}</code></td><td>${checkpointTime(c)}</td>
        <td>${v === null ? `<span class="pill mut">no Ed25519 in browser</span>` : v ? `<span class="pill ok">valid</span>` : `<span class="pill bad">invalid</span>`}</td></tr>`;
    }).join("");
  const opts = r.checkpoints.filter(c => c.treeSize > 0).map(c => `<option value="${c.treeSize}">${int.format(c.treeSize)} entries · ${checkpointTime(c)}</option>`).join("");
  for (const id of ["consFrom", "consTo"]) {
    const el = $(id), keep = el.value;
    el.innerHTML = opts;
    if (keep && [...el.options].some(o => o.value === keep)) el.value = keep;
  }
  if (!$("consFrom").dataset.touched && $("consFrom").options.length) $("consFrom").selectedIndex = $("consFrom").options.length - 1;
}
$("consFrom").addEventListener("change", e => { e.target.dataset.touched = "1"; });
$("signNow").addEventListener("click", async () => { await api("regulator/checkpoint", { method: "POST" }); refreshCheckpoints(); });

function reportHtml(r) {
  return `<div class="verdict ${r.ok ? "ok" : "bad"}">${r.ok ? "VERIFIED: every entry, hash, signature and balance rule checks out." : "FAILED: " + esc(r.problems[0] || "verification failed")}</div>
    <p>${esc(r.note)}</p>
    <dl class="kv"><dt>entries replayed</dt><dd>${int.format(r.entries)}</dd>
      <dt>checkpoints verified</dt><dd>${int.format(r.checkpointsVerified)}</dd>
      <dt>money conserved</dt><dd>${Object.entries(r.totals).map(([c, v]) => `${esc(c)} sums to ${v}`).join(", ") || "–"}</dd>
      <dt>merkle root</dt><dd>${esc(r.merkleRoot || "–")}</dd>
      <dt>time</dt><dd>${r.millis} ms</dd></dl>
    ${r.problems.length > 1 ? `<ol class="steps">${r.problems.slice(1).map(p => step(false, esc(p))).join("")}</ol>` : ""}`;
}
$("auditBtn").addEventListener("click", async () => {
  $("auditBtn").disabled = true;
  $("auditResult").innerHTML = `<p class="hint">Snapshotting and replaying the journal…</p>`;
  try { $("auditResult").innerHTML = reportHtml(await api("regulator/audit", { method: "POST" })); }
  catch (e) { $("auditResult").innerHTML = `<div class="verdict bad">${errText(e)}</div>`; }
  $("auditBtn").disabled = false;
});
document.querySelectorAll("[data-tamper]").forEach(b =>
  b.addEventListener("click", () => runMainChaos(b.dataset.tamper, $("tamperResult"))));

$("consForm").addEventListener("submit", async e => {
  e.preventDefault();
  let from = Number($("consFrom").value), to = Number($("consTo").value);
  if (from > to) [from, to] = [to, from];
  try {
    const r = await api(`proof/consistency?from=${from}&to=${to}`);
    const a = parseTreeHead(b64(r.from.signingBytes)), b = parseTreeHead(b64(r.to.signingBytes));
    const fieldsOk = a.tag === "PAM-LEDGER/tree-head/v1" && b.tag === "PAM-LEDGER/tree-head/v1" &&
      a.ledgerId === b.ledgerId && a.ledgerId === r.ledgerId && a.ledgerId === WEB_LEDGER_ID &&
      a.treeSize === from && b.treeSize === to &&
      hex(a.root) === r.from.rootHash && hex(b.root) === r.to.rootHash &&
      hex(a.chainHead) === r.from.chainHead && hex(b.chainHead) === r.to.chainHead;
    const proofOk = fieldsOk && await consistent(from, to, a.root, b.root, r.proof.map(fromHex));
    const sa = await verifySignature(r.publicKey, r.from.signature, b64(r.from.signingBytes));
    const sb = await verifySignature(r.publicKey, r.to.signature, b64(r.to.signingBytes));
    const sigs = sa === true && sb === true;
    const ok = proofOk && sigs;
    $("consResult").innerHTML = `<div class="verdict ${ok ? "ok" : "bad"}">${ok ? `The ${int.format(from)}-entry ledger is an exact prefix of the ${int.format(to)}-entry ledger: nothing was changed or removed in between.` : "Consistency verification FAILED or incomplete."}</div>
      <ol class="steps">${step(proofOk, `Rebuilt both signed roots from ${r.proof.length} proof hashes in this browser`)}
      ${sa === null || sb === null ? "<li>This browser has no Ed25519 support to check the two signatures.</li>" : step(sigs, `Both checkpoint signatures ${sigs ? "valid" : "INVALID"}`)}</ol>`;
  } catch (err) { $("consResult").innerHTML = `<div class="verdict bad">${errText(err)}</div>`; }
});
$("inclForm").addEventListener("submit", e => { e.preventDefault(); verifyEntry(Number($("inclSeq").value), $("inclResult")); });

$("paForm").addEventListener("submit", async e => {
  e.preventDefault();
  const id = $("paPlayer").value.trim();
  try {
    const r = await api("regulator/players/" + encodeURIComponent(id));
    const pill = (ok, yes, no) => `<span class="pill ${ok ? "ok" : "bad"}">${ok ? yes : no}</span>`;
    $("paResult").innerHTML = `
      <div class="row">${pill(r.reconciles, "reconciles with the ledger", "does NOT reconcile")} ${pill(r.neverNegative, "never negative", "went negative")}
        <span class="hint">${int.format(r.entries)} entries · replayed cash ${eur(r.replayedCash)} vs ledger ${eur(r.ledgerCash)} · in open bets ${eur(r.replayedEscrow)} vs ${eur(r.ledgerEscrow)}</span></div>
      <div class="tablewrap"><table class="tbl"><tr><th class="num">#</th><th>Type</th><th>Time</th><th class="num">Cash change</th><th class="num">Cash after</th><th class="num">Open bets after</th><th>Chain hash</th></tr>
      ${r.rows.map(x => `<tr><td class="num">${x.seq}</td><td>${esc(x.kind)}</td><td>${timeOf(x.time)}</td>
        <td class="num ${BigInt(x.cashDelta) > 0n ? "pos" : BigInt(x.cashDelta) < 0n ? "neg" : ""}">${BigInt(x.cashDelta) !== 0n ? eur(x.cashDelta) : "–"}</td>
        <td class="num">${eur(x.cash)}</td><td class="num">${eur(x.escrow)}</td><td><code>${short(x.chainHash)}</code></td></tr>`).join("")}</table></div>`;
  } catch (err) { $("paResult").innerHTML = `<div class="verdict bad">${errText(err)}</div>`; }
});

let stopRegulator = [];
views.regulator = {
  enter() { stopRegulator = [poll(refreshCheckpoints, 3000)]; },
  leave() { stopRegulator.forEach(f => f()); },
};

// ============================== simulator ==============================
const WALL_LIMIT = 500;
const tiles = new Map(); // customer -> {el, stream, bal, lastOk}
let wallReady = false;
let wallSize = 0, lastEventId = 0, wallFilter = "all", lastCount = null, lastCountAt = 0, measuredRate = 0;

function buildWall(customers) {
  const n = Math.min(customers, WALL_LIMIT);
  $("wallNote").textContent = customers > WALL_LIMIT
    ? `Showing the first ${WALL_LIMIT} of ${int.format(customers)} automated customers. Every customer's activity still reaches the ledger and the other views.`
    : "Each tile is one automated customer. Select a tile to open that customer in the Customer view.";
  if (n === wallSize) return;
  wallSize = n;
  for (const name of tiles.keys()) {
    if (Number(name.slice(4)) > n) tiles.delete(name);
  }
  const frag = document.createDocumentFragment();
  for (let i = 1; i <= n; i++) {
    const name = "sim-" + String(i).padStart(5, "0");
    const existing = tiles.get(name);
    if (existing) { frag.appendChild(existing.el); continue; }
    const el = document.createElement("button");
    el.type = "button";
    el.className = "tile idle";
    el.dataset.customer = name;
    el.innerHTML = `<div class="th"><b>${name}</b><span class="bal">–</span></div><div class="stream"></div>`;
    frag.appendChild(el);
    tiles.set(name, { el, stream: el.querySelector(".stream"), bal: el.querySelector(".bal"), lastOk: true });
  }
  $("wall").replaceChildren(frag);
  applyWallFilter();
}

function applyWallFilter() {
  const q = $("wallSearch").value.trim();
  for (const [name, t] of tiles) {
    t.el.hidden = (q && !name.includes(q)) || (wallFilter === "refused" && t.lastOk);
  }
}
$("wallSearch").addEventListener("input", applyWallFilter);
document.querySelectorAll("[data-wall]").forEach(b => b.addEventListener("click", () => {
  wallFilter = b.dataset.wall;
  document.querySelectorAll("[data-wall]").forEach(x => x.setAttribute("aria-pressed", String(x === b)));
  applyWallFilter();
}));
$("wall").addEventListener("click", e => {
  const t = e.target.closest("[data-customer]");
  if (!t) return;
  player = t.dataset.customer;
  location.hash = "#customer";
});

async function pullEvents() {
  if (!wallReady) return;
  const r = await api("simulator/events?after=" + lastEventId);
  lastEventId = r.lastId;
  const now = performance.now();
  for (const ev of r.events) {
    const t = tiles.get(ev.customer);
    if (!t) continue;
    const line = document.createElement("div");
    line.className = ev.ok ? "ok" : "bad";
    line.innerHTML = `<span class="t">${new Date(ev.t).toLocaleTimeString([], { hour12: false, minute: "2-digit", second: "2-digit" })}</span>${esc(ev.text)}`;
    t.stream.prepend(line);
    while (t.stream.childElementCount > 4) t.stream.lastElementChild.remove();
    t.bal.textContent = eur(ev.cash);
    t.lastOk = ev.ok;
    t.el.classList.remove("idle");
    t.el.classList.add("live");
    t.el.classList.toggle("lastbad", !ev.ok);
    t.el.classList.remove("flash-ok", "flash-bad");
    t.el.classList.add(ev.ok ? "flash-ok" : "flash-bad");
    t.flashAt = now;
  }
  // Let flashes fade after a moment.
  for (const t of tiles.values()) {
    if (t.flashAt && now - t.flashAt > 250) { t.el.classList.remove("flash-ok", "flash-bad"); t.flashAt = 0; }
  }
  if (wallFilter === "refused" || $("wallSearch").value) applyWallFilter();
}

function simStat(label, value) { return `<div><span>${label}</span><b>${value}</b></div>`; }
async function refreshSim() {
  const s = await api("simulator");
  const count = s.succeeded + s.rejectedForFunds + s.rejectedOther;
  const t = performance.now();
  if (lastCount !== null && t > lastCountAt) measuredRate = Math.max(0, (count - lastCount) / ((t - lastCountAt) / 1000));
  lastCount = count; lastCountAt = t;
  if (s.state !== "RUNNING") measuredRate = 0;
  if (!$("simForm").dataset.dirty) {
    $("simCustomers").value = s.customers;
    $("simRate").value = s.opsPerMinute;
    $("simAuto").checked = s.autoFixtures;
  }
  buildWall(s.customers);
  wallReady = true;
  $("simStart").disabled = s.state !== "STOPPED";
  $("simPause").disabled = s.state === "STOPPED";
  $("simPause").textContent = s.state === "PAUSED" ? "Resume" : "Pause";
  $("simStop").disabled = s.state === "STOPPED";
  const elapsed = s.state === "STOPPED" || !s.startedAt ? "–" : new Date(Date.now() - s.startedAt).toISOString().substring(11, 19);
  $("simStats").innerHTML = [
    simStat("State", s.state.toLowerCase()),
    simStat("Running for", elapsed),
    simStat("Target", int.format(Math.round(s.targetRate)) + "/s"),
    simStat("Actual", int.format(Math.round(measuredRate)) + "/s"),
    simStat("Bets", int.format(s.bets)),
    simStat("Deposits", int.format(s.deposits)),
    simStat("Withdrawals", int.format(s.withdrawals)),
    simStat("Cash-outs", int.format(s.cashOuts)),
    simStat("Refused: funds", int.format(s.rejectedForFunds)),
    simStat("Refused: other", int.format(s.rejectedOther)),
    simStat("Shed (overload)", int.format(s.shed)),
    simStat("Fixture rounds", int.format(s.fixtureRounds)),
  ].join("");
}

function simSettings() {
  return { customers: $("simCustomers").value, opsPerMinute: $("simRate").value, autoFixtures: String($("simAuto").checked) };
}
async function simAction(action, withSettings) {
  try {
    await api("simulator", { method: "POST", params: { ...(withSettings ? simSettings() : {}), ...(action ? { action } : {}) } });
    delete $("simForm").dataset.dirty;
    say($("simResult"), null, "");
  } catch (e) { say($("simResult"), false, errText(e)); }
  refreshSim();
}
$("simForm").addEventListener("input", () => { $("simForm").dataset.dirty = "1"; });
$("simForm").addEventListener("submit", e => { e.preventDefault(); simAction(null, true); });
$("simStart").addEventListener("click", () => simAction("start", true));
$("simPause").addEventListener("click", () => simAction($("simPause").textContent === "Resume" ? "resume" : "pause", false));
$("simStop").addEventListener("click", () => simAction("stop", false));

let stopSim = [];
views.simulator = {
  enter() { wallReady = false; stopSim = [poll(refreshSim, 1000), poll(pullEvents, 500)]; },
  leave() { stopSim.forEach(f => f()); },
};

// ============================== administrator ==============================
const bytesText = value => {
  if (value == null) return "Unavailable";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let n = value, unit = 0;
  while (Math.abs(n) >= 1024 && unit < units.length - 1) { n /= 1024; unit++; }
  return `${n.toFixed(unit ? 1 : 0)} ${units[unit]}`;
};
const percentText = value => value == null ? "Unavailable" : `${value.toFixed(1)}%`;
let stopAdmin = null;
async function refreshAdmin() {
  try {
    const data = await api("administrator");
    if (current !== "administrator") return;
    $("adminCharts").hidden = data.available === false;
    $("adminReset").style.order = data.available === false ? "-1" : "";
    $("adminMode").textContent = data.available === false ?
      "Ledger stopped · reset remains available" : "Server-side samples every second · up to 3 minutes of history";
    if (data.available === false) {
      $("adminKpis").innerHTML = "";
      $("adminDetails").innerHTML = `<dt>Data directory</dt><dd>${esc(data.directory)}</dd><dt>Journal files on disk</dt><dd>${esc(bytesText(data.journalBytes))}</dd><dt>Storage available</dt><dd>${esc(bytesText(data.storageFree))}</dd>`;
      $("adminScope").textContent = data.ioScope;
      say($("adminStatus"), false, `${esc(data.reason)} Use Clear demo storage below to start again.`);
      return;
    }
    const s = data.series, last = s[s.length - 1];
    if (!last) { say($("adminStatus"), null, "Collecting server measurements…"); return; }
    const kpi = (name, value, note) => `<div class="kpi"><span>${esc(name)}</span><b>${esc(value)}</b><small>${esc(note)}</small></div>`;
    $("adminKpis").innerHTML = [
      kpi("Transactions / sec", last.tps.toFixed(1), "committed entries, not attempts"),
      kpi("JVM CPU", percentText(last.processCpu), `Host CPU ${percentText(last.systemCpu)}`),
      kpi("Heap used", bytesText(last.heapUsed), `Limit ${bytesText(last.heapMax)}`),
      kpi("Storage available", bytesText(last.storageFree), `Filesystem ${bytesText(last.storageTotal)}`),
      kpi("Journal size", bytesText(last.journalBytes), `${int.format(last.entries)} durable entries`),
      kpi("Storage writes", last.writeBytesPerSec == null ? "Unavailable" : bytesText(last.writeBytesPerSec) + "/s", "JVM physical I/O, not just journal"),
      kpi("Platform threads", int.format(last.threads), `Peak ${int.format(last.peakThreads)}; excludes virtual threads`),
      kpi("Read-model lag", int.format(last.readModelLag), "entries not yet indexed"),
    ].join("");
    const chart = (id, key, fmt) => {
      const svg = $(id);
      if (last[key] == null) {
        svg.innerHTML = `<text x="12" y="80">Counter unavailable for this interval</text>`;
      } else {
        let start = 0;
        s.forEach((p, i) => { if (p[key] == null) start = i + 1; });
        const available = s.slice(start);
        lineChart(svg, available.map(p => p[key]), fmt, `${Math.round((last.t - available[0].t) / 1000)} sec ago`);
      }
    };
    chart("adminCpu", "processCpu", percentText);
    chart("adminHeap", "heapUsed", bytesText);
    chart("adminTps", "tps", n => n.toFixed(1));
    chart("adminStorage", "storageFree", bytesText);
    chart("adminRead", "readBytesPerSec", n => bytesText(n) + "/s");
    chart("adminWrite", "writeBytesPerSec", n => bytesText(n) + "/s");
    const detail = (name, value) => `<dt>${esc(name)}</dt><dd>${esc(value)}</dd>`;
    $("adminDetails").innerHTML = [
      detail("Data directory", data.directory),
      detail("JVM uptime", `${Math.floor(last.uptimeMillis / 1000)} seconds`),
      detail("Available processors", last.processors),
      detail("Host load average", last.loadAverage == null ? "Unavailable" : last.loadAverage.toFixed(2)),
      detail("Host memory free / total", `${bytesText(last.systemMemoryFree)} / ${bytesText(last.systemMemoryTotal)}`),
      detail("Heap committed", bytesText(last.heapCommitted)),
      detail("Non-heap memory", bytesText(last.nonHeapUsed)),
      detail("Garbage collections", `${last.gcCount ?? "Unavailable"} / ${last.gcMillis ?? "Unavailable"} ms cumulative collection time`),
    ].join("");
    $("adminScope").textContent = data.ioScope;
    say($("adminStatus"), null, `Updated ${esc(timeOf(last.t))}. CPU is normalized across available processors; memory and filesystem totals reflect the host/container.`);
  } catch (e) { say($("adminStatus"), false, `Telemetry unavailable: ${errText(e)}`); }
}
views.administrator = {
  enter() { stopAdmin = poll(refreshAdmin, 1000); },
  leave() { if (stopAdmin) stopAdmin(); },
};
$("clearReveal").addEventListener("click", () => {
  $("clearForm").hidden = false; $("clearReveal").hidden = true; $("clearConfirmation").focus();
});
$("clearCancel").addEventListener("click", () => {
  $("clearForm").hidden = true; $("clearReveal").hidden = false; $("clearConfirmation").value = ""; $("clearSubmit").disabled = true; $("clearReveal").focus();
});
$("clearConfirmation").addEventListener("input", () => { $("clearSubmit").disabled = $("clearConfirmation").value !== "CLEAR DEMO STORAGE"; });
$("clearForm").addEventListener("submit", async e => {
  e.preventDefault();
  $("clearSubmit").disabled = true; $("clearCancel").disabled = true;
  $("clearConfirmation").disabled = true;
  say($("clearResult"), null, "Stopping services and clearing demo history…");
  try {
    await api("administrator/clear", { method: "POST", params: { confirmation: $("clearConfirmation").value } });
    location.reload(); // Drop all customer, proof and simulator caches from the previous ledger.
  } catch (error) {
    say($("clearResult"), false, `Reset failed: ${errText(error)}. Refresh to check whether the ledger is available before taking further action.`);
    $("clearCancel").disabled = false;
    $("clearConfirmation").disabled = false;
  }
});

// ============================== chaos monkey ==============================
let stopChaos = null, chaosSubmitting = false, chaosAvailable = false;
function updateChaosControls() {
  const enabled = chaosAvailable && !chaosSubmitting;
  document.querySelectorAll("[data-chaos], [data-tamper]").forEach(b => { b.disabled = !enabled; });
}
async function refreshChaos() {
  try {
    const data = await api("chaos");
    if (current !== "chaos") return;
    chaosAvailable = data.available;
    say($("chaosStoreStatus"), data.available ? null : false, data.available ?
      "Main store available. Live transaction races leave the simulator running; file and signer faults drain services before the experiment." :
      `${esc(data.reason)} <a href="#administrator">Reset the main store</a> before running another experiment.`);
    const enabled = chaosAvailable && !chaosSubmitting;
    renderControls($("chaosScenarios"), data.scenarios.map(s =>
      `<div class="chaos-scenario"><div><b>${esc(s.name)}</b><p>${esc(s.description)}</p></div><button type="button" data-chaos="${esc(s.id)}" data-live="${s.live}" ${enabled ? "" : "disabled"}>Run experiment<span class="sr">: ${esc(s.name)}</span></button></div>`).join(""), ["chaos"]);
    updateChaosControls();
    $("chaosHistory").innerHTML = data.history.length ? data.history.map(r =>
      `<article><div class="panel-head"><b>${esc(r.name)}</b><span class="pill ${r.passed ? "ok" : "bad"}">${r.passed ? "Expected protection observed" : "Protection failed"}</span></div><p>${esc(r.evidence)}</p><p class="hint">${esc(timeOf(r.t))} · ${r.millis} ms · ${esc(r.scope)} · ${r.available ? (r.scope.includes("LIVE") ? "ledger available" : "reopened") : "STOPPED - reset required"}</p></article>`).join("") :
      `<p class="hint">No experiments yet. Run a scenario to see its measured outcome here.</p>`;
  } catch (e) { say($("chaosResult"), false, `Chaos lab unavailable: ${errText(e)}`); }
}
$("chaosScenarios").addEventListener("click", async e => {
  const button = e.target.closest("[data-chaos]");
  if (button) await runMainChaos(button.dataset.chaos, $("chaosResult"), button.dataset.live === "true");
});
async function runMainChaos(scenario, target, live = false) {
  if (chaosSubmitting || !chaosAvailable) return;
  chaosSubmitting = true;
  updateChaosControls();
  say(target, null, live ? "Running a live transaction race on the MAIN DATA STORE; simulator stays active…" :
    "Draining services before running against the MAIN DATA STORE…");
  try {
    const r = await api("chaos", { method: "POST", params: { scenario } });
    say(target, r.passed, `<div class="verdict ${r.passed ? "ok" : "bad"}">${esc(r.name)}: ${r.passed ? "expected protection observed" : "protection FAILED"}</div><p>${esc(r.evidence)}</p>${r.available ? "" : '<a href="#administrator">Clear storage to start again</a>'}`);
  } catch (error) { say(target, false, `Experiment failed to complete: ${errText(error)}. Check Administrator for storage status and reset.`); }
  finally { chaosSubmitting = false; await refreshChaos(); }
}
views.chaos = {
  enter() { stopChaos = poll(refreshChaos, 1500); },
  leave() { if (stopChaos) stopChaos(); },
};

// ============================== start ==============================
const firstView = location.hash.slice(1);
show(firstView || "about");

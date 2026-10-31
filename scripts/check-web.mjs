// Dependency-free regression checks for the real console JavaScript.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
import vm from "node:vm";
import test from "node:test";

const source = readFileSync(new URL("../src/main/resources/web/app.js", import.meta.url), "utf8");
const cryptoSource = source.split("// ============================== router")[0];
const str = s => { const b = Buffer.from(s); const n = Buffer.alloc(4); n.writeInt32BE(b.length); return Buffer.concat([n, b]); };
const i32 = n => { const b = Buffer.alloc(4); b.writeInt32BE(n); return b; };
const i64 = n => { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(n)); return b; };
function entryBytes(amount = 100n) {
  return Buffer.concat([
    Buffer.from("PAML"), Buffer.from([1]), i64(0), i64(1000000), Buffer.alloc(32), Buffer.alloc(16),
    str("deposit:real"), str("DEPOSIT"), i32(2),
    str("house:psp-clearing:EUR"), str("EUR"), i64(-amount),
    str("player:alice:cash:EUR"), str("EUR"), i64(amount),
    i32(1), str("playerId"), str("alice"),
  ]);
}
function context(fetch) {
  const c = vm.createContext({ crypto: webcrypto, atob, TextDecoder, Uint8Array, DataView, fetch });
  vm.runInContext(cryptoSource, c);
  return c;
}

test("entry decoder preserves Long precision and rejects malformed records", () => {
  const c = context();
  const bytes = entryBytes(9223372036854775807n);
  c.bytes = bytes;
  const e = vm.runInContext("parseEntry(bytes)", c);
  assert.equal(e.postings[1].amount, 9223372036854775807n);
  assert.equal(e.metadata[0].value, "alice");
  for (const bad of [bytes.subarray(0, -1), Buffer.concat([bytes, Buffer.from([0])]), Buffer.from(bytes)]) {
    if (bad.length === bytes.length) bad.writeInt32BE(-1, 69);
    c.bytes = bad;
    assert.throws(() => vm.runInContext("parseEntry(bytes)", c));
  }
});

test("money entry and display preserve exact decimal and minor-unit precision", () => {
  const c = context();
  assert.equal(vm.runInContext('toMinor("70368744177664.01")', c), "7036874417766401");
  assert.equal(vm.runInContext('eur("9007199254740995")', c), "€90,071,992,547,409.95");
  assert.equal(vm.runInContext('eur("-1")', c), "-€0.01");
  assert.throws(() => vm.runInContext('toMinor("1.001")', c), /two decimal places/);
  assert.throws(() => vm.runInContext('toMinor("92233720368547758.08")', c), /supported range/);
});

test("bet submissions send entered decimal amounts as exact minor-unit strings", async () => {
  const calls = [];
  const s = slipContext(async (path, options) => {
    calls.push({ path, body: options.body.toString() });
    return { ok: true, json: async () => ({ seq: 5 }) };
  });
  s.pick("HOME");
  s.element("slipStake").value = "70368744177664.01";
  await s.submit();
  assert.match(calls[0].body, /stake=7036874417766401/);
});

test("uncertain wallet actions retry their original customer, amount and idempotency reference", async () => {
  const calls = [];
  const s = slipContext((path, options) => {
    calls.push({ path, body: options.body.toString() });
    if (calls.length === 1) return Promise.reject({ status: 504, error: "outcome_unknown", message: "unknown" });
    return Promise.resolve({ ok: true, json: async () => ({ seq: 9, chainHash: "abcdef0123456789" }) });
  });
  s.element("depAmount").value = "1.01";
  await s.c.walletAction("deposit");
  assert.match(s.element("walletResult").innerHTML, /original deposit/);
  vm.runInContext('player = "bob"', s.c);
  s.element("depAmount").value = "9.99";
  await s.c.walletAction("withdraw");
  assert.deepEqual(calls[1], calls[0]);
  await s.c.walletAction("withdraw");
  assert.equal(calls[2].path, "/api/players/bob/withdraw");
  assert.match(calls[2].body, /amount=999/);
  assert.notEqual(calls[2].body, calls[1].body);
});

test("LAN wallet and bet requests work without secure-context randomUUID", async () => {
  const calls = [];
  const s = slipContext(async (path, options) => {
    calls.push({ path, body: options.body.toString() });
    return { ok: true, json: async () => ({ seq: 1, chainHash: "abcdef" }) };
  });
  s.c.crypto = { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) };
  s.element("depAmount").value = "1.00";
  await s.c.walletAction("deposit");
  s.pick("HOME");
  await s.submit();
  const id = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  assert.match(new URLSearchParams(calls[0].body).get("ref").replace("web-deposit-", ""), id);
  assert.match(new URLSearchParams(calls[1].body).get("betId"), id);
});

test("late cash-out success and failure do not overwrite a different customer's result", async () => {
  for (const fails of [false, true]) {
    let finish, started;
    const pending = new Promise(yes => { started = yes; });
    const s = slipContext(path => {
      if (path.endsWith("/cashout") && !finish) {
        finish = "offer";
        return Promise.resolve({ ok: true, json: async () => ({ offer: "85" }) });
      }
      return new Promise((yes, no) => {
        finish = () => fails ? no({ status: 409, error: "idempotency_conflict" }) : yes({ ok: true, json: async () => ({}) });
        started();
      });
    });
    const action = s.element("betTable").handlers.click({ target: { closest: () => ({ dataset: { cashout: "alice-bet" } }) } });
    await pending;
    vm.runInContext('player = "bob"', s.c);
    s.element("betResult").innerHTML = "Bob's result";
    finish();
    await action;
    assert.equal(s.element("betResult").innerHTML, "Bob's result");
  }
});

test("a definitive 422 bet rejection unlocks the stake and starts a corrected request", async () => {
  const bodies = [];
  const s = slipContext(async (path, options) => {
    bodies.push(options.body.toString());
    if (bodies.length === 1) return { ok: false, status: 422, json: async () => ({ error: "rejected", message: "overflow" }) };
    return { ok: true, json: async () => ({ seq: 2 }) };
  });
  s.pick("HOME");
  await s.submit();
  assert.equal(s.element("slipStake").disabled, false);
  s.element("slipStake").value = "1.00";
  await s.submit();
  assert.match(bodies[1], /stake=100(?:&|$)/);
  assert.notEqual(new URLSearchParams(bodies[0]).get("betId"), new URLSearchParams(bodies[1]).get("betId"));
});

test("resizing the simulator wall preserves retained customers' activity and filters", () => {
  const c = context();
  const elements = new Map([["wall", { replaceChildren(frag) { this.children = frag.children; } }],
    ["wallNote", {}], ["wallSearch", { value: "" }]]);
  c.document = {
    getElementById: id => elements.get(id),
    createDocumentFragment: () => ({ children: [], appendChild(el) { this.children.push(el); } }),
    createElement: () => {
      const stream = {}, bal = {};
      return { dataset: {}, querySelector: sel => sel === ".stream" ? stream : bal };
    },
  };
  vm.runInContext(source.slice(source.indexOf("const WALL_LIMIT"), source.indexOf('$("wallSearch").addEventListener')), c);
  c.buildWall(500);
  const retained = vm.runInContext('tiles.get("sim-00001")', c);
  retained.lastOk = false;
  retained.stream.innerHTML = "Refused";
  retained.bal.textContent = "€1.00";
  retained.el.className = "tile live lastbad";
  vm.runInContext('lastEventId = 99; wallFilter = "refused"', c);
  c.buildWall(400);
  assert.equal(vm.runInContext('tiles.get("sim-00001")', c), retained);
  assert.equal(retained.stream.innerHTML, "Refused");
  assert.equal(retained.bal.textContent, "€1.00");
  assert.equal(retained.el.className, "tile live lastbad");
  assert.equal(retained.el.hidden, false);
  assert.equal(vm.runInContext("tiles.size", c), 400);
  assert.equal(vm.runInContext("lastEventId", c), 99);
  c.buildWall(600);
  c.buildWall(700);
  assert.match(elements.get("wallNote").textContent, /of 700/);
});

test("simulator action errors survive refreshes and wall resizing until a successful action", async () => {
  const c = context();
  const elements = new Map();
  c.document = {
    getElementById: id => {
      if (!elements.has(id)) elements.set(id, {
        dataset: {}, value: "", className: "", innerHTML: "",
        replaceChildren(frag) { this.children = frag.children; },
      });
      return elements.get(id);
    },
    createDocumentFragment: () => ({ children: [], appendChild(el) { this.children.push(el); } }),
    createElement: () => ({ dataset: {}, querySelector: () => ({}) }),
  };
  c.performance = { now: () => 1000 };
  const state = { customers: 2, state: "STOPPED", succeeded: 0, rejectedForFunds: 0, rejectedOther: 0 };
  let error = "customers must be 1..20000";
  c.api = async (path, options) => {
    if (options && error) throw new Error(error);
    return state;
  };
  vm.runInContext(source.slice(source.indexOf("const WALL_LIMIT"), source.indexOf('$("wallSearch").addEventListener')), c);
  vm.runInContext(source.slice(source.indexOf("function simStat"), source.indexOf('$("simForm").addEventListener')), c);

  await c.refreshSim();
  const result = c.document.getElementById("simResult");
  elements.get("simForm").dataset.dirty = "1";
  await c.simAction("start", true);
  assert.equal(result.innerHTML, error);
  assert.match(result.className, /\bbad\b/);
  assert.equal(elements.get("simForm").dataset.dirty, "1");
  for (const customers of [2, 3, 501]) {
    state.customers = customers;
    await c.refreshSim();
    assert.equal(result.innerHTML, error);
    assert.match(elements.get("wallNote").textContent, /automated customer/);
  }
  error = "<invalid settings>";
  await c.simAction(null, true);
  assert.equal(result.innerHTML, "&lt;invalid settings&gt;");

  error = null;
  await c.simAction("start", true);
  assert.equal(result.innerHTML, "");
  assert.doesNotMatch(result.className, /\bbad\b/);
  assert.equal(elements.get("simForm").dataset.dirty, undefined);
  await c.refreshSim();
  assert.equal(result.innerHTML, "");
});

test("simulator event polling waits for the customer wall to be built", async () => {
  let requests = 0;
  const c = context();
  c.api = async () => { requests++; return { lastId: 10, events: [] }; };
  const begin = source.indexOf("async function pullEvents()");
  const end = source.indexOf("function simStat", begin);
  vm.runInContext(`
    let wallReady = false, lastEventId = 0, wallFilter = "all";
    const tiles = new Map();
    ${source.slice(begin, end)}
  `, c);
  await c.pullEvents();
  assert.equal(requests, 0);
  assert.equal(vm.runInContext("lastEventId", c), 0);
});

test("verified panel renders canonical bytes, not altered JSON entry fields", async () => {
  const bytes = entryBytes();
  const root = Buffer.from(await webcrypto.subtle.digest("SHA-256", Buffer.concat([Buffer.from([0]), bytes])));
  const message = Buffer.concat([str("PAM-LEDGER/tree-head/v1"), str("sportsbook-web"), i64(1), root, Buffer.alloc(32), i64(1000000)]);
  const keys = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const signature = await webcrypto.subtle.sign("Ed25519", keys.privateKey, message);
  const response = {
    ledgerId: "sportsbook-web",
    entryBytes: bytes.toString("base64"), leafHash: root.toString("hex"), treeSize: 1, path: [],
    publicKey: Buffer.from(await webcrypto.subtle.exportKey("spki", keys.publicKey)).toString("base64"),
    sth: { signingBytes: message.toString("base64"), signature: Buffer.from(signature).toString("base64"), keyId: "test" },
    entry: { kind: "FORGED", key: "forged-key", time: "forged-time", postings: [{ account: "forged-account", amount: 999 }] },
  };
  const c = context(async () => ({ ok: true, json: async () => response }));
  const target = {};
  await c.verifyEntry(0, target);
  assert.match(target.innerHTML, /Verified in this browser/);
  assert.match(target.innerHTML, /DEPOSIT/);
  assert.match(target.innerHTML, /deposit:real/);
  assert.match(target.innerHTML, /alice:cash:EUR/);
  assert.match(target.innerHTML, /\+100 EUR minor units/);
  assert.match(target.innerHTML, /closest\('\.panel-head'\)\.parentElement\.hidden=true/);
  assert.doesNotMatch(target.innerHTML, /forged/i);
});

function slipContext(fetch) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      handlers: {}, value: "10.00", className: "", innerHTML: "", hidden: true, disabled: false,
      addEventListener(name, handler) { this.handlers[name] = handler; },
      querySelector() { return element("submit"); },
    });
    return elements.get(id);
  };
  const c = context(fetch);
  c.URLSearchParams = URLSearchParams;
  c.document = { getElementById: element, querySelectorAll: () => [] };
  const customer = source.slice(source.indexOf('let player = "alice"')).split("// Five devices at once:")[0];
  vm.runInContext(customer, c);
  vm.runInContext(`
    loadPlayers = loadCustFixtures = refreshPlayer = async () => {};
    fixturesCache = [{ id: "F1", home: "Home", away: "Away", odds: { HOME: 200, AWAY: 250 } }];
  `, c);
  const pick = sel => element("custFixtures").handlers.click({
    target: { closest: () => ({ dataset: { fx: "F1", sel } }) },
  });
  const submit = () => element("slip").handlers.submit({ preventDefault() {} });
  return { c, element, pick, submit };
}

test("double submission is blocked and a lost response retries the identical slip", async () => {
  const calls = [];
  let reject;
  const s = slipContext((path, options) => {
    calls.push({ path, body: options.body.toString() });
    if (calls.length === 1) return new Promise((_, no) => { reject = no; });
    return Promise.resolve({ ok: true, json: async () => ({ seq: 5 }) });
  });
  s.pick("HOME");
  const first = s.submit();
  assert.equal(s.element("submit").disabled, true);
  assert.equal(s.element("slipStake").disabled, true);
  await s.submit();
  assert.equal(calls.length, 1);
  reject(new Error("Response lost"));
  await first;
  assert.equal(s.element("submit").disabled, false);
  s.element("slipStake").value = "99.00";
  vm.runInContext('player = "bob"', s.c);
  await s.submit();
  assert.deepEqual(calls[1], calls[0]);
  assert.equal(s.element("slip").hidden, true);
});

test("completion of an old slip preserves a newly selected slip", async () => {
  let complete;
  const s = slipContext(() => new Promise(yes => { complete = yes; }));
  s.pick("HOME");
  const pending = s.submit();
  s.pick("AWAY");
  assert.equal(s.element("submit").disabled, true);
  complete({ ok: true, json: async () => ({ seq: 6 }) });
  await pending;
  assert.equal(s.element("slip").hidden, false);
  assert.equal(s.element("submit").disabled, false);
  assert.equal(s.element("slipStake").disabled, false);
  assert.equal(vm.runInContext("selection.sel", s.c), "AWAY");
});

test("checkpoint cache binds signature, signed bytes, key and displayed commitments", () => {
  const c = context();
  vm.runInContext(source.slice(source.indexOf("const sigCache =")).split("async function refreshCheckpoints")[0], c);
  const checkpoint = { signingBytes: "bytes", signature: "signature", ledgerId: "ledger", treeSize: 1, rootHash: "root", chainHead: "chain" };
  c.checkpoint = checkpoint;
  const original = vm.runInContext('checkpointKey(checkpoint, "key")', c);
  for (const field of Object.keys(checkpoint)) {
    c.checkpoint = { ...checkpoint, [field]: "different" };
    assert.notEqual(vm.runInContext('checkpointKey(checkpoint, "key")', c), original, field);
  }
  c.checkpoint = checkpoint;
  assert.notEqual(vm.runInContext('checkpointKey(checkpoint, "different-key")', c), original);
});

test("checkpoint table and options display signed timestamps even after an unsigned time changes", async () => {
  const root = Buffer.alloc(32, 1), chain = Buffer.alloc(32, 2);
  const message = Buffer.concat([str("PAM-LEDGER/tree-head/v1"), str("sportsbook-web"), i64(1), root, chain, i64(1000000)]);
  const keys = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const signature = await webcrypto.subtle.sign("Ed25519", keys.privateKey, message);
  const checkpoint = {
    ledgerId: "sportsbook-web", treeSize: 1, rootHash: root.toString("hex"), chainHead: chain.toString("hex"),
    signingBytes: message.toString("base64"), signature: Buffer.from(signature).toString("base64"),
    time: "1970-01-01T00:00:01Z",
  };
  const response = {
    publicKey: Buffer.from(await webcrypto.subtle.exportKey("spki", keys.publicKey)).toString("base64"),
    checkpoints: [checkpoint],
  };
  const elements = new Map(["sthTable", "consFrom", "consTo"].map(id => [id, { value: "", options: [], dataset: {} }]));
  const c = context(async () => ({ ok: true, json: async () => response }));
  c.document = { getElementById: id => elements.get(id) };
  vm.runInContext(source.slice(source.indexOf("const sigCache ="), source.indexOf('$("consFrom").addEventListener')), c);
  await c.refreshCheckpoints();
  const before = [...elements.values()].map(el => el.innerHTML);
  const signedTime = vm.runInContext("timeOf(1000)", c);
  before.forEach(html => assert.ok(html.includes(signedTime)));
  assert.match(before[0], /pill ok.*valid/);

  checkpoint.time = "2099-12-31T23:59:59Z";
  await c.refreshCheckpoints();
  assert.deepEqual([...elements.values()].map(el => el.innerHTML), before);
});

function raceContext(fetch) {
  const s = slipContext(fetch);
  vm.runInContext(source.slice(source.indexOf("// Five devices at once:")).split("let stopCustomer")[0], s.c);
  s.run = () => s.element("raceBtn").handlers.click();
  return s;
}

test("customer switch before balance lookup prevents all five bets", async () => {
  const calls = [];
  let complete;
  const s = raceContext(path => { calls.push(path); return new Promise(yes => { complete = yes; }); });
  const pending = s.run();
  vm.runInContext('player = "bob"', s.c);
  complete({ ok: true, json: async () => ({ cash: 100 }) });
  await pending;
  assert.deepEqual(calls, ["/api/players/alice"]);
});

test("five-device bets and late results stay bound to their original customer", async () => {
  const calls = [];
  const completions = [];
  const s = raceContext(path => {
    calls.push(path);
    if (calls.length === 1) return Promise.resolve({ ok: true, json: async () => ({ cash: 100 }) });
    return new Promise(yes => completions.push(yes));
  });
  const pending = s.run();
  while (completions.length < 5) await new Promise(yes => setImmediate(yes));
  vm.runInContext('player = "bob"', s.c);
  s.element("raceResult").innerHTML = "Bob's view";
  completions.forEach(yes => yes({ ok: true, json: async () => ({ seq: 1 }) }));
  await pending;
  assert.ok(calls.slice(1).every(path => path === "/api/players/alice/bets"));
  assert.equal(s.element("raceResult").innerHTML, "Bob's view");
});

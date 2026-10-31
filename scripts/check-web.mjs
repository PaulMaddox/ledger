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

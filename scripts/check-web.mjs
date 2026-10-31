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
  const message = Buffer.concat([str("PAM-LEDGER/tree-head/v1"), str("test-ledger"), i64(1), root, Buffer.alloc(32), i64(1000000)]);
  const keys = await webcrypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]);
  const signature = await webcrypto.subtle.sign("Ed25519", keys.privateKey, message);
  const response = {
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

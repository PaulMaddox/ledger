// Independently verify a downloaded supervisory evidence pack. No dependencies.
// Usage: node scripts/verify-report.mjs <evidence.json>
import { readFileSync } from "node:fs";
import { webcrypto, createHash } from "node:crypto";
import vm from "node:vm";

try {
  if (process.argv.length !== 3) throw new Error("Usage: node scripts/verify-report.mjs <evidence.json>");
  const pack = JSON.parse(readFileSync(process.argv[2], "utf8"));
  if (pack.format !== "supervisory-evidence-v1") throw new Error("Unsupported evidence pack format");
  const digest = createHash("sha256").update(JSON.stringify(pack.payload)).digest("hex");
  if (digest !== pack.sha256) throw new Error("Evidence payload digest does not match");
  const source = readFileSync(new URL("../src/main/resources/web/app.js", import.meta.url), "utf8");
  const context = vm.createContext({ crypto: webcrypto, atob, TextDecoder, TextEncoder, Uint8Array, DataView });
  vm.runInContext(source.split("// ============================== router")[0], context);
  const result = await context.verifyReportEvidence(pack.payload.report, pack.payload.entries);
  console.log(`PASS: ${result.entries} canonical entries, checkpoint signature, financials and customer money reconciled.`);
  console.log(result.note);
  console.log("Trust the public key through an independent channel; a key supplied inside the pack is not an external trust anchor.");
} catch (e) {
  console.error(`FAIL: ${e.message}`);
  process.exitCode = 1;
}

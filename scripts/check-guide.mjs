#!/usr/bin/env node
/*
 * Headless check of the interactive guide (docs/index.html). No dependencies: Node 18+.
 *
 *   node scripts/check-guide.mjs            # checks docs/index.html
 *   node scripts/check-guide.mjs path.html  # checks another build
 *
 * It runs the page's scripts against a minimal fake DOM and fails (exit 1) if:
 *  - the in-browser SHA-256 is wrong;
 *  - any debugger step's code anchor no longer exists in the embedded Kotlin source
 *    (this is what breaks when someone edits Ledger.kt and the guide isn't updated);
 *  - any cloud component lacks a service, rationale or alternative for any provider,
 *    or any component/provider/drill combination throws while rendering;
 *  - any test link in the R1-R6 table points at a test that no longer exists.
 *
 * It does not check visual layout. For that, open the page in a browser.
 */
import fs from "fs";

const file = process.argv[2] ?? "docs/index.html";
const html = fs.readFileSync(file, "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);

// ---- Minimal fake DOM: just enough for the page's scripts to run. ----
const element = () => ({
  style: {}, dataset: {}, classList: { toggle() {}, add() {}, remove() {} },
  querySelectorAll: () => [], querySelector: () => null, appendChild() {}, setAttribute() {},
  addEventListener() {}, contains: () => false, innerHTML: "", textContent: "",
  scrollTop: 0, clientHeight: 400, offsetTop: 0, value: "50",
});
const byId = {};
globalThis.document = {
  documentElement: { scrollHeight: 10000 },
  getElementById: id => (byId[id] ??= element()),
  querySelectorAll: () => [], querySelector: () => null, createElement: element,
  addEventListener() {}, activeElement: null, body: {},
};
globalThis.window = { addEventListener() {}, innerHeight: 900, scrollY: 0 };
globalThis.requestAnimationFrame = f => f();
globalThis.GUIDE_HTML = html;

// ---- Checks run inside the page's own scope, so they can see its constants. ----
const checks = `
globalThis.__checks = {
  sha256() {
    return sha256hex("abc") === "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  },
  debugger() {
    const broken = [];
    let steps = 0;
    for (const sc of SCENARIOS) {
      selectScenario(sc.id);
      for (const st of sc.steps) {
        if (!SOURCES[st.file] || !SOURCES[st.file].split("\\n").some(l => l.includes(st.anchor))) {
          broken.push(sc.id + ": " + st.file + ' "' + st.anchor + '"');
        }
        $("bStep").onclick();
        steps++;
      }
    }
    return { steps, broken };
  },
  cloud() {
    const missing = [];
    let renders = 0;
    for (const p of Object.keys(PV_NAMES)) for (const d of ["none", "az", "region"]) {
      pv = p; drill = d;
      for (const k of Object.keys(CLOUD)) {
        const c = CLOUD[k][p];
        if (!c || !c.svc || !c.why || !c.alt) missing.push(p + "/" + k);
        cSel = k; renderCloud(); renders++;
      }
    }
    for (const r of [1000, 150000, 600000]) renderLoad(r);
    return { renders, missing };
  },
  tests() {
    const names = new Set(Object.values(TESTS).flat().map(t => t.name));
    const links = [...GUIDE_HTML.matchAll(/data-test="([^"]+)"/g)].map(m => m[1]);
    return { tests: names.size, links: links.length, broken: links.filter(n => !names.has(n)) };
  },
};`;
(0, eval)(scripts.join("\n;\n") + checks);

// ---- Report ----
const c = globalThis.__checks;
let failed = false;
const report = (label, ok, detail) => {
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${detail ? "  " + detail : ""}`);
  if (!ok) failed = true;
};
report("sha256", c.sha256());
const d = c.debugger();
report("debugger anchors", d.broken.length === 0, `${d.steps} steps` + (d.broken.length ? `; broken: ${d.broken.join(" | ")}` : ""));
const cl = c.cloud();
report("cloud architecture", cl.missing.length === 0, `${cl.renders} renders` + (cl.missing.length ? `; missing: ${cl.missing.join(", ")}` : ""));
const t = c.tests();
report("test links", t.broken.length === 0, `${t.tests} tests, ${t.links} links` + (t.broken.length ? `; broken: ${t.broken.join(" | ")}` : ""));
process.exit(failed ? 1 : 0);

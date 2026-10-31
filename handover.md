# Handover: finishing the review of PR #11 (web console)

Branch: `feature/web-app`. PR: https://github.com/PaulMaddox/ledger/pull/11

Read `AGENTS.md` first. It holds the invariants, the documentation rules and the PR workflow. In particular:

- Every money movement goes through `SportsbookWallet`.
- No runtime dependencies beyond the JDK and Kotlin stdlib.
- README, the guide and the screenshots must be updated in the same PR as behaviour changes.

## Where things stand

Copilot's review raised 22 findings. **14 are fixed, committed and pushed.** Each fixed thread has a reply explaining the fix, and 13 of them are resolved.

| Commit | Fixes (review comment ids) |
|---|---|
| `d7abe6c` | Exact payout/cash-out arithmetic; placement holds lock until definitive; retries replay after settlement; resumable settlement; persisted fixtures + no id reuse; simulator event ordering, cursor, per-customer cash-outs (4430747738, 4430747759, 4430747771, 4430747782, 4430747794, 4430747897, 4430747913, 4430747930, 4430747945, 4430747978) |
| `cfef535` | Cross-site request protection (Host check, `X-Ledger-Console` header on POSTs, Origin check); paths decoded once (4430747804, 4430747961) |
| `0f1a035` | Player audit pinned to each account's `lastSeq` (4430747991) |
| next commit | Checkpoint history tails `checkpoints.journal` via new `Journal.scanReadOnly(path, from)`; burst test fails on old code (4430747872) |

## Remaining: 8 findings, all in the browser and the tamper lab

Fix each one separately. For each: commit and push, reply on its review thread explaining the fix, then resolve the thread.

**Verification honesty** in `src/main/resources/web/app.js` (most important):

1. **4430747824**: `verifyEntry` treats `sig === null` (no Ed25519 in the browser) as success. Show green only when `sig === true`; `null` must give an amber "incomplete" verdict.
2. **4430747844**: the panel shows `p.entry` fields from the server, not the bytes that were verified. Decode `entryBytes` in the browser and render *those* fields. The format is documented in `EntryCodec.kt`:
   - magic `PAML` (int32), then version (u8), seq (i64), timestamp (i64)
   - prevChainHash (32 bytes), then txId (two i64)
   - key (str), kind (str), postingCount (i32), then for each posting: account (str), currency (str), amount (i64)
   - metaCount (i32), then for each entry: key (str), value (str)
   - every str is an int32 length followed by UTF-8 bytes; all values big-endian
3. **4430747854**: before running the consistency check, verify that both signed heads' parsed `treeSize` values equal the requested `from` and `to`. Also check the tags are `PAM-LEDGER/tree-head/v1` and the two `ledgerId`s match. Use the parsed sizes in the check.
4. **4430747862**: show the consistency verdict green only if the proof is valid **and** both signatures are `=== true`; `null` means incomplete.

**Tamper lab** in `src/main/kotlin/com/pam/ledger/web/Audit.kt`:

5. **4430747882**: `tamper()` must attack only signed history.
   - Capture `val sth = checkpointNow()`, then snapshot.
   - In the copy, keep only entries `< sth.head.treeSize`, and only checkpoint records with `treeSize <= sth.head.treeSize`. Rewrite both files with `Journal.openForAppend` and `appendBatch`.
   - Choose the deposit victim and the truncation within that range.
   - Add a test that runs the simulator during `tamper()` and asserts every mode is caught.

**Browser races** in `src/main/resources/web/app.js`:

6. **4430748001**: in `refreshPlayer`, capture `const requested = player` before awaiting, and discard the response if `player !== requested`.
7. **4430748020**: allocate the bet slip's `betId` when the slip is created (in the price click handler), keep it until the outcome is definitive, and disable the submit button while a request is in flight.
8. **4430748032**: in the slip submit handler, capture the selection before awaiting, and clear `selection` and hide the slip only if `selection` is still that captured object.

## After the code fixes

1. **Rebuild the guide.** Run `python3 scripts/build-readme.py`, then `node scripts/check-guide.mjs`, which must print four PASS lines. CI fails if `docs/index.html` is stale.
2. **Regenerate the screenshots.** The UI changes, so follow AGENTS.md:
   - run `./gradlew web --args="--port 18080"`
   - then run `python3 scripts/capture-screenshots.py`
   - look at every image before committing, and commit `docs/screenshots/`
3. **Run the tests.** Run `./gradlew test`; 36 tests currently pass, and CI runs them twice. CI also runs the setup scripts and the demo on Ubuntu and Windows.
4. **Update the PR description.** Add the review fixes, including the bugs Copilot found.

## Owner actions, outside the code

- The ruleset **"Protect Main with your life"** has "require status checks" on but **no checks listed**, so it blocks nothing. Add these four checks under Settings → Rules:
  - `Kotlin tests`
  - `Guide is up to date and consistent`
  - `One-command setup (ubuntu-latest)`
  - `One-command setup (windows-latest)`
- The repository is **public**, though a private one was originally intended.

## Useful API calls (REST only; GraphQL is unavailable to Claude sessions)

```
# reply on a review thread
gh api repos/PaulMaddox/ledger/pulls/11/comments/<comment_id>/replies -X POST -F body=@reply.md
# resolve it (Claude Code sessions: CCR route)
gh api repos/PaulMaddox/ledger/pulls/11/ccr/comments/<comment_id>/resolve -X POST
```

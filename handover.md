# Review fixes for PR #11

Branch: `feature/web-app`. PR: https://github.com/PaulMaddox/ledger/pull/11

The four unresolved review comments found on 2026-10-31 have separate fixes:

| Commit | Review comment | Fix |
|---|---|---|
| `9034290` | 4430747844 | Decode and render canonical entry bytes; preserve exact Long amounts as BigInt. |
| `079a6d4` | 4430747882 | Bound tamper copies to a captured signed head, removing newer entries and checkpoints. |
| `7d94c79` | 4430748020 | One ID per slip, no overlapping submissions, identical retries after uncertain responses. |
| `764ca93` | 4430810188 | Await the original placement future while holding the fixture lock; no retry-admission gap. |

README and the guide describe the changes. The built guide is regenerated.
All six console screenshots were regenerated from a fresh running console and inspected.
`scripts/check-web.mjs` adds dependency-free browser regression checks, also run in CI.

## Validation

- Four browser regression checks pass.
- Guide checks print all four PASS lines (38 tests embedded, 53 debugger anchors).
- The targeted queue-overload regression passes: it keeps a bet uncommitted for
  31 seconds with a full queue while settlement waits, and checks overload propagation.
- The concurrent simulator tamper regression passes in targeted and full-suite runs.
- The demo completes with an independent audit, valid inclusion and consistency proofs,
  and zero currency totals.
- Both final full-suite runs execute 38 tests and finish with 37 passing and one failure.
  Each failure occurs during app startup at the existing `Book.save()` atomic replacement
  of `fixtures.txt.tmp` with `fixtures.txt`, with a Windows `AccessDeniedException`.
  Different tests fail on the two runs; no tests were skipped or weakened, and the
  unrelated fixture persistence code was left unchanged. Do not report the full suite
  as passing; inspect CI before merging.

## Owner actions outside this review

The earlier handover noted that the main-branch ruleset had required checks enabled
but no checks listed. That configuration has not been rechecked or changed here.
The expected checks are `Kotlin tests`, `Guide is up to date and consistent`,
`One-command setup (ubuntu-latest)`, and `One-command setup (windows-latest)`.

# Review fixes for PR #11

Branch: `feature/web-app`. PR: https://github.com/PaulMaddox/ledger/pull/11

The initial four comments received individual fixes. Concurrent remote commits
`45389b5` and `0a435fc` resolved those threads and introduced a registered-future
settlement design. With the owner's approval, merge `043f5c8` integrates that design
and preserves canonical-byte rendering, exact amounts and stable-slip regressions.

The eleven comments outstanding after that integration are addressed as follows:

| Commit | Review comment | Fix |
|---|---|---|
| `9034290`, `043f5c8` | 4430843905 | Display decoded canonical entry fields, not unsigned JSON. |
| `a0869f4` | 4430843915 | Bind checkpoint cache lookups to evidence, key and displayed commitments. |
| `9d26c73` | 4430843921 | Read the latest forty bets from the customer's own entry index. |
| `41910ea` | 4430843925 | Build global open views from current fixture indexes, removing empty buckets. |
| `b9c5fac` | 4430843934 | Scope counters and events to each run; suppress late old-run publication. |
| `d403d89` | 4430843942 | Recognize committed seeds and use stable new seed payloads. |
| `3735e64` | 4430843949 | Await original wallet outcomes before publishing acceptance/refusal. |
| `aa26b6c` | 4430843963 | Format customer IDs with `Locale.ROOT`. |
| `f4b406a` | 4430843981 | Clean up acquired services and the writer lock after failed startup. |
| `7d94c79`, `043f5c8` | 4430843990 | Keep one slip ID and submitted payload; prevent overlapping submissions. |
| `a43274b` | 4430843997 | Pin five-device bets and late results to the original customer. |

## Final validation

- All 48 Kotlin tests pass in two full runs, including the 31-second pending-outcome races.
- All seven dependency-free browser regressions pass.
- The rebuilt guide prints all four PASS lines: SHA-256, 53 debugger anchors,
  cloud architecture and test links (48 tests embedded).
- The demo's independent audit passes, including inclusion and consistency proofs
  and zero currency totals.
- All six screenshots were regenerated from the final integrated console and inspected.
- README, guide source and built guide are updated.

Earlier pre-integration runs encountered Windows access failures in the old fixture
file replacement code. The integrated remote persistence implementation uses unique
temporary files, and both final full runs pass. No tests were skipped or weakened.

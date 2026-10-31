#!/usr/bin/env python3
"""Builds docs/README.html: embeds the real Kotlin sources into the interactive README
so the step-through debugger always shows the code exactly as it is in the repository.
Run from the repository root:  python3 scripts/build-readme.py"""
import json, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
src_dir = root / "src/main/kotlin/com/pam/ledger"
order = ["SportsbookWallet.kt", "Ledger.kt", "Replay.kt", "Journal.kt", "Model.kt",
         "Validation.kt", "Merkle.kt", "Hashes.kt", "Signing.kt", "EntryCodec.kt",
         "AppendOnlyList.kt", "LedgerVerifier.kt", "Errors.kt"]
sources = {name: (src_dir / name).read_text() for name in order}
blob = "const SOURCES = " + json.dumps(sources).replace("</", "<\\/") + ";"
template = (root / "docs/readme.src.html").read_text()
(root / "docs/README.html").write_text(template.replace("/*__SOURCES__*/", blob))
print("wrote docs/README.html", sum(map(len, sources.values())), "chars of source")

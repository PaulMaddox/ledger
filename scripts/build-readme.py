#!/usr/bin/env python3
"""Builds docs/index.html, the interactive guide served by GitHub Pages.

It embeds the real Kotlin sources into the page so the step-through debugger always
shows the code exactly as it is in the repository, and wraps the template in a complete
standalone HTML document (doctype, charset, viewport) so browsers render it in
standards mode.

Run from the repository root after changing any source file:
    python3 scripts/build-readme.py
"""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent
src_dir = root / "src/main/kotlin/com/pam/ledger"
order = ["SportsbookWallet.kt", "Ledger.kt", "Replay.kt", "Journal.kt", "Model.kt",
         "Validation.kt", "Merkle.kt", "Hashes.kt", "Signing.kt", "EntryCodec.kt",
         "AppendOnlyList.kt", "LedgerVerifier.kt", "Errors.kt"]

sources = {name: (src_dir / name).read_text() for name in order}
# "</" is escaped so a "</script>" inside the Kotlin sources can't end the script block early.
blob = "const SOURCES = " + json.dumps(sources).replace("</", "<\\/") + ";"
template = (root / "docs/readme.src.html").read_text().replace("/*__SOURCES__*/", blob)

# The template starts with <title>, <meta>, <link> and <style>: those belong in <head>.
# Everything after the first </style> is page content.
split = template.index("</style>") + len("</style>")
head, body = template[:split], template[split:]

page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
:root{{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}}
body{{margin:0}} img{{max-width:100%}} [hidden]{{display:none!important}}
</style>
{head}
</head>
<body>
{body}
</body>
</html>
"""
(root / "docs/index.html").write_text(page)
print("wrote docs/index.html with", sum(map(len, sources.values())), "chars of embedded source")

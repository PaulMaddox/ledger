#!/usr/bin/env python3
"""Builds docs/index.html and the interactive guide embedded in the web console.

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


def extract_tests(path):
    """Return every @Test function in a Kotlin test file, with its doc comment.

    Each entry has the test name, the 1-based line where its snippet starts, and the
    snippet itself (doc comment + annotation + function body), dedented. The body is
    found by brace matching; Kotlin string templates like "${x}" balance, so a plain
    count is sufficient for these files, and the result is checked to end with "}".
    """
    lines = path.read_text().split("\n")
    tests = []
    for i, line in enumerate(lines):
        if line.strip() != "@Test":
            continue
        start = i
        # Pull in a directly preceding /** ... */ doc comment, if any.
        if i > 0 and lines[i - 1].strip().endswith("*/"):
            j = i - 1
            while j >= 0 and not lines[j].strip().startswith("/**"):
                j -= 1
            start = j
        # Find the function and its matching closing brace.
        k = i + 1
        depth, seen_open, end = 0, False, None
        while k < len(lines):
            for ch in lines[k]:
                if ch == "{":
                    depth += 1
                    seen_open = True
                elif ch == "}":
                    depth -= 1
            if seen_open and depth == 0:
                end = k
                break
            k += 1
        assert end is not None, f"unterminated test at {path.name}:{i + 1}"
        snippet = lines[start:end + 1]
        assert snippet[-1].strip() == "}", f"bad extraction at {path.name}:{i + 1}"
        name = lines[i + 1].split("`")[1]
        indent = min(len(l) - len(l.lstrip()) for l in snippet if l.strip())
        tests.append({"name": name, "line": start + 1,
                      "src": "\n".join(l[indent:] for l in snippet)})
    return tests


test_dir = root / "src/test/kotlin/com/pam/ledger"
test_order = ["MultiDeviceWalletTest.kt", "ConcurrencyTest.kt", "IntegrityTest.kt", "MerkleTest.kt", "TestSupport.kt"]
test_files = {name: (test_dir / name).read_text() for name in test_order}
tests = {name: extract_tests(test_dir / name) for name in test_order if name != "TestSupport.kt"}


def js_const(name, value):
    # "</" is escaped so a "</script>" inside Kotlin source can't end the script block early.
    return f"const {name} = " + json.dumps(value).replace("</", "<\\/") + ";"


template = ((root / "docs/readme.src.html").read_text()
            .replace("/*__SOURCES__*/", js_const("SOURCES", sources))
            .replace("/*__TESTS__*/", js_const("TEST_FILES", test_files) + "\n" + js_const("TESTS", tests)))

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
(root / "src/main/resources/web/about.html").write_text(page)
print("wrote docs/index.html and src/main/resources/web/about.html with",
      sum(map(len, sources.values())), "chars of embedded source and",
      sum(map(len, tests.values())), "tests")

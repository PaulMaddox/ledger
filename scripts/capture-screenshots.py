#!/usr/bin/env python3
"""Capture screenshots of the Ledger Console for README.md and the interactive guide.

Run whenever the web app's appearance or behaviour changes (AGENTS.md requires it):

    ./gradlew web --args="--port 18080"          # in one terminal (a fresh ledger)
    python3 scripts/capture-screenshots.py        # in another

Requires Playwright for Python and a Chromium-based browser:
    pip install playwright && python3 -m playwright install chromium
or pass an existing Chrome with --chrome /path/to/chrome.

It drives the real UI: starts the simulator with 500 automated customers, lets traffic
build up, then photographs each persona's view into docs/screenshots/.
"""
import argparse
import asyncio
import pathlib

from playwright.async_api import async_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent


async def main(url: str, out: pathlib.Path, chrome: str | None, warmup: int) -> None:
    out.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        launch = {"args": ["--no-sandbox"]}
        if chrome:
            launch["executable_path"] = chrome
        browser = await p.chromium.launch(**launch)
        page = await browser.new_page(viewport={"width": 1440, "height": 1000}, device_scale_factor=1, color_scheme="light")
        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(str(e)))

        def report(name: str) -> None:
            print(f"  wrote {out.relative_to(ROOT) if out.is_relative_to(ROOT) else out}/{name}.png")

        async def shot(name: str) -> None:
            """The current view from the top, as a person first sees it."""
            await page.evaluate("window.scrollTo(0, 0)")
            await page.wait_for_timeout(300)
            await page.screenshot(path=str(out / f"{name}.png"))
            report(name)

        async def close_up(name: str, selector: str) -> None:
            """Just one panel, for moments that happen below the fold."""
            await page.locator(selector).screenshot(path=str(out / f"{name}.png"))
            report(name)

        # Simulator: 500 automated customers, then let traffic build so every view has data.
        await page.goto(url + "#simulator")
        await page.fill("#simCustomers", "500")
        await page.fill("#simRate", "60")
        await page.click("#simStart")
        await page.wait_for_timeout(warmup * 1000)
        await shot("simulator")

        # Operator: KPIs, charts, fixtures and feed, with three minutes' worth of chart history building.
        await page.goto(url + "#operator")
        await page.wait_for_timeout(4000)
        await shot("operator")

        # Customer: an automated customer's account, with one entry verified in the browser.
        await page.goto(url + "#customer")
        await page.fill("#playerSearch", "sim-00007")
        await page.wait_for_timeout(1200)
        await page.click("[data-player='sim-00007']")
        await page.wait_for_timeout(1500)
        await page.click("#historyTable [data-verify]")
        await page.wait_for_selector("#custProof .verdict", timeout=10000)
        await close_up("customer-verify", "#custProof")
        await shot("customer")

        # Regulator: a full audit and a caught history rewrite.
        await page.goto(url + "#regulator")
        await page.wait_for_timeout(1500)
        await page.click("#auditBtn")
        await page.wait_for_selector("#auditResult .verdict", timeout=30000)
        await page.click("[data-tamper='rewrite']")
        await page.wait_for_selector("#tamperResult .verdict", timeout=30000)
        await close_up("regulator-tamper", "#tamperResult >> xpath=..")
        await shot("regulator")

        # Leave the app as we found it.
        await page.goto(url + "#simulator")
        await page.click("#simStop")
        await browser.close()
        if errors:
            raise SystemExit("page errors while capturing:\n  " + "\n  ".join(errors))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--url", default="http://127.0.0.1:18080/", help="where the web app is running")
    ap.add_argument("--out", default=str(ROOT / "docs" / "screenshots"), help="output folder")
    ap.add_argument("--chrome", default=None, help="path to an existing Chrome/Chromium executable")
    ap.add_argument("--warmup", type=int, default=12, help="seconds of simulated traffic before the first screenshot")
    a = ap.parse_args()
    asyncio.run(main(a.url.rstrip("/") + "/", pathlib.Path(a.out).resolve(), a.chrome, a.warmup))

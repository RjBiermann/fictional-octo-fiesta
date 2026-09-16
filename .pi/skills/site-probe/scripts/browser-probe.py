#!/usr/bin/env python3
"""Browser probe — site-probe escalation step 3, diagnosis only (ADR-0008).

Output is LEADS, never evidence: re-prove anything useful with plain curl before
it enters FINDINGS.

usage: browser-probe.py URL [--click TEXT] [--grep PATTERN]
  --click TEXT   click the first visible button/link whose text contains TEXT,
                then print which cookies changed (age-gate unlock discovery)
  --grep REGEX   network-log filter (matched case-insensitively); default shows
                media (.m3u8/.mp4), XHR/fetch and document requests

Prints: VERDICT line (CLIENT-RENDERED / SERVER-RENDERED), network log, cookie diff.
"""
import argparse
import re
import sys

DEFAULT_CLICK = None

PLAYWRIGHT_HINT = """\
browser-probe.py: playwright missing. Install it, then re-run:
  pip install playwright
  playwright install chromium
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--click", default=DEFAULT_CLICK)
    ap.add_argument("--grep", default=r"\.(m3u8|mp4)\b|xhr|fetch")
    args = ap.parse_args()

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sys.stderr.write(PLAYWRIGHT_HINT)
        return 2

    import urllib.request

    raw = urllib.request.urlopen(  # nosec - probe target given by the operator
        urllib.request.Request(args.url, headers={"User-Agent": "Mozilla/5.0"}), timeout=30
    ).read().decode("utf-8", "replace")

    rx = re.compile(args.grep, re.I)
    net: list[str] = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.on("request", lambda req: net.append(f"{req.resource_type}: {req.url}"))
        page.goto(args.url, timeout=30_000, wait_until="networkidle")

        rendered = page.content()

        # Age gate: click matching element, record cookie changes
        if args.click:
            before = {c["name"]: (c["domain"], c["value"][:8]) for c in page.context.cookies()}
            try:
                el = page.locator(f"button:has-text('{args.click}'), a:has-text('{args.click}')").first
                el.click(timeout=5_000)
                page.wait_for_load_state("networkidle", timeout=15_000)
                after = {c["name"]: (c["domain"], c["value"][:8]) for c in page.context.cookies()}
                for name, val in after.items():
                    if before.get(name) != val:
                        print(f"COOKIE-SET: {name} @ {val[0]}")
            except Exception as e:  # noqa: BLE001 - click is best-effort
                print(f"CLICK-FAILED: {e}", file=sys.stderr)
        browser.close()

    # Verdict: does the DOM gain content after JS that raw HTTP lacks?
    raw_empty = len(re.findall(r"<(a|article|video)\b", raw, re.I)) < 3
    rendered_full = len(re.findall(r"<(a|article|video)\b", rendered, re.I)) >= 3
    if raw_empty and rendered_full:
        print("VERDICT: CLIENT-RENDERED (HTTP body has no cards; DOM does — "
              "Blocked: client-rendered unless an SSR/API fallback is found)")
    elif rendered_full:
        print("VERDICT: SERVER-RENDERED (cards present in raw HTTP)")

    print("--- network log (leads — re-prove with curl) ---")
    for line in net:
        if rx.search(line):
            print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Production smoke: Digicom USSDGW admin via nginx (:80). Recon + login + HTMX pages."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://100.110.205.176"
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASS = sys.argv[3] if len(sys.argv) > 3 else "ussd-admin"
OUT = Path("/tmp/ussdgw-admin-smoke")
OUT.mkdir(parents=True, exist_ok=True)

PAGES = [
    "/admin",
    "/admin/routing",
    "/admin/ss7",
    "/admin/hlr",
    "/admin/smpp",
    "/admin/http",
    "/admin/grpc",
    "/admin/diameter",
    "/admin/sip",
    "/admin/tenants",
    "/admin/app-users",
    "/admin/campaigns",
    "/admin/my-campaigns",
]


def main() -> int:
    report: dict = {"base": BASE, "login": None, "pages": [], "htmx": [], "ui": {}}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("console", lambda m: errors.append(f"console:{m.type}:{m.text}") if m.type == "error" else None)

        # Login
        page.goto(f"{BASE}/admin/login", wait_until="networkidle", timeout=30000)
        page.screenshot(path=str(OUT / "01-login.png"), full_page=True)
        title = page.title()
        brand_ok = "Digicom-ET USSDGW" in title or "Digicom-ET USSDGW" in page.content()
        page.fill('input[name="username"]', USER)
        page.fill('input[name="password"]', PASS)
        page.click('button[type="submit"], input[type="submit"]')
        page.wait_for_load_state("networkidle")
        url = page.url
        logged_in = "/admin/login" not in url
        report["login"] = {
            "title": title,
            "brand_ok": brand_ok,
            "logged_in": logged_in,
            "url_after": url,
            "body_snip": page.locator("body").inner_text()[:240] if logged_in else page.content()[:240],
        }
        page.screenshot(path=str(OUT / "02-after-login.png"), full_page=True)
        if not logged_in:
            (OUT / "report.json").write_text(json.dumps(report, indent=2))
            browser.close()
            print(json.dumps(report, indent=2))
            return 1

        # Nav + pages
        for i, path in enumerate(PAGES):
            entry = {"path": path}
            try:
                resp = page.goto(f"{BASE}{path}", wait_until="networkidle", timeout=30000)
                entry["status"] = resp.status if resp else None
                html = page.content()
                entry["title"] = page.title()
                entry["has_hx"] = bool(re.search(r"\bhx-(get|post|target|swap|trigger)\b", html))
                entry["raw_token"] = "{{" in html and "}}" in html and bool(
                    re.search(r"\{\{[A-Z0-9_]+\}\}", html)
                )
                entry["brand"] = "Digicom-ET USSDGW" in html
                # Prefer ink-panel / form-card shells (OTA-derived admin)
                entry["shell_form_card"] = "form-card" in html or "ink-panel" in html
                # hx-live-badge must NOT appear on plane pages (AGENTS rule)
                entry["bad_hx_live_badge"] = "hx-live-badge" in html
                page.screenshot(path=str(OUT / f"page-{i:02d}-{path.strip('/').replace('/', '_')}.png"), full_page=True)
                # Trigger HTMX load polls if present (links status)
                status = page.locator("[hx-get*='/admin/links']").first
                if status.count():
                    # wait briefly for first poll swap
                    page.wait_for_timeout(1500)
                    frag = status.inner_html()[:200]
                    entry["links_frag_snip"] = frag
                    entry["links_looks_html"] = "<" in frag
            except Exception as ex:  # noqa: BLE001
                entry["error"] = str(ex)
            report["pages"].append(entry)

        # Explicit HTMX fragment fetch with cookies
        cookies = page.context.cookies()
        cookie_hdr = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
        report["htmx"].append({"cookie_names": [c["name"] for c in cookies]})

        # UI signature checks on dashboard
        page.goto(f"{BASE}/admin", wait_until="networkidle")
        text = page.locator("body").inner_text()
        report["ui"] = {
            "has_planes_nav": "SS7" in text or "Routing" in text,
            "console_errors": [e for e in errors if "favicon" not in e.lower()][:20],
        }

        browser.close()

    (OUT / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    # Fail if login failed or any page error / raw tokens
    bad = not report["login"]["logged_in"]
    bad = bad or any(p.get("error") or p.get("raw_token") for p in report["pages"])
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""M2-9 前端验收:mockup V15 p2/p5/p9/p16 与实现页逐页截图对照基线。
前置:后端 8094(vend_dev)已起,前端 dev 5185 已起(proxy → 8094)。
用法:python3 m2_9_baseline.py [round1|round2]
"""
import sys
from pathlib import Path
from urllib.parse import quote
from playwright.sync_api import sync_playwright

ROUND = sys.argv[1] if len(sys.argv) > 1 else "round1"
BASE = "http://localhost:5185"
MOCKUP = "file:///Users/yh-1/系统开发/智慧园区/UI-mockup/index.html"
OUT = Path(__file__).resolve().parents[1] / "screenshots" / "m2-9"
OUT.mkdir(parents=True, exist_ok=True)

PAGES = [
    ("p2-replenish", "/replenish", "AI 补货提示"),
    ("p5-outbound", "/outbound", "出库上架"),
    ("p9-stocktake", "/stocktake", "盘点"),
    ("p16-staff", "/staff/" + quote("小邱"), "员工详情"),
    ("p12-tasks", "/tasks", "任务日历"),
]
MOCK_PAGES = ["p2", "p5", "p9", "p16"]

console_errors: list[str] = []

with sync_playwright() as p:
    browser = p.chromium.launch()

    # ---- 1. mockup 桌面基线(仅 round1 需要) ----
    if ROUND == "round1":
        mpage = browser.new_page(viewport={"width": 1280, "height": 1000})
        mpage.goto(MOCKUP)
        for pid in MOCK_PAGES:
            mpage.evaluate(f"go('{pid}')")
            mpage.wait_for_timeout(300)
            mpage.screenshot(path=str(OUT / f"mockup-{pid}-1280.png"), full_page=True)
        mpage.close()

    # ---- 2. 实现页 桌面 1280 ----
    page = browser.new_page(viewport={"width": 1280, "height": 1000})
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: console_errors.append(str(e)))
    for name, path, _ in PAGES:
        page.goto(BASE + path, wait_until="networkidle")
        page.wait_for_timeout(600)
        page.screenshot(path=str(OUT / f"{ROUND}-{name}-1280.png"), full_page=True)
    page.close()

    # ---- 3. 实现页 手机 375 ----
    m = browser.new_page(viewport={"width": 375, "height": 812})
    m.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
    m.on("pageerror", lambda e: console_errors.append(str(e)))
    for name, path, _ in PAGES:
        m.goto(BASE + path, wait_until="networkidle")
        m.wait_for_timeout(600)
        m.screenshot(path=str(OUT / f"{ROUND}-{name}-375.png"), full_page=True)
    m.close()
    browser.close()

print("console errors:", len(console_errors))
for e in console_errors:
    print("  -", e[:200])
print("saved to", OUT)

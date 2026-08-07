#!/usr/bin/env python3
"""M2-2 仓库侧「一键转订货单」真通走查(fresh 数据下):勾行 → 弹窗选供应商 → 生成草稿 → 行标已采纳。"""
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://localhost:5180"
OUT = Path(__file__).resolve().parents[1] / "screenshots" / "m2-12"
OUT.mkdir(parents=True, exist_ok=True)
console_errors = []

with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1440, "height": 1200})
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: console_errors.append(str(e)))

    page.goto(BASE + "/replenish", wait_until="networkidle")
    page.get_by_role("tab", name="仓库侧 · 采购建议").click()
    page.wait_for_selector("text=仓库不够了")
    # 勾第一行
    page.locator(".el-tab-pane:visible .el-table__body .el-checkbox").first.click()
    page.wait_for_timeout(200)
    page.screenshot(path=str(OUT / "p2-8-po-selected.png"))
    page.get_by_role("button", name="一键转订货单").click()
    page.wait_for_selector("text=转订货单(草稿)")
    # 供应商默认第一个已选;直接生成
    page.screenshot(path=str(OUT / "p2-9-po-dialog.png"))
    page.get_by_role("button", name="生成订货单草稿").click()
    page.wait_for_selector("text=已转订货单", timeout=10000)
    page.screenshot(path=str(OUT / "p2-10-po-created.png"))
    page.get_by_role("button", name="留在本页").click()
    page.wait_for_timeout(800)
    # 行应消失(已采纳不再显示在建议列表)或状态更新
    page.screenshot(path=str(OUT / "p2-11-after-adopt.png"), full_page=True)
    browser.close()

if console_errors:
    print("CONSOLE ERRORS:")
    for e in console_errors:
        print(" -", e)
    sys.exit(1)
print("OK: 转订货单流程真通,console 0 错")

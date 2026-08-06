#!/usr/bin/env python3
"""M2-1/M2-2 p2 页真浏览器走查:两 Tab + 🔬 弹窗四 Tab + console 采集 + 截图。
前置:后端 8089(vend_dev)已起,前端 dev 5180 已起(proxy → 8089)。
"""
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

    # 1. 机器侧 Tab
    page.goto(BASE + "/replenish", wait_until="networkidle")
    page.wait_for_selector("text=机器侧 · 补满货道")
    page.screenshot(path=str(OUT / "p2-1-machine-tab.png"), full_page=True)

    # 2. 🔬 弹窗(机器侧第一行)四 Tab
    page.locator(".ai-badge").first.click()
    page.wait_for_selector("text=这个数怎么算出来的")
    page.wait_for_selector("text=策略")
    page.screenshot(path=str(OUT / "p2-2-process-tab1-calc.png"))
    page.get_by_role("tab", name="完整输出").click()
    page.wait_for_timeout(300)
    page.screenshot(path=str(OUT / "p2-3-process-tab2-output.png"))
    page.get_by_role("tab", name="确信分").click()
    page.wait_for_timeout(300)
    page.screenshot(path=str(OUT / "p2-4-process-tab3-confidence.png"))
    page.get_by_role("tab", name="原始数据").click()
    page.wait_for_timeout(300)
    page.screenshot(path=str(OUT / "p2-5-process-tab4-raw.png"))
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)

    # 3. 仓库侧 Tab + 慢销标记 + 🔬
    page.get_by_role("tab", name="仓库侧 · 采购建议").click()
    page.wait_for_selector("text=仓库不够了")
    page.screenshot(path=str(OUT / "p2-6-purchase-tab.png"), full_page=True)
    slow = page.locator("text=🐢 慢销")
    assert slow.count() >= 1, "慢销标记未渲染"
    # 仓库侧行 🔬
    page.locator(".el-tab-pane:visible .ai-badge").first.click()
    page.wait_for_selector("text=这个数怎么算出来的")
    page.screenshot(path=str(OUT / "p2-7-purchase-process.png"))
    page.keyboard.press("Escape")

    # 4. 断言关键元素
    page.get_by_role("tab", name="机器侧 · 补满货道").click()
    page.wait_for_timeout(300)
    assert page.locator("text=配货单票开发中").count() >= 1, "生成配货单占位按钮缺失"
    assert page.locator("text=数据截至").count() >= 1, "数据截至水印缺失"
    assert page.locator("text=① 每早导入昨日数据").count() >= 1, "流程条缺失"

    browser.close()

if console_errors:
    print("CONSOLE ERRORS:")
    for e in console_errors:
        print(" -", e)
    sys.exit(1)
print("OK: p2 两 Tab + 🔬 四 Tab 走查通过,console 0 错;截图落", OUT)

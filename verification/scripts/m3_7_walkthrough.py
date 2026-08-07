#!/usr/bin/env python3
"""M3-7 前端验收:p7 资金与对账页装配 + Dashboard 钱账卡点亮 真浏览器走查。
前置:后端 8102(vend_dev)已起,前端 dev 5193 已起(proxy → 8102)。
产出:verification/screenshots/m3-7/*.png + stdout 断言结果 + console 错误清单。
"""
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://localhost:5193"
OUT = Path(__file__).resolve().parents[1] / "screenshots" / "m3-7"
OUT.mkdir(parents=True, exist_ok=True)

console_errors: list[str] = []
results: list[str] = []


def ok(name: str, cond: bool, extra: str = ""):
    results.append(f"{'PASS' if cond else 'FAIL'} | {name}{(' | ' + extra) if extra else ''}")


with sync_playwright() as p:
    browser = p.chromium.launch()
    ctx = browser.new_context(viewport={"width": 1280, "height": 1000})
    page = ctx.new_page()
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: console_errors.append(str(e)))

    # ---- 1. /money 桌面全页 ----
    page.goto(BASE + "/money")
    page.wait_for_timeout(1500)
    ok("路由 /money 打开·标题", "资金与对账" in page.title())
    acct_cards = page.locator('[data-block="account-cards"] .stat').count()
    ok("① 账户余额卡行在位", acct_cards >= 2, f"{acct_cards} 张卡")
    ok("本月平台扣费卡在位", page.locator('text=本月平台扣费').count() >= 1)
    ok("② 结算对账区(SettlementPanel)在位", page.locator('[data-block="settlement"]').count() == 1)
    ok("⚡任务来源提示在位", "每月 2 日自动生成" in page.locator('[data-block="settlement"] h3').inner_text())
    ok("五步流程条在位", page.locator('[data-block="settlement"] .flow-step').count() == 5)
    ok("③ 流水账表在位", page.locator('[data-block="flows"] .el-table').count() == 1)
    flow_rows = page.locator('[data-block="flows"] .el-table__row').count()
    ok("流水有数据行", flow_rows > 0, f"{flow_rows} 行(首页)")
    ok("④ 索赔区(ClaimPanel)在位", page.locator('[data-block="claim"]').count() == 1)
    ok("⑤ 支出与设备区(ExpensePanel)在位", page.locator('[data-block="expense"]').count() == 1)
    ok("⑦ 钱盘入口在位", page.locator('[data-block="cashcheck-entry"]').count() == 1)
    ok("⑧ AI 洞察灰位(M4)在位", "里程碑 4" in page.locator('[data-block="ai-insight"]').inner_text())
    page.screenshot(path=str(OUT / "p7-money-1280.png"), full_page=True)

    # ---- 2. 流水筛选:类别=杂支 ----
    page.locator('[data-block="flows"] .el-select').nth(1).click()
    page.wait_for_timeout(400)
    page.locator('.el-select-dropdown__item:visible', has_text="杂支").first.click()
    page.wait_for_timeout(800)
    cats = page.locator('[data-block="flows"] .el-table__row .chip').all_inner_texts()
    ok("流水筛选(类别=杂支)生效", all("杂支" in c for c in cats) if cats else True,
       f"{len(cats)} 行全为杂支" if cats else "该类别 0 行(空态不误报)")
    page.screenshot(path=str(OUT / "p7-flows-filter-1280.png"))
    # 清筛选
    page.locator('[data-block="flows"] .el-select').nth(1).hover()
    page.wait_for_timeout(200)
    clear = page.locator('[data-block="flows"] .el-select').nth(1).locator('.el-select__clear')
    if clear.count():
        clear.click()
        page.wait_for_timeout(600)

    # ---- 3. 线下收入复合单弹窗 ----
    page.locator('button', has_text="记线下收入").click()
    page.wait_for_timeout(1000)
    ok("⑥ 线下收入复合单弹窗打开", page.locator('.el-dialog:visible').count() >= 1)
    page.screenshot(path=str(OUT / "p7-offline-dialog-1280.png"))
    page.keyboard.press("Escape")
    page.wait_for_timeout(400)

    # ---- 4. 钱盘入口 → 盘点页 ----
    page.locator('[data-block="cashcheck-entry"] button', has_text="去盘点页").click()
    page.wait_for_timeout(1200)
    ok("钱盘入口跳盘点页", page.url.endswith("/stocktake"))
    ok("盘点页钱盘三核对面板在位", page.locator('[data-block="cash-check"]').count() == 1)

    # ---- 5. Dashboard 钱账卡点亮 ----
    page.goto(BASE + "/dashboard")
    page.wait_for_timeout(2000)
    card = page.locator('[data-block="money-card"]')
    ok("Dashboard 钱账卡点亮(非灰位)", card.count() == 1 and "里程碑 3" not in card.inner_text())
    ok("钱账卡·今日流水笔数", "笔今日流水" in card.inner_text(), card.inner_text().replace("\n", " ")[:120])
    page.screenshot(path=str(OUT / "p1-dashboard-money-card-1280.png"), full_page=True)
    card.click()
    page.wait_for_timeout(1000)
    ok("钱账卡点击跳 /money", page.url.endswith("/money"))

    # ---- 6. /money-lab 已删(路由无匹配 → 空白主区,不再渲染验证台) ----
    page.goto(BASE + "/money-lab")
    page.wait_for_timeout(800)
    ok("/money-lab 验证台已删", page.locator('text=M3-4 组件验证台').count() == 0)

    # ---- 7. 手机视口 375 ----
    mpage = browser.new_context(viewport={"width": 375, "height": 812}).new_page()
    mpage.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    mpage.goto(BASE + "/money")
    mpage.wait_for_timeout(1500)
    ok("375 视口无横向溢出", mpage.evaluate("document.documentElement.scrollWidth <= 385"),
       f"scrollWidth={mpage.evaluate('document.documentElement.scrollWidth')}")
    mpage.screenshot(path=str(OUT / "p7-money-375.png"), full_page=True)
    mpage.goto(BASE + "/dashboard")
    mpage.wait_for_timeout(1500)
    mpage.screenshot(path=str(OUT / "p1-dashboard-375.png"), full_page=True)

    browser.close()

print("\n".join(results))
errs = [e for e in console_errors if "favicon" not in e]
print(f"\nconsole errors: {len(errs)}")
for e in errs[:10]:
    print("  ERR:", e[:200])

#!/usr/bin/env python3
"""M4-6 前端验收:经营驾驶舱 p1 全点亮 + 全站数据新鲜度治理 真浏览器走查。
前置:后端 8108(vend_dev)已起,前端 dev 5199 已起(proxy → 8108)。
产出:verification/screenshots/m4-6/*.png + stdout 断言结果 + console 错误清单。
"""
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://localhost:5199"
OUT = Path(__file__).resolve().parents[1] / "screenshots" / "m4-6"
OUT.mkdir(parents=True, exist_ok=True)

console_errors: list[str] = []
results: list[str] = []


def ok(name: str, cond: bool, extra: str = ""):
    results.append(f"{'PASS' if cond else 'FAIL'} | {name}{(' | ' + extra) if extra else ''}")


with sync_playwright() as p:
    browser = p.chromium.launch()

    # ============ 桌面视口 1360x1000 ============
    ctx = browser.new_context(viewport={"width": 1360, "height": 1000})
    page = ctx.new_page()
    page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)
    page.on("pageerror", lambda e: console_errors.append(str(e)))

    page.goto(BASE + "/dashboard")
    page.wait_for_timeout(2500)
    ok("路由 /dashboard 打开·标题", "工作台" in page.title() or "ERP" in page.title())

    # --- 全局数据新鲜度水印(铁律1) ---
    fb = page.locator('[data-block="data-freshness-bar"]')
    ok("① 全站数据新鲜度水印在位", fb.count() >= 1)
    ok("水印显示「数据截至」", "数据截至" in fb.first.inner_text())
    ok("水印新鲜态(今日已更新)", "今日已更新" in fb.first.inner_text(),
       fb.first.inner_text().replace("\n", " "))

    # --- 今日工作台 ---
    ok("② 今日工作台条在位", page.locator(".work-strip").count() == 1)

    # --- 4 张数字卡全点亮 ---
    stats = page.locator(".stat-grid .stat")
    ok("③ 数字卡 4 张在位", stats.count() == 4, f"{stats.count()} 张")

    sales_txt = stats.nth(0).inner_text()
    ok("本月销售/毛利 非测试噪声(非 ¥7)", "¥7\n" not in sales_txt and "-388" not in sales_txt,
       sales_txt.replace("\n", " ")[:60])
    ok("本月销售/毛利 有真实销售额", "¥" in sales_txt)

    money_card = page.locator('[data-block="money-card"]')
    ok("④ 钱账卡在位", money_card.count() == 1)
    ok("钱账卡显示净家底", "净家底" in money_card.inner_text(),
       money_card.inner_text().replace("\n", " ")[:70])

    # --- AI 经营洞察(接入点#3 解释层) ---
    ai = page.locator('[data-block="ai-insight"]')
    ok("⑤ AI 经营洞察卡在位", ai.count() == 1)
    ok("AI 洞察有解读文本", len(ai.inner_text()) > 20)

    # --- 异常雷达(接入点#4)TOP 限制 ---
    radar = page.locator('[data-block="anomaly-radar"]')
    ok("⑥ 异常雷达卡在位", radar.count() == 1)
    shown = radar.locator(".anomaly-row").count()
    ok("异常雷达只显 TOP 5", shown <= 5, f"{shown} 行")
    ok("异常雷达有折叠计数", radar.locator(".anomaly-more").count() == 1)

    # --- 机器卡 / 热销 TOP ---
    ok("⑦ 机器卡在位", page.locator(".mcard").count() >= 1, f"{page.locator('.mcard').count()} 张")
    ok("⑧ 热销 TOP 有数据行", page.locator(".ltab tr").count() > 1)

    page.screenshot(path=str(OUT / "p1-desktop-full.png"), full_page=True)

    # ============ 卡片点击跳转真走一遍(router.push 异步,click 后等) ============
    def jump(name, do_click, expect):
        page.goto(BASE + "/dashboard")
        page.wait_for_timeout(1800)
        do_click()
        page.wait_for_timeout(700)
        ok(f"跳转 · {name} → {expect}", expect in page.url, page.url.replace(BASE, ""))

    jump("钱账卡", lambda: page.locator('[data-block="money-card"]').click(), "/money")
    jump("今日工作台", lambda: page.locator(".work-strip").click(), "/tasks")
    jump("AI 补货卡", lambda: page.locator('.stat', has_text="AI 补货建议").click(), "/replenish")
    jump("红灯·负库存", lambda: page.locator(".alert.a-red").first.click(), "/inventory")
    jump("机器卡", lambda: page.locator(".mcard").first.click(), "/machines/")
    jump("热销商品名", lambda: page.locator(".ltab .plink").first.click(), "/products/")

    # ============ 直达非驾驶舱页:全站水印惰性自足 ============
    page.goto(BASE + "/inventory")
    page.wait_for_timeout(2000)
    fb2 = page.locator('[data-block="data-freshness-bar"]')
    ok("⑨ 直达库存页也有顶部水印(全站统一)", fb2.count() >= 1)
    ok("直达页水印惰性拉到数据截至", "数据截至" in fb2.first.inner_text()
       and "——" not in fb2.first.inner_text(), fb2.first.inner_text().replace("\n", " ")[:50])
    page.screenshot(path=str(OUT / "freshbar-on-inventory.png"))

    ctx.close()

    # ============ 手机视口 390x844 ============
    mctx = browser.new_context(viewport={"width": 390, "height": 844})
    mpage = mctx.new_page()
    mpage.goto(BASE + "/dashboard")
    mpage.wait_for_timeout(2500)
    # 响应式:数字卡网格在 ≤560 应为单列
    cols = mpage.eval_on_selector(
        ".stat-grid", "el => getComputedStyle(el).gridTemplateColumns")
    col_n = len(cols.split(" ")) if cols else 0
    ok("⑩ 手机视口数字卡单列(响应式)", col_n == 1, f"grid-cols={cols}")
    two = mpage.eval_on_selector(
        ".two-col", "el => getComputedStyle(el).gridTemplateColumns")
    ok("手机视口机器/热销单列", len(two.split(" ")) == 1, f"two-col={two}")
    mpage.screenshot(path=str(OUT / "p1-mobile-full.png"), full_page=True)

    mctx.close()
    browser.close()

print("\n".join(results))
print("\n--- console errors ---")
print("\n".join(console_errors) if console_errors else "(none)")
n_fail = sum(1 for r in results if r.startswith("FAIL"))
print(f"\n=== {len(results)-n_fail} PASS / {n_fail} FAIL / {len(results)} total ===")

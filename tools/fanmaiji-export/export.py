#!/usr/bin/env python3
"""fanmaiji.top 后台数据自动下载框架(S0-4)。

框架状态:selectors 为占位,首次 --probe 联真后回填 config.yaml。
账密永远来自本地 .env,脚本只做 登录→导出→下载,不做任何修改类操作。
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
from pathlib import Path

try:
    import yaml
    from dotenv import load_dotenv
except ImportError:
    sys.exit("缺依赖:pip3 install playwright pyyaml python-dotenv && python3 -m playwright install chromium")

BASE = Path(__file__).parent
load_dotenv(BASE / ".env")
CFG = yaml.safe_load((BASE / "config.yaml").read_text(encoding="utf-8"))


def env_or_die(key: str) -> str:
    val = os.getenv(key, "")
    if not val or "待老板填" in val:
        sys.exit(f"{key} 未配置:请复制 .env.example 为 .env 并填入真实值(见 README)")
    return val


def month_list(month_arg: str | None) -> list[str]:
    if month_arg:
        return [month_arg]
    today = dt.date.today()
    first = today.replace(day=1)
    prev = (first - dt.timedelta(days=1)).replace(day=1)
    return [prev.strftime("%Y-%m"), today.strftime("%Y-%m")]  # 上月+本月(后台查询窗口)


def run(export_types: list[str], month_arg: str | None, probe: bool) -> None:
    from playwright.sync_api import sync_playwright

    base_url = env_or_die("FANMAIJI_BASE_URL")
    user = env_or_die("FANMAIJI_USER")
    password = env_or_die("FANMAIJI_PASS")
    out_root = Path(os.getenv("FANMAIJI_DOWNLOAD_DIR", "./downloads"))
    out_dir = out_root / dt.date.today().isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=not probe)
        page = browser.new_page()
        login = CFG["login"]
        page.goto(base_url + login["url_path"])
        if probe:
            print("【probe 模式】请在打开的浏览器里人工登录并逐个点开三个导出菜单;"
                  "把地址栏 URL 与按钮特征回填 config.yaml 后,即可无头运行。按 Ctrl+C 结束。")
            page.wait_for_timeout(600_000)
            return
        page.fill(login["user_selector"], user)
        page.fill(login["pass_selector"], password)
        page.click(login["submit_selector"])
        page.wait_for_load_state("networkidle")

        for etype in export_types:
            spec = CFG["exports"][etype]
            months = month_list(month_arg) if spec["date_range_param"] == "month" else [None]
            for m in months:
                page.click(f"text={spec['menu_text']}")
                page.wait_for_load_state("networkidle")
                # TODO(probe 后回填):按月筛选控件的真实选择器
                with page.expect_download() as dl:
                    page.click(f"text={spec['export_button_text']}")
                target = out_dir / f"{etype}{'-' + m if m else ''}{Path(dl.value.suggested_filename).suffix or '.xlsx'}"
                dl.value.save_as(target)
                print(f"已下载:{target}")
        browser.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="fanmaiji.top 三类数据自动下载")
    ap.add_argument("--type", choices=["sales", "replenish", "products"], help="单独下载一类")
    ap.add_argument("--month", help="YYYY-MM,仅对按月导出生效;缺省=上月+本月")
    ap.add_argument("--all", action="store_true", help="三类全下")
    ap.add_argument("--probe", action="store_true", help="有头模式人工登录,用于首次核对选择器")
    args = ap.parse_args()
    types = ["sales", "replenish", "products"] if (args.all or not args.type) else [args.type]
    run(types, args.month, args.probe)

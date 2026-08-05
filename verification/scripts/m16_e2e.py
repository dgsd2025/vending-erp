#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
M1-6 端到端对平驱动脚本:用期初向导三步把老 Excel 套表全量导入,然后调毛利报表 API 对平冲刺0基准。

前置:后端起在 8085,DB 指向干净库(注意:vend_e2e 被其他会话占用,M1-6 用 vend_e2e_m16):
  docker exec vend-mysql mysql -uroot -pvend123 -e "CREATE DATABASE IF NOT EXISTS vend_e2e_m16 DEFAULT CHARACTER SET utf8mb4"
  cd vending-erp/backend && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && \
  mvn -s settings.xml -q package -DskipTests && \
  java -DDB_URL='jdbc:mysql://127.0.0.1:3308/vend_e2e_m16?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
       -jar target/vending-erp-backend-0.1.0-SNAPSHOT.jar --server.port=8085

跑法:python3 verification/scripts/m16_e2e.py

硬验收(冲刺0基准 sprint0/对平报告.md):
  总毛利 vs 9058.42 误差 <1%;2026-06 vs 937.22、2026-07 vs 8121.20 各误差 <2%。
  (SP068 无采购史 → 系统按 §13 显「—(成本待补)」不计毛利;一码多品按「拆分新码」清洗,
   与老表混合池的算法差异已在预演中确认 <0.5%,见 verification/M1-6.md)
"""
import json
import sys

import requests

BASE = 'http://127.0.0.1:8085/api'
XLSX = '/Users/yh-1/系统开发/智慧园区/小卖铺数据表8.4/小卖铺数据表8.4/自助售卖机业务进销存套表(更新8.1).xlsx'
HDR = {'X-User-Name': 'e2e'}

# 冲刺0基准
TRUTH_TOTAL_GP = 9058.42
TRUTH_JUNE_GP = 937.22
TRUTH_JULY_GP = 8121.20
TRUTH_PURCHASE = 27838.54
TRUTH_SALE = 25113.50


def call(method, path, **kw):
    r = requests.request(method, BASE + path, timeout=300, **kw)
    r.raise_for_status()
    body = r.json()
    if body.get('code') != 200:
        raise SystemExit(f'API 失败 {path}: {body.get("message")}')
    return body['data']


def upload(step):
    with open(XLSX, 'rb') as f:
        return call('POST', f'/v1/imports/initial/step{step}/upload',
                    files={'file': ('套表.xlsx', f, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')})


def main():
    status = call('GET', '/v1/imports/initial/status')
    print('向导状态:', json.dumps({k: v.get('done') if isinstance(v, dict) else v
                                    for k, v in status.items()}, ensure_ascii=False))

    # ---------- 第①步:商品档案+别名(一码多品全部选「拆分」) ----------
    if not status['step1']['done']:
        p1 = upload(1)
        print(f"①预览:商品 {p1['productCount']} · 别名 {p1['aliasCount']} · 机器 {p1['machineCount']}"
              f" · 冲突 {[c['code'] for c in p1['conflicts']]} · 补建 {p1['autoCreateCodes']}")
        resolutions = [{'code': c['code'], 'mode': 'split'} for c in p1['conflicts']]
        r1 = call('POST', '/v1/imports/initial/step1/confirm', headers=HDR,
                  json={'token': p1['token'], 'resolutions': resolutions})
        print('①确认:', json.dumps(r1, ensure_ascii=False))

    # ---------- 第②步:历史采购 ----------
    status = call('GET', '/v1/imports/initial/status')
    if not status['step2']['done']:
        p2 = upload(2)
        print(f"②预览:{p2['rowCount']} 行 · 金额 {p2['totalAmt']} · 缺档案 {p2['missingProducts']}")
        r2 = call('POST', f"/v1/imports/initial/step2/confirm?token={p2['token']}", headers=HDR)
        print('②确认:', json.dumps(r2, ensure_ascii=False))

    # ---------- 第③步:历史销售 ----------
    status = call('GET', '/v1/imports/initial/status')
    if not status['step3']['done']:
        p3 = upload(3)
        print(f"③预览:{p3['rowCount']} 行 · 实收 {p3['totalAmt']}")
        r3 = call('POST', f"/v1/imports/initial/step3/confirm?token={p3['token']}", headers=HDR)
        print('③确认:', json.dumps({k: r3[k] for k in ('rowOk', 'rowDup', 'rowFail', 'pendingBind')},
                                    ensure_ascii=False))

    # ---------- 对平校验(期初完成判定) ----------
    v = call('POST', '/v1/imports/initial/validate', headers=HDR,
             json={'expectedPurchase': TRUTH_PURCHASE, 'expectedSale': TRUTH_SALE})
    print('对平校验:', json.dumps(v, ensure_ascii=False))
    assert v['pass'], '期初对平未通过!'

    # ---------- 毛利报表对平(硬验收) ----------
    june = call('GET', '/v1/report/gross-margin', params={'month': '2026-06', 'dim': 'sku'})
    july = call('GET', '/v1/report/gross-margin', params={'month': '2026-07', 'dim': 'sku'})
    gp_june = float(june['totalGrossProfit'])
    gp_july = float(july['totalGrossProfit'])
    gp_total = gp_june + gp_july
    sales_total = float(june['totalSalesAmt']) + float(july['totalSalesAmt'])

    err_total = (gp_total - TRUTH_TOTAL_GP) / TRUTH_TOTAL_GP
    err_june = (gp_june - TRUTH_JUNE_GP) / TRUTH_JUNE_GP
    err_july = (gp_july - TRUTH_JULY_GP) / TRUTH_JULY_GP

    print('\n========== M1-6 端到端对平结果 ==========')
    print(f'销售额合计: {sales_total:.2f}(基准 {TRUTH_SALE})')
    print(f'2026-06 毛利: {gp_june:.2f} vs {TRUTH_JUNE_GP} → {err_june:+.4%} {"✅<2%" if abs(err_june) < 0.02 else "❌"}')
    print(f'2026-07 毛利: {gp_july:.2f} vs {TRUTH_JULY_GP} → {err_july:+.4%} {"✅<2%" if abs(err_july) < 0.02 else "❌"}')
    print(f'总毛利:     {gp_total:.2f} vs {TRUTH_TOTAL_GP} → {err_total:+.4%} {"✅<1%" if abs(err_total) < 0.01 else "❌"}')
    print(f'无成本 SKU: 6月 {june["noCostCount"]} 个 · 7月 {july["noCostCount"]} 个(SP068 成本待补,毛利显「—」)')

    ok = abs(err_total) < 0.01 and abs(err_june) < 0.02 and abs(err_july) < 0.02
    print('判定:', '✅ 对平通过' if ok else '❌ 对不平')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()

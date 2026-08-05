#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S0-1 辅助: 把每个 sheet 的前 N 行原样 dump 出来, 供人工/AI 判断表头位置与列含义"""
import sys, openpyxl, xlrd

BASE = '/Users/yh-1/系统开发/智慧园区/小卖铺数据表8.4/小卖铺数据表8.4/'
MAIN = BASE + '自助售卖机业务进销存套表(更新8.1).xlsx'

def dump_xlsx(path, nrows=12):
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    for ws in wb.worksheets:
        print(f"===== SHEET: {ws.title}  ({ws.max_row} rows x {ws.max_column} cols) =====")
        for i, row in enumerate(ws.iter_rows(min_row=1, max_row=nrows, values_only=True)):
            print(i+1, [str(c)[:22] if c is not None else '' for c in row])
        print()

def dump_xls(path, nrows=12):
    book = xlrd.open_workbook(path)
    for sh in book.sheets():
        print(f"===== SHEET: {sh.name}  ({sh.nrows} rows x {sh.ncols} cols) =====")
        for i in range(min(nrows, sh.nrows)):
            print(i+1, [str(c)[:22] for c in sh.row_values(i)])
        print()

if __name__ == '__main__':
    target = sys.argv[1] if len(sys.argv) > 1 else 'main'
    if target == 'main':
        dump_xlsx(MAIN)
    elif target == 'qiu':
        dump_xls(BASE + '7.27拿货数量价格(小邱统计表).xls')
    elif target == 'supplier':
        dump_xlsx(BASE + '原供应商采购费用明细.xlsx')

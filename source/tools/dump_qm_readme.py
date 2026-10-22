# -*- coding: utf-8 -*-
"""打印各「分页」sheet 的前几行，抽取分类说明（只读）。"""
import io
import os
import sys

import openpyxl

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

BASE = r"C:\Users\Estiavl\Desktop\RUBIK"
FILES = [
    "浅梦's 角块UFR分类公式集 202602版.xlsx",
    "浅梦's 棱块UF分类公式集 202602版.xlsx",
]

for name in FILES:
    path = os.path.join(BASE, name)
    print("=" * 78)
    print("FILE:", name)
    wb = openpyxl.load_workbook(path, data_only=True)
    for ws in wb.worksheets:
        if ws.title.strip() in ("说明", "编码"):
            continue
        print("---- sheet %r ----" % ws.title)
        for i, row in enumerate(ws.iter_rows(min_row=1, max_row=4, values_only=True), 1):
            cells = ["" if c is None else str(c).strip().replace("\n", " / ") for c in row]
            line = " | ".join(cells).rstrip(" |")
            if line.strip():
                print("  r%d: %s" % (i, line[:400]))

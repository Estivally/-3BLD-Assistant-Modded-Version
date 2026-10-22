#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成「818 公式熟练度」页用的精简公式库（src/assets/alg818.txt）。

为什么要有这个文件
------------------
App 里现成的公式库是 WebView 用的 JSON：
    cornerAlgToInfo.json   0.80 MB / 1008 键
    edgeAlgToInfo.json     6.91 MB / 1760 键
单条少则 18 个算法、多则 100 个，键还包含同一 case 的多种旋转写法。
原生页面只需要「每个 case 一条」，用不着整库，也扛不住 7 MB JSON 解析。

818 的口径
----------
三盲 818 = 角块 378 + 棱块 440。在这份数据里正好是：
    角块：所有以 J 开头的键（378 个，两两配对不同）
    棱块：所有以 G 开头的键（440 个，两两配对不同）
J / G 就是默认编码方案下角块、棱块的缓冲位置，所以这一组键
= 「每个 case 恰好一条」的规范形。

输出格式（一行一个 case，制表符分隔）
--------------------------------------
    TYPE \t KEY \t REC \t ALG1 \t ALG2 \t ...
TYPE  C=角块 / E=棱块
KEY   如 JDM / GAC（前两位就是联想词编码对）
REC   推荐算法下标（来自 *AlgToNightmare.json，-1 表示没有）
ALG*  该 case 的全部算法，保持原库顺序

用法：python make_alg818_assets.py
"""
import os
import sys
import json

ROOT = os.path.dirname(os.path.abspath(__file__))
JSON_DIR = os.path.join(ROOT, 'src', 'assets', 'assets', 'json')
OUT = os.path.join(ROOT, 'src', 'assets', 'alg818.txt')

CORNER_PREFIX = 'J'
EDGE_PREFIX = 'G'
CORNER_EXPECT = 378
EDGE_EXPECT = 440


def load(name):
    with open(os.path.join(JSON_DIR, name + '.json'), encoding='utf-8') as f:
        return json.load(f)


def build(specs):
    """specs: [(type, prefix, info_map, nightmare_map)] -> 行列表"""
    lines = []
    for typ, prefix, info, nightmare in specs:
        keys = sorted(k for k in info if k.startswith(prefix))
        for key in keys:
            algs = info[key]
            rec = -1
            sel = nightmare.get(key)
            if isinstance(sel, str) and sel in algs:
                rec = algs.index(sel)
            lines.append([typ, key, str(rec)] + list(algs))
    return lines


def main():
    corner_info = load('cornerAlgToInfo')
    corner_nm = load('cornerAlgToNightmare')
    edge_info = load('edgeAlgToInfo')
    edge_nm = load('edgeAlgToNightmare')

    lines = build([
        ('C', CORNER_PREFIX, corner_info, corner_nm),
        ('E', EDGE_PREFIX, edge_info, edge_nm),
    ])

    # ---------------- 自检 ----------------
    bad = []
    if len(lines) != CORNER_EXPECT + EDGE_EXPECT:
        bad.append('总行数 %d != 818' % len(lines))
    n_corner = sum(1 for x in lines if x[0] == 'C')
    n_edge = sum(1 for x in lines if x[0] == 'E')
    if n_corner != CORNER_EXPECT:
        bad.append('角块 %d != %d' % (n_corner, CORNER_EXPECT))
    if n_edge != EDGE_EXPECT:
        bad.append('棱块 %d != %d' % (n_edge, EDGE_EXPECT))

    keys = [x[1] for x in lines]
    if len(set(keys)) != len(keys):
        bad.append('键有重复')
    # 编码对只要求「同一类型内不重复」：角块与棱块共用同一套字母对，跨类型重合是正常的
    for typ in ('C', 'E'):
        pairs = [x[1][1:] for x in lines if x[0] == typ]
        if len(set(pairs)) != len(pairs):
            bad.append('%s 类编码对有重复' % typ)
    for x in lines:
        if x[0] == 'C' and not x[1].startswith(CORNER_PREFIX):
            bad.append('角块键前缀错: %s' % x[1])
        if x[0] == 'E' and not x[1].startswith(EDGE_PREFIX):
            bad.append('棱块键前缀错: %s' % x[1])
        if len(x[1]) != 3:
            bad.append('键长度错: %s' % x[1])
        if not x[3:]:
            bad.append('没有算法: %s' % x[1])
        for alg in x[3:]:
            if not alg.strip() or '\t' in alg or '\n' in alg:
                bad.append('算法内容异常: %s -> %r' % (x[1], alg))

    # 与原始库逐条比对
    for x in lines:
        typ, key, rec = x[0], x[1], int(x[2])
        src = corner_info if typ == 'C' else edge_info
        nm = corner_nm if typ == 'C' else edge_nm
        if list(x[3:]) != list(src[key]):
            bad.append('算法列表不一致: %s' % key)
        sel = nm.get(key)
        if rec >= 0:
            if x[3 + rec] != sel:
                bad.append('推荐下标错: %s' % key)
        elif isinstance(sel, str):
            bad.append('推荐算法不在列表里: %s' % key)

    if bad:
        print('!! 自检失败 %d 项：' % len(bad))
        for b in bad[:10]:
            print('   ', b)
        sys.exit(1)

    # ---------------- 写文件 ----------------
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        for x in lines:
            f.write('\t'.join(x))
            f.write('\n')

    size = os.path.getsize(OUT)
    n_algs = sum(len(x) - 3 for x in lines)
    print('已写出 %s' % OUT)
    print('  %d 行（角块 %d / 棱块 %d），共 %d 条算法，%.2f MB'
          % (len(lines), n_corner, n_edge, n_algs, size / 1048576.0))

    # ---------------- 回读校验 ----------------
    with open(OUT, encoding='utf-8') as f:
        back = [ln.rstrip('\n').split('\t') for ln in f]
    if len(back) != len(lines):
        print('!! 回读行数不一致')
        sys.exit(2)
    for a, b in zip(lines, back):
        if a != b:
            print('!! 回读不一致: %s' % a[1])
            sys.exit(3)
    print('  自检通过：%d/%d 行逐字段一致，推荐下标全部命中' % (len(back), len(lines)))


if __name__ == '__main__':
    main()

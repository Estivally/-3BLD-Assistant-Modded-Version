# -*- coding: utf-8 -*-
"""opt21_dimens_ladder —— 把 framework_dimens 的「连续微调值」吸附到阶梯

为什么只动 radius / text / space，不动 size
==========================================
fw_radius_* / fw_text_* / fw_space_* 是**节奏量**：取值本来就该落在有限的档位上，
现状 16 / 21 / 25 档里有大量 1dp、1sp 的连续微调（11dp、13dp、17sp、25dp…），
是历次手工调参的残留，吸附后视觉几乎无损但系统一致。

fw_size_*（75 档）是**部件尺寸**命名空间（108dp 的框、120dp 的区），
吸附会真的改变部件大小 → 不在本次范围。见文档「范围说明」。

只改 token 的 **value**，token 名一个不改 → 2612 处引用自动跟随。
"""
import os
import re
import sys
import shutil
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, 'src', 'res', 'values', 'framework_dimens.xml')
DIMEN_RE = re.compile(r'(<dimen\s+name="(?P<name>[A-Za-z0-9_]+)">)(?P<val>-?[\d.]+)(dp|sp)(</dimen>)')

# 圆角：0 / 4 / 8 / 12 / 16 / 20 / 999(pill)
RADIUS_LADDER = [0, 4, 8, 12, 16, 20, 999]

# 字号：12 档（保留 7~8sp 的微型标签位，避免中文小字溢出）
TEXT_LADDER = [8, 10, 12, 14, 16, 18, 20, 24, 28, 34, 40, 56]

# 间距：16 档（≤10 用 2dp 网格，之后用 4dp 网格；100 是页面级留白）
SPACE_LADDER = [0, 2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 56, 72, 100]

# 逐 token 的手工例外：视觉上必须保留的特殊值
EXCEPTIONS = {
    'fw_radius_999': 999,   # 胶囊
    'fw_space_100': 100,    # 页面级
}


def snap(v, ladder, bias_down=True):
    """吸附到阶梯上最近的一档；等距时按 bias_down 取小（避免中文文字溢出）。"""
    best = min(ladder, key=lambda x: (abs(x - v), x if bias_down else -x))
    return best


def family(name):
    m = re.match(r'^(fw_radius|fw_text|fw_space)_(\d+)$', name)
    return m.group(1) if m else None


def main():
    write = '--write' in sys.argv
    txt = open(TARGET, encoding='utf-8').read()
    lines = txt.split('\n')

    ladders = {'fw_radius': RADIUS_LADDER, 'fw_text': TEXT_LADDER, 'fw_space': SPACE_LADDER}
    stats = {}
    edits = []
    for i, ln in enumerate(lines):
        m = DIMEN_RE.search(ln)
        if not m:
            continue
        name, val, unit = m.group('name'), float(m.group('val')), m.group(4)
        fam = family(name)
        if not fam:
            continue
        ladder = ladders[fam]
        new = EXCEPTIONS.get(name, snap(val, ladder))
        if float(new) == val:
            continue
        edits.append((i, m.group(1), new, unit, m.group(5), name, val))
        st = stats.setdefault(fam, [0, 0])
        st[0] += 1

    # 档位统计（改前 / 改后）
    print('%-10s %8s %8s %8s' % ('family', '改前档位', '改后档位', '改动数'))
    for fam, ladder in ladders.items():
        before = set()
        for ln in lines:
            m = DIMEN_RE.search(ln)
            if m and family(m.group('name')) == fam:
                before.add(float(m.group('val')))
        after = set()
        for ln in lines:
            m = DIMEN_RE.search(ln)
            if m and family(m.group('name')) == fam:
                nm = m.group('name')
                after.add(float(EXCEPTIONS.get(nm, snap(float(m.group('val')), ladder))))
        print('%-10s %8d %8d %8d' % (fam, len(before), len(after), stats.get(fam, [0])[0]))

    if not write:
        print('\n[dry-run] %d 处待改，加 --write 落盘' % len(edits))
        return

    if not edits:
        print('已是目标状态（幂等）')
        return

    os.makedirs(os.path.join(HERE, 'tmp'), exist_ok=True)
    shutil.copy2(TARGET, os.path.join(HERE, 'tmp', 'framework_dimens.bak-' +
                                      datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.xml'))
    for i, pre, new, unit, post, name, old in edits:
        lines[i] = '%s%s%s%s' % (pre, new, unit, post)
    open(TARGET, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    print('写入 %d 处' % len(edits))


if __name__ == '__main__':
    main()

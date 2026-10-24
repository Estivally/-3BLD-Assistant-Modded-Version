# -*- coding: utf-8 -*-
"""opt22_dimens_rhythm —— 收敛 dimens.xml 里应用自有的语义 dimens（值吸附，不改名）

为什么有这个脚本
================
opt21 只覆盖 framework_dimens.xml 的 fw_* 家族。但应用真正在用的节奏量
（main_* / disrupt_* / other_* / read_* / radius_* / text_* …）全在 dimens.xml，
133 个里还有一批 3dp / 5dp / 9dp / 13sp / 19sp / 21sp 的连续微调。

为什么只改值、不改名
====================
这些 token 的名字是**语义**的（main_action_height / radius_md / other_row_height），
不含数值，所以改值不会让名字说谎。对照：fw_size_15 这种名字里写着 15 的，
改值就等于制造新一处「名字说谎」—— 所以本脚本不碰 fw_size_*。

安全边界
========
- 只处理 dimens.xml；framework_dimens.xml 归 opt21。
- 跳过 AOSP / 三方前缀（abc_ / notification_ / mtrl_ / design_ / compat_ / tooltip_ /
  cardview_ / fastscroll_ / item_touch_helper_ / *alpha_material_*）。
- 跳过无单位值（0.3 / 0.26 这类 alpha 常量）。
- **吸附位移上限**：dp 类 ≤2dp，sp 类 ≤1sp，radius 类 ≤4dp；超过上限一律保留原值
  （宁可不收敛，也不改变真实布局）。
- 幂等：吸附是幂等函数，第二次运行 0 改动。

用法
====
  python opt22_dimens_rhythm.py            # 报告
  python opt22_dimens_rhythm.py --write    # 备份 + 落盘
"""
import os
import re
import sys
import shutil
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, 'src', 'res', 'values', 'dimens.xml')

DIMEN_RE = re.compile(r'(<dimen\s+name="(?P<name>[A-Za-z0-9_]+)">)(?P<val>-?[\d.]+)(dp|sp|dip)(</dimen>)')

# 跳过：AOSP / 三方 / 框架前缀
SKIP_PREFIX = ('abc_', 'notification_', 'mtrl_', 'design_', 'compat_', 'tooltip_',
               'cardview_', 'fastscroll_', 'item_touch_helper_', 'preference_',
               'browser_', 'exo_', 'material_')
SKIP_CONTAINS = ('alpha_material', 'shadow_radius')  # shadow_radius 是阴影模糊半径，不是圆角

# 只收敛「真的被引用」的 token：AOSP 遗留但没人用的（subtitle_*、fab_*、cardview_* 等）
# 不动 —— 改它们是给审计凑数字，不是修节奏。
RES_DIR = os.path.join(HERE, 'src', 'res')
REF_RE = re.compile(r'@dimen/([A-Za-z0-9_]+)')

# 圆角：7 档（P2-6 的语义档位，命名规则见 ui_dimens_spec.py）
RADIUS_LADDER = [0, 8, 12, 16, 20, 24, 999]

# 间距/尺寸：2dp 网格到 40，之后 4dp 网格到 160，再往上 8dp 网格
SPACE_LADDER = sorted(set(list(range(0, 41, 2)) + list(range(40, 161, 4)) + list(range(160, 401, 8))))

# 字号：12 档（与 opt21 一致）
TEXT_LADDER = [8, 10, 12, 14, 16, 18, 20, 24, 28, 34, 40, 56]

# 位移上限
MAX_DELTA = {'radius': 4.0, 'space': 2.0, 'text': 1.0}


def snap(v, ladder):
    return min(ladder, key=lambda x: (abs(x - v), x))


def classify(name, unit):
    if unit == 'sp':
        return 'text'
    if 'radius' in name or name.endswith('_corner'):
        return 'radius'
    return 'space'


def skipped(name):
    if name.startswith(SKIP_PREFIX):
        return True
    return any(k in name for k in SKIP_CONTAINS)


def referenced_names():
    """扫描 src/res 下所有非 values/ 文件，收集 @dimen/NAME 引用。"""
    refs = set()
    skip_files = {'dimens.xml', 'framework_dimens.xml', 'public.xml'}
    for root, dirs, files in os.walk(RES_DIR):
        for fn in files:
            if not fn.endswith('.xml') or fn in skip_files:
                continue
            try:
                body = open(os.path.join(root, fn), encoding='utf-8', errors='ignore').read()
            except OSError:
                continue
            refs.update(REF_RE.findall(body))
    return refs


def smali_blob():
    """smali 会按名字运行时查 dimen（ThemeSettingActivity 查 radius_sm/radius_md 等），
    所以「XML 没引用」不等于「没人用」—— 把 smali 全文拼成一个大串来判定。"""
    parts = []
    smali_dir = os.path.join(HERE, 'src', 'smali')
    for root, dirs, files in os.walk(smali_dir):
        for fn in files:
            if fn.endswith('.smali'):
                try:
                    parts.append(open(os.path.join(root, fn), encoding='utf-8',
                                      errors='ignore').read())
                except OSError:
                    pass
    return '\n'.join(parts)


def main():
    write = '--write' in sys.argv
    txt = open(TARGET, encoding='utf-8').read()
    lines = txt.split('\n')
    refs = referenced_names()
    smali = smali_blob()
    unused = []

    ladders = {'radius': RADIUS_LADDER, 'space': SPACE_LADDER, 'text': TEXT_LADDER}
    edits, kept, before_by_kind = [], [], {}
    stats = {}

    for i, ln in enumerate(lines):
        m = DIMEN_RE.search(ln)
        if not m:
            continue
        name, val, unit = m.group('name'), float(m.group('val')), m.group(4)
        if skipped(name):
            continue
        if name not in refs and name not in smali:
            unused.append(name)
            continue
        kind = classify(name, unit)
        ladder = ladders[kind]
        before_by_kind.setdefault(kind, set()).add(val)
        target = snap(val, ladder)
        if abs(target - val) > MAX_DELTA[kind]:
            kept.append((name, val, unit, target))
            stats.setdefault(kind, [0, 0])[1] += 1
            continue
        if target == val:
            continue
        edits.append((i, m.group(1), target, unit, m.group(5), name, val, kind))
        stats.setdefault(kind, [0, 0])[0] += 1

    print('%-8s %8s %8s %8s' % ('kind', '改前档位', '改动数', '超限保留'))
    for kind in ('radius', 'space', 'text'):
        n_before = len(before_by_kind.get(kind, ()))
        e, k = stats.get(kind, [0, 0])
        print('%-8s %8d %8d %8d' % (kind, n_before, e, k))

    print('\n共 %d 处可吸附，%d 处因位移超限保留原值，%d 处无人引用跳过'
          % (len(edits), len(kept), len(unused)))
    if unused:
        print('无人引用（不动）：' + ', '.join(unused))
    if edits:
        print('\n改动明细：')
        for _, _, target, unit, _, name, val, kind in edits:
            print('  %-40s %6g%s -> %6g%s' % (name, val, unit, target, unit))
    if kept:
        print('\n超限保留（位移 > %s）：' % MAX_DELTA)
        for name, val, unit, target in kept:
            print('  %-40s %6g%s  (最近档 %g)' % (name, val, unit, target))

    if not write:
        print('\n[dry-run] 加 --write 落盘')
        return
    if not edits:
        print('\n已是目标状态（幂等）')
        return

    os.makedirs(os.path.join(HERE, 'tmp'), exist_ok=True)
    shutil.copy2(TARGET, os.path.join(HERE, 'tmp', 'dimens.bak-' +
                                      datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.xml'))
    for i, pre, target, unit, post, name, val, kind in edits:
        lines[i] = '%s%g%s%s' % (pre, target, unit, post)
    open(TARGET, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    print('\n写入 %d 处 -> %s' % (len(edits), TARGET))


if __name__ == '__main__':
    main()

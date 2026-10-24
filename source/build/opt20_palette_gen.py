# -*- coding: utf-8 -*-
"""opt20_palette_gen —— 语义调色板生成器（theme_schemes.xml 取值收敛）

做什么
======
把 8 套 overlay 里 147 个 app_lit_* 历史自由值，按语义角色改写取值，
**一个 attr 名都不改**，所以 2612 处布局引用自动跟着变。

为什么不会编译断裂
==================
只替换 <item ...>#xxxxxx</item> 里的颜色文本，item 名/顺序/缩进/文件结构全不动。

幂等性从哪来
============
角色判定**只看 attr 名字里的基准 hex**（作者当初按 indigo 方案写的），
在 indigo 浅色这一套上做一次 ΔE 吸附，得到一张全局 role map，8 套共用。
不读当前值 → 连跑两次结果完全一致。

merge 语义
==========
只改写「本脚本认识的、且属于 172 个已知 attr」的 item。opt19 注入的
app_nav_pill / app_dial_ring 等不在 172 名单内，原样保留 → 与 opt19 顺序解耦。

用法
====
  python opt20_palette_gen.py            # 报告，不写文件
  python opt20_palette_gen.py --write    # 备份 + 原地改写
"""
import os
import re
import sys
import shutil
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ui_palette_spec import (THRESH, OVERRIDE, BG_ROLES, FG_ROLES,  # noqa: E402
                             SEMANTIC_ALIAS, SOFTABLE, derive)
import glob  # noqa: E402

THEME = os.path.join(HERE, 'src', 'res', 'values', 'theme_schemes.xml')
COLORS = os.path.join(HERE, 'src', 'res', 'values', 'colors.xml')
BADGE_TEXT = '#047857'   # 联想词徽章文字色
INDIGO = 'ThemeOverlay.App.Scheme.indigo'

STYLE_RE = re.compile(r'<style\s+name="(ThemeOverlay\.App\.Scheme\.[A-Za-z.]+)"')
ITEM_RE = re.compile(
    r'^(?P<indent>\s*)<item\s+name="(?P<name>app_[A-Za-z0-9_]+)">'
    r'(?P<value>#[0-9a-fA-F]{3,8})</item>\s*$')

# 25 个语义锚点：这些 item 只当「取值来源」，M1 阶段不改它们的值
ANCHORS = [
    'app_background', 'app_surface', 'app_line', 'app_black',
    'app_colorPrimary', 'app_colorPrimaryDark', 'app_colorPrimaryLight', 'app_colorAccent',
    'app_green_text', 'app_text_disabled',
    'app_lit_text_dc2626', 'app_lit_text_b45309', 'app_lit_text_2563eb',
]


# ---------- 颜色工具 ----------

def parse_color(s):
    h = s.lstrip('#')
    if len(h) == 3:
        h = ''.join(c * 2 for c in h)
    if len(h) == 6:
        h = 'ff' + h
    if len(h) != 8:
        return None
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16))


def fmt_color(c):
    return '#%02x%02x%02x%02x' % c


def base_hex_of_name(name):
    """app_lit_bg_1a6366f1 -> '6366f1'；app_lit_text_000 -> '000000'"""
    m = re.match(r'app_lit_(?:bg|text|tint)_([0-9a-fA-F]{3,8})$', name)
    if not m:
        return None
    h = m.group(1).lower()
    if len(h) == 3:
        h = ''.join(c * 2 for c in h)
    elif len(h) == 4:
        h = h[1:] * 2 if False else ''.join(c * 2 for c in h[1:])
    elif len(h) == 8:
        h = h[2:]
    return h if len(h) == 6 else None


def _lin(v):
    v /= 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def lab(c):
    r, g, b = _lin(c[1]), _lin(c[2]), _lin(c[3])
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = (0.2126 * r + 0.7152 * g + 0.0722 * b)
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3.0) if t > 0.008856 else 7.787 * t + 16 / 116.0
    fx, fy, fz = f(x), f(y), f(z)
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def dE(c1, c2):
    return sum((a - b) ** 2 for a, b in zip(lab(c1), lab(c2))) ** 0.5


def lum(c):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[1]) + 0.7152 * ch(c[2]) + 0.0722 * ch(c[3])


def contrast(c1, c2):
    a, b = lum(c1) + 0.05, lum(c2) + 0.05
    return max(a, b) / min(a, b)


# ---------- 解析 ----------

def parse(path):
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.read().split('\n')
    overlays = {}
    cur = None
    for i, ln in enumerate(lines):
        m = STYLE_RE.search(ln)
        if m:
            cur = m.group(1)
            overlays.setdefault(cur, {})
            continue
        if ln.strip() == '</style>':
            cur = None
            continue
        if cur is None:
            continue
        m = ITEM_RE.match(ln)
        if m:
            overlays[cur][m.group('name')] = (i, m.group('indent'), m.group('value'))
    return lines, overlays


# P1-3 主色锚点下探（indigo-500 #6366f1 → indigo-600 #4f46e5）：
# 白字主按钮对比 4.47:1 → ≈6.4:1，越过 AA 4.5。只改这一个 item 的**取值**，
# 172 个 attr 名一个不动；所有 app_lit_* 角色色随 derive() 自动重算。
ANCHOR_OVERRIDE = {
    'ThemeOverlay.App.Scheme.indigo': {'app_colorPrimary': '#ff4f46e5'},
}

# 提示文字色 app_text_hint：8 套 overlay 里都是 #61xxxxxx —— 38% alpha 的浅灰蓝，
# 压在卡片上只有 1.32:1（浅色）/ 1.50:1（深色），远低于 AA 4.5，输入框 placeholder
# 基本看不见。它是**极性色**（浅色要暗、深色要亮），所以浅/深各给一个中性值：
#   浅 #8a000000 = 54% 黑 → 4.60:1
#   深 #8affffff = 54% 白 → 5.88:1
POLARITY_OVERRIDE = {
    'app_text_hint': ('#8a000000', '#8affffff'),
}


def anchors_of(ov, ov_name=None):
    d = {}
    ovr = ANCHOR_OVERRIDE.get(ov_name, {})
    for a in ANCHORS:
        if a not in ov:
            raise SystemExit('overlay %s 缺少锚点 %s' % (ov, a))
        d[a] = parse_color(ovr[a] if a in ovr else ov[a][2])
    return d


# ---------- 徽章文字色（P0 可读性） ----------

def ensure_badge_text(write=False):
    """联想词徽章文字色：全工程只有 res/color/remember_assoc_badge_on_text.xml 引用。

    #059669 在 success_container(#e1f2ed) 上只有 3.25:1，低于 AA 4.5；
    #047857 是 4.73:1。这是一条 P0 级可读性修复，放进脚本兜住，
    免得重建时被从 APK 重新解包出来的 colors.xml 冲掉。
    """
    txt = open(COLORS, encoding='utf-8').read()
    new, n = re.subn(r'<color name="green_text">#[0-9a-fA-F]{6}</color>',
                     '<color name="green_text">%s</color>' % BADGE_TEXT, txt)
    if not n:
        return '未找到 green_text（跳过）'
    if new == txt:
        return '已是 %s（幂等）' % BADGE_TEXT
    if write:
        open(COLORS, 'w', encoding='utf-8', newline='\n').write(new)
        return '已改为 %s' % BADGE_TEXT
    return '待改为 %s' % BADGE_TEXT


# ---------- 描边专用色：从 drawable 的 <stroke> 里反查 ----------

def stroke_names():
    """哪些 app_lit_* 被当作 <stroke android:color="?attr/..."/> 用过。

    同一个绿，做「填充底色」和做「边框线」需要不同深浅 —— 收敛到同一个值
    会让徽章的边框和底色变成一色，边框直接消失。所以描边色单独走 *_soft 档。
    """
    out = set()
    for f in glob.glob(os.path.join(HERE, 'src', 'res', 'drawable*', '*.xml')):
        txt = open(f, encoding='utf-8').read()
        for m in re.finditer(r'<stroke[^>]*?color="\?attr/(app_[A-Za-z0-9_]+)"', txt, re.S):
            out.add(m.group(1))
    return out


# ---------- 角色判定（只看名字，跨 8 套共用） ----------

def build_role_map(indigo_ov, strokes=frozenset()):
    """返回 {attr_name: role or 'keep' or None}"""
    A = derive(dict(anchors_of(indigo_ov), __dark__=False))
    pool = []
    for r in BG_ROLES + FG_ROLES:
        if r in A:
            pool.append((r, A[r]))
    seen = set()
    out = {}
    for name in indigo_ov:
        if name in seen or name in ANCHORS or name in SEMANTIC_ALIAS:
            continue
        seen.add(name)
        bh = base_hex_of_name(name)
        if not bh:
            out[name] = None
            continue
        role = OVERRIDE.get(bh)
        if role is None:
            rgb = (255,) + (int(bh[0:2], 16), int(bh[2:4], 16), int(bh[4:6], 16))
            best, bd = None, 1e9
            for r, anc in pool:
                dd = dE(rgb, anc)
                if dd < bd:
                    best, bd = r, dd
            role = best if bd <= THRESH else None
        if role in ('primary_container', 'success_container', 'danger_container',
                    'warning_container', 'info_container') and name in strokes:
            role = role.replace('_container', '_soft')
        out[name] = role
    return out


# ---------- 主流程 ----------

def main():
    write = '--write' in sys.argv
    lines, overlays = parse(THEME)
    if len(overlays) != 8:
        raise SystemExit('期望 8 套 overlay，实际 %d' % len(overlays))

    strokes = stroke_names()
    print('描边专用 app_lit_*: %d 个' % len(strokes))
    role_map = build_role_map(overlays[INDIGO], strokes)
    n_keep = sum(1 for v in role_map.values() if v in (None, 'keep'))
    print('role map: %d 个 app_lit_*  →  %d 归类, %d 保留原值'
          % (len(role_map), len(role_map) - n_keep, n_keep))

    edits = []          # (lineno, indent, name, old, new)
    stats = []
    for ov_name, ov in overlays.items():
        dark = ov_name.endswith('.Night')
        A = derive(dict(anchors_of(ov, ov_name), __dark__=dark))
        ovr = ANCHOR_OVERRIDE.get(ov_name, {})
        before, after = set(), set()
        changed = 0
        for name, (lineno, indent, value) in ov.items():
            old = parse_color(value)
            if old is None:
                continue
            before.add(value.lower())
            if name in POLARITY_OVERRIDE:                # 极性覆盖（浅/深各一值）
                nv = POLARITY_OVERRIDE[name][1 if dark else 0]
                after.add(nv)
                if nv != value.lower():
                    changed += 1
                    edits.append((lineno, indent, name, value, nv))
                continue
            if name in ovr:                              # P1-3 锚点显式覆盖
                nv = ovr[name]
                after.add(nv)
                if nv != value.lower():
                    changed += 1
                    edits.append((lineno, indent, name, value, nv))
                continue
            if name in ANCHORS or name in role_map or name in SEMANTIC_ALIAS:
                role = SEMANTIC_ALIAS.get(name) or role_map.get(name)
                if role in (None, 'keep'):
                    after.add(value.lower())
                    continue
                rgb = A[role]
                new = (old[0], rgb[1], rgb[2], rgb[3])   # 保留原有 alpha
            else:
                after.add(value.lower())                 # 未知 item：merge 保留
                continue
            nv = fmt_color(new)
            after.add(nv)
            if nv != value.lower():
                changed += 1
                edits.append((lineno, indent, name, value, nv))
        stats.append((ov_name, len(before), len(after), changed))

    print('\n%-42s %8s %8s %8s' % ('overlay', '改前色数', '改后色数', '改动条数'))
    for ov_name, b, a, c in stats:
        print('%-42s %8d %8d %8d' % (ov_name.replace('ThemeOverlay.App.Scheme.', ''), b, a, c))

    # 覆盖率：每个角色被多少 item 用到
    used = {}
    for name, role in role_map.items():
        if role and role != 'keep':
            used[role] = used.get(role, 0) + 1
    print('\n角色覆盖（共 %d 个角色被使用）:' % len(used))
    for r, n in sorted(used.items(), key=lambda x: -x[1]):
        print('  %-20s %3d' % (r, n))

    if not write:
        print('\n[dry-run] 共 %d 处待改，加 --write 落盘' % len(edits))
        return

    print('\n徽章文字色: %s' % ensure_badge_text(write))

    if not edits:
        print('已是目标状态，无需改动（幂等）')
        return

    # 备份（★ 不能放在 res/values/ 里 —— aapt2 会把 .bak-* 当非法资源路径直接报错）
    os.makedirs(os.path.join(HERE, 'tmp'), exist_ok=True)
    bak = os.path.join(HERE, 'tmp', 'theme_schemes.bak-' +
                       datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.xml')
    shutil.copy2(THEME, bak)
    print('\n备份 -> %s' % os.path.basename(bak))

    for lineno, indent, name, old, new in edits:
        lines[lineno] = '%s<item name="%s">%s</item>' % (indent, name, new)
    with open(THEME, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print('写入 %d 处 -> %s' % (len(edits), THEME))


if __name__ == '__main__':
    main()
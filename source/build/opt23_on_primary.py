# -*- coding: utf-8 -*-
"""opt23_on_primary —— 主色填充按钮的标签色在深色下翻转为「卡片色」

问题
====
布局里主色按钮（开始作答 / 确定 / 提交 …）的标签是硬编码
    android:textColor="@android:color/white"
浅色下没问题（白字 + #4f46e5 填充 = 6.3:1）。
但深色方案的 app_colorPrimary 是亮紫 #818cf8，白字压上去只有 3.0:1 —— 低于 AA 4.5。

为什么不用新增 app_on_primary attr
==================================
新增 attr 会让 aapt 重新分配 attr ID，而 src/smali 里 R$attr 常量是硬编码的，
加一个 attr 就可能把后面所有 ID 顶掉 → 编译过、运行崩。所以复用已有语义 attr：
    ?attr/app_surface   浅色 = #ffffff（白字，与原来完全一致）
                        深色 = #1a1d21（近黑标签，5.6:1）
这正是 Material「onPrimary」的极性，只是借用了卡片色这个已有的锚点。

判定规则（只改真正翻转的那些）
==============================
对每个「白字 + 有色填充」的视图，分别算
    浅色填充 vs 白字  → 若 < 4.5 跳过（说明本来就不是主色填充，白字是别的用意）
    深色填充 vs 白字  → 若 < 4.5 才改
所以只会命中「浅色深底白字、深色变亮底」的那批按钮，幂等。

用法
====
  python opt23_on_primary.py            # 报告
  python opt23_on_primary.py --write    # 落盘
"""
import os
import re
import sys
import glob
import shutil
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, 'src', 'res')
WHITE = '@android:color/white'
NEW = '?attr/app_surface'
THRESH = 4.5

ATTR_RE = re.compile(r'\?attr/(app_[A-Za-z0-9_]+)')
DRAWABLE_RE = re.compile(r'@drawable/([A-Za-z0-9_.]+)')
TEXTCOLOR_RE = re.compile(r'android:textColor="([^"]*)"')
BG_RE = re.compile(r'android:background="([^"]*)"')


def color(s):
    h = s.lstrip('#')
    if len(h) == 3:
        h = ''.join(c * 2 for c in h)
    if len(h) == 6:
        h = 'ff' + h
    if len(h) != 8:
        return None
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4, 6))


def lum(c):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[1]) + 0.7152 * ch(c[2]) + 0.0722 * ch(c[3])


def contrast(a, b):
    x, y = lum(a) + 0.05, lum(b) + 0.05
    return max(x, y) / min(x, y)


def load_schemes():
    p = os.path.join(RES, 'values', 'theme_schemes.xml')
    txt = open(p, encoding='utf-8').read()
    blocks = re.split(r'<style name="ThemeOverlay\.App\.Scheme\.', txt)
    ovs = {}
    for b in blocks[1:]:
        name = b.split('"')[0]
        ovs[name] = dict(re.findall(r'<item name="(app_[A-Za-z0-9_]+)">(#[0-9a-fA-F]+)</item>', b))
    return ovs


def drawable_fills(scheme):
    """drawable → 该方案下这个 drawable 可能出现的所有填充色（solid / gradient 两端）。"""
    fills = {}
    for f in glob.glob(os.path.join(RES, 'drawable*', '*.xml')):
        name = os.path.splitext(os.path.basename(f))[0]
        body = open(f, encoding='utf-8', errors='ignore').read()
        cols = []
        for m in re.finditer(r'(?:android:(?:color|startColor|endColor|centerColor))="([^"]+)"', body):
            v = m.group(1)
            if v.startswith('?attr/'):
                a = ATTR_RE.match(v)
                if a and a.group(1) in scheme:
                    cols.append(color(scheme[a.group(1)]))
            elif v.startswith('#'):
                cols.append(color(v))
        cols = [c for c in cols if c]
        if cols:
            fills[name] = cols
    return fills


def main():
    write = '--write' in sys.argv
    ovs = load_schemes()
    light, dark = ovs['indigo'], ovs['indigo.Night']
    fills_light = drawable_fills(light)
    fills_dark = drawable_fills(dark)
    white = color('#ffffffff')

    def worst_fill(name, table):
        cols = table.get(name)
        if not cols:
            return None
        return min(cols, key=lambda c: contrast(white, c))   # 对比度最差的那个端点

    edits = []
    skipped = []
    for f in sorted(glob.glob(os.path.join(RES, 'layout', '*.xml'))):
        body = open(f, encoding='utf-8').read()
        lines = body.split('\n')
        changed = False
        for i, ln in enumerate(lines):
            tc = TEXTCOLOR_RE.search(ln)
            if not tc or tc.group(1) != WHITE:
                continue
            bg = BG_RE.search(ln)
            if not bg:
                skipped.append((os.path.basename(f), i + 1, 'no-background'))
                continue
            raw = bg.group(1)
            fill_l = fill_d = None
            m = DRAWABLE_RE.search(raw)
            if m:
                fill_l, fill_d = worst_fill(m.group(1), fills_light), worst_fill(m.group(1), fills_dark)
            else:
                a = ATTR_RE.match(raw)
                if a:
                    fill_l = color(light[a.group(1)]) if a.group(1) in light else None
                    fill_d = color(dark[a.group(1)]) if a.group(1) in dark else None
            if not fill_l or not fill_d:
                skipped.append((os.path.basename(f), i + 1, 'unresolved:' + raw))
                continue
            c_light, c_dark = contrast(white, fill_l), contrast(white, fill_d)
            if c_light < THRESH:
                skipped.append((os.path.basename(f), i + 1,
                                'light-fill-too-light %.2f' % c_light))
                continue
            if c_dark >= THRESH:
                continue
            new_ln = TEXTCOLOR_RE.sub('android:textColor="%s"' % NEW, ln, count=1)
            lines[i] = new_ln
            changed = True
            edits.append((os.path.basename(f), i + 1, c_light, c_dark))
        if changed and write:
            open(f, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))

    print('需要翻转标签色：%d 处' % len(edits))
    for fn, ln, cl, cd in edits:
        print('  %-28s:%-4d 白字 浅%.2f:1 → 深%.2f:1' % (fn, ln, cl, cd))
    if skipped:
        print('\n跳过 %d 处（非主色填充 / 解析不到）：' % len(skipped))
        for fn, ln, why in skipped[:12]:
            print('  %-28s:%-4d %s' % (fn, ln, why))
    if not write:
        print('\n[dry-run] 加 --write 落盘')
        return
    if edits:
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
        os.makedirs(os.path.join(HERE, 'tmp'), exist_ok=True)
        for fn in sorted({e[0] for e in edits}):
            src = os.path.join(RES, 'layout', fn)
            shutil.copy2(src, os.path.join(HERE, 'tmp', fn + '.bak-' + stamp))
        print('\n写入 %d 处' % len(edits))


if __name__ == '__main__':
    main()

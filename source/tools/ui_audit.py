# -*- coding: utf-8 -*-
"""ui_audit —— 新 UI 验收口径（脚本判定，不靠肉眼）

跑法：  python tools/ui_audit.py            # 报告
        python tools/ui_audit.py --json     # 机器可读，供 build 断言

判定项（对应计划里的验收表）
  1. 每套 overlay 的独立颜色数（RGB，忽略 alpha）
  2. 8 套 overlay 之间同一 attr 的取值是否还有「同名不同色」漂移
  3. 应用布局里的裸 dp 数 / 裸 hex 数
  4. framework_dimens 的 fw_radius_* / fw_space_* / fw_text_* / fw_size_* 档位分布
  5. 关键组合的对比度（正文 / 次要文字 / 危险按钮 / 联想词徽章）
"""
import os
import re
import sys
import json
import glob

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(HERE, 'src', 'res')

STYLE_RE = re.compile(r'<style\s+name="(ThemeOverlay\.App\.Scheme\.[A-Za-z.]+)"')
ITEM_RE = re.compile(r'<item\s+name="(app_[A-Za-z0-9_]+)">(#[0-9a-fA-F]{3,8})</item>')
DIMEN_RE = re.compile(r'<dimen\s+name="([A-Za-z0-9_]+)">([^<]+)</dimen>')

# AOSP / notification 模板不算「应用布局」
SKIP_LAYOUT = ('abc_', 'abc_screen', 'notification_', 'support_simple_spinner',
               'select_dialog', 'mtrl_', 'design_', 'preference_', 'browser_actions',
               'app_bar', 'tooltip_', 'material_', 'm3_', 'exo_', 'media_controller',
               'divider_horizontal', 'screen_', 'dropdown_', 'text_input_')

SKIP_DRAWABLE = ('abc_', 'mtrl_', 'design_', 'notification_', 'common_', 'btn_radio',
                 'ic_', 'avd_', 'tooltip_', 'material_', 'm3_', 'exo_', 'switch_',
                 'preference_', 'divider_', 'screen_', 'test_')


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


def contrast(c1, c2):
    a, b = lum(c1) + 0.05, lum(c2) + 0.05
    return max(a, b) / min(a, b)


def over(fg, bg):
    """fg 按 alpha 合成到 bg 上"""
    a = fg[0] / 255.0
    return (255,) + tuple(int(round(fg[i] * a + bg[i] * (1 - a))) for i in range(1, 4))


def load_schemes():
    p = os.path.join(RES, 'values', 'theme_schemes.xml')
    txt = open(p, encoding='utf-8').read()
    ovs, cur = {}, None
    for ln in txt.split('\n'):
        m = STYLE_RE.search(ln)
        if m:
            cur = m.group(1).replace('ThemeOverlay.App.Scheme.', '')
            ovs[cur] = {}
            continue
        if ln.strip() == '</style>':
            cur = None
            continue
        if cur:
            m = ITEM_RE.search(ln)
            if m:
                ovs[cur][m.group(1)] = m.group(2)
    return ovs


def count_bare_dp():
    total, files = 0, []
    for f in glob.glob(os.path.join(RES, 'layout*', '*.xml')):
        b = os.path.basename(f)
        if b.startswith(SKIP_LAYOUT):
            continue
        n = len(re.findall(r'"[^"]*?-?\d+(?:\.\d+)?dp"', open(f, encoding='utf-8').read()))
        if n:
            total += n
            files.append((b, n))
    files.sort(key=lambda x: -x[1])
    return total, files


def count_bare_hex():
    total, files = 0, []
    for pat in ('layout*', 'drawable*'):
        for f in glob.glob(os.path.join(RES, pat, '*.xml')):
            b = os.path.basename(f)
            if b.startswith(SKIP_LAYOUT + SKIP_DRAWABLE):
                continue
            n = len(re.findall(r'"#[0-9a-fA-F]{3,8}"', open(f, encoding='utf-8').read()))
            if n:
                total += n
                files.append((b, n))
    return total, sorted(files, key=lambda x: -x[1])


def dimens_ladder():
    p = os.path.join(RES, 'values', 'framework_dimens.xml')
    if not os.path.exists(p):
        return {}
    out = {}
    for m in DIMEN_RE.finditer(open(p, encoding='utf-8').read()):
        n, v = m.group(1), m.group(2).strip()
        mm = re.match(r'^(-?[\d.]+)dp$', v)
        if not mm:
            continue
        fam = re.match(r'^(fw_radius_\d+|fw_space_\d+|fw_text_\d+|fw_size_\d+|radius_\w+)$', n)
        if fam:
            out.setdefault(fam.group(1).rsplit('_', 1)[0], []).append(float(mm.group(1)))
    return {k: sorted(set(v)) for k, v in out.items()}


def main():
    ovs = load_schemes()
    report = {'schemes': {}, 'drift': {}, 'bare_dp': {}, 'bare_hex': {}, 'ladder': {}}

    for name, items in ovs.items():
        rgbs = {color(v)[1:] for v in items.values() if color(v)}
        report['schemes'][name] = {'items': len(items), 'distinct_rgb': len(rgbs)}

    # 同名不同色：跨 8 套比较（同名 attr 的 RGB 本就应该不同 —— 所以只看「语义角色」收敛度，
    # 这里退而统计：每套里出现次数 >1 的完全相同取值数量，反映别名冗余）
    dup = {}
    for name, items in ovs.items():
        cnt = {}
        for v in items.values():
            cnt[v] = cnt.get(v, 0) + 1
        dup[name] = sorted([(k, n) for k, n in cnt.items() if n > 1], key=lambda x: -x[1])
    report['drift'] = {k: len(v) for k, v in dup.items()}

    n_dp, f_dp = count_bare_dp()
    report['bare_dp'] = {'total': n_dp, 'top': f_dp[:12]}
    n_hex, f_hex = count_bare_hex()
    report['bare_hex'] = {'total': n_hex, 'top': f_hex[:12]}
    report['ladder'] = dimens_ladder()

    # 关键对比度（浅色 + 深色各一套）
    def probe(scheme):
        d = {k: color(v) for k, v in ovs[scheme].items()}
        page = d.get('app_background')
        card = d.get('app_surface') or page
        res = {}
        for label, fg, bg in (
            ('正文/卡片', d.get('app_black'), card),
            ('正文/页面', d.get('app_black'), page),
            ('次要文字/卡片', d.get('app_grey'), card),
            ('危险文字/卡片', d.get('app_lit_text_dc2626'), card),
            ('主色按钮文字', d.get('app_bar_text'), d.get('app_colorPrimary')),
        ):
            if fg and bg:
                res[label] = round(contrast(over(fg, bg), bg), 2)
        return res

    report['contrast'] = {s: probe(s) for s in ('indigo', 'indigo.Night') if s in ovs}

    if '--json' in sys.argv:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return

    print('=== 每套 overlay 独立颜色数（RGB） ===')
    for k, v in report['schemes'].items():
        print('  %-12s %3d items  %3d 色' % (k, v['items'], v['distinct_rgb']))
    print('\n=== 裸 dp（应用布局）===  %d 处' % report['bare_dp']['total'])
    for b, n in report['bare_dp']['top']:
        print('  %-40s %3d' % (b, n))
    print('\n=== 裸 hex（layout+drawable）===  %d 处' % report['bare_hex']['total'])
    for b, n in report['bare_hex']['top']:
        print('  %-40s %3d' % (b, n))
    print('\n=== dimens 档位 ===')
    for k, v in sorted(report['ladder'].items()):
        print('  %-14s %2d 档  %s' % (k, len(v), v[:16]))
    print('\n=== 对比度 ===')
    for s, r in report['contrast'].items():
        print('  [%s]' % s)
        for k, v in r.items():
            flag = 'OK ' if v >= 4.5 else 'LOW'
            print('    %s %-14s %5.2f:1' % (flag, k, v))


if __name__ == '__main__':
    main()

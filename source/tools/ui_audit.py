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
sys.path.insert(0, HERE)
import ui_dimens_spec as SPEC   # 梯子与语义角色表的唯一事实来源（opt21/opt22 也用它）

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


# dimens.xml 里属于 AOSP / 三方模板的 token，不参与应用节奏判定
SKIP_AOSP_DIMEN = ('abc_', 'notification_', 'mtrl_', 'design_', 'compat_', 'tooltip_',
                   'cardview_', 'fastscroll_', 'item_touch_helper_', 'preference_',
                   'browser_', 'exo_', 'material_', 'elevation_', 'fab_',
                   'subtitle_', 'activity_horizontal_margin', 'activity_vertical_margin',
                   'button_height', 'spacing_')


def app_dimens_audit():
    """dimens.xml 里应用自有语义 token 的档位合规：不在梯子上的离群值。"""
    p = os.path.join(RES, 'values', 'dimens.xml')
    off = []
    total = 0
    for m in DIMEN_RE.finditer(open(p, encoding='utf-8').read()):
        n, v = m.group(1), m.group(2).strip()
        if n.startswith(SKIP_AOSP_DIMEN):
            continue
        mm = re.match(r'^(-?[\d.]+)(dp|sp|dip)$', v)
        if not mm:
            continue
        total += 1
        unit = 'sp' if mm.group(2) == 'sp' else 'dp'
        val = float(mm.group(1))
        kind = SPEC.classify_token(n, unit)
        ladder = SPEC.ladder_for(kind)
        if val not in ladder:
            off.append({'name': n, 'value': val, 'unit': unit,
                        'kind': kind, 'nearest': SPEC.snap(val, ladder)})
    return total, off


def radius_roles_audit():
    """圆角 token 的语义角色分布：每个角色应该只有一个值。"""
    role_of = {}
    unknown = []
    for fn in ('dimens.xml', 'framework_dimens.xml'):
        p = os.path.join(RES, 'values', fn)
        if not os.path.exists(p):
            continue
        for m in DIMEN_RE.finditer(open(p, encoding='utf-8').read()):
            n, v = m.group(1), m.group(2).strip()
            if 'radius' not in n and not n.endswith('_corner'):
                continue
            mm = re.match(r'^(-?[\d.]+)dp$', v)
            if not mm:
                continue
            val = float(mm.group(1))
            role = SPEC.radius_role(val)
            if role is None:
                unknown.append((n, val))
            else:
                role_of.setdefault(role, []).append((n, val))
    return role_of, unknown


# ---------- 深色分层 / 图标对比 / 矢量裸色 ----------

ELEV_MIN = 1.15      # 卡片与页底、描边与卡片的最小可分辨对比度


def elevation_audit(ovs):
    """每套 scheme 的三级层次：页底 → 卡片 → 描边。"""
    out = {}
    for name, items in ovs.items():
        bg, card, line = items.get('app_background'), items.get('app_surface'), items.get('app_line')
        if not (bg and card and line):
            continue
        b, c, l = over(color(bg), (255, 255, 255, 255)), over(color(card), over(color(bg), (255, 255, 255, 255))), color(line)
        dark = name.endswith('.Night')
        ok = contrast(c, b) >= ELEV_MIN and contrast(over(l, c), c) >= ELEV_MIN
        out[name] = {
            'bg': bg, 'card': card, 'line': line, 'dark': dark,
            'card_vs_bg': round(contrast(c, b), 2),
            'line_vs_card': round(contrast(over(l, c), c), 2),
            # 浅色靠阴影 + 描边分层，卡片本就该是「白纸压在白底上」，只审计不判定
            'ok': ok if dark else True,
        }
    return out


ICON_PROPS = ('android:src', 'android:drawableLeft', 'android:drawableRight',
              'android:drawableTop', 'android:drawableBottom', 'android:drawableStart',
              'android:drawableEnd', 'app:srcCompat')


def icon_drawables():
    """布局里被当图标用的 drawable（含矢量/形状）。"""
    used = {}
    for f in glob.glob(os.path.join(RES, 'layout*', '*.xml')):
        b = os.path.basename(f)
        if b.startswith(SKIP_LAYOUT):
            continue
        for i, ln in enumerate(open(f, encoding='utf-8').read().split('\n'), 1):
            for prop in ICON_PROPS:
                m = re.search(re.escape(prop) + r'="@(?:drawable|mipmap)/([A-Za-z0-9_]+)"', ln)
                if m:
                    used.setdefault(m.group(1), []).append('%s:%d' % (b, i))
    return used


def icon_contrast_audit(ovs, schemes=('indigo', 'indigo.Night')):
    """图标主色与卡片底的对比度（深色 <3:1 视为不易辨认）。"""
    used = icon_drawables()
    rows = []
    for nm, refs in sorted(used.items()):
        path = None
        for d in ('drawable', 'drawable-anydpi-v21', 'drawable-v21', 'drawable-hdpi', 'mipmap-anydpi-v26'):
            p = os.path.join(RES, d, nm + '.xml')
            if os.path.exists(p):
                path = p
                break
        if not path:
            continue
        txt = open(path, encoding='utf-8').read()
        if '<vector' not in txt and '<shape' not in txt:
            continue
        attrs = re.findall(r'(?:android:)?(fillColor|strokeColor|color)="\?attr/(app_[A-Za-z0-9_]+)"', txt)
        if not attrs:
            continue
        row = {'icon': nm, 'refs': len(refs), 'where': refs[:3], 'pairs': {}}
        for sc in schemes:
            if sc not in ovs:
                continue
            surface = over(color(ovs[sc]['app_surface']), over(color(ovs[sc]['app_background']), (255, 255, 255, 255)))
            worst = None
            for _prop, attr in attrs:
                if attr not in ovs[sc]:
                    continue
                c = over(color(ovs[sc][attr]), surface)
                k = round(contrast(c, surface), 2)
                worst = k if worst is None else min(worst, k)
            row['pairs'][sc] = worst
        if row['pairs']:
            rows.append(row)
    return rows


def vector_literals_audit():
    """drawable 里的裸 hex（不经 attr 的硬编码色）。"""
    hits = []
    for f in glob.glob(os.path.join(RES, 'drawable*', '*.xml')):
        b = os.path.basename(f)
        for i, ln in enumerate(open(f, encoding='utf-8').read().split('\n'), 1):
            for m in re.finditer(r'"(#[0-9a-fA-F]{6,8})"', ln):
                hits.append({'file': b, 'line': i, 'value': m.group(1).lower()})
    agg = {}
    for h in hits:
        agg[h['value']] = agg.get(h['value'], 0) + 1
    return hits, agg



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

    n_app, app_off = app_dimens_audit()
    report['app_dimens'] = {'total': n_app, 'off_ladder': app_off}
    role_of, radius_unknown = radius_roles_audit()
    report['radius_roles'] = {r: v for r, v in role_of.items()}
    report['radius_unknown'] = radius_unknown
    report['fw_size'] = SPEC.fw_size_report()

    # 关键对比度（浅色 + 深色各一套）
    def probe(scheme):
        d = {k: color(v) for k, v in ovs[scheme].items()}
        page = d.get('app_background')
        card = d.get('app_surface') or page
        res = {}
        # 探针必须取**真正当 textColor 用**的 attr。实测布局里
        #   app_text_primary 157 处 / app_text_secondary 116 处 / app_text_hint 13 处，
        # 而 app_grey 108 处**全部**是 background（0.1dp 分隔线），0 处当文字色 ——
        # 早先版本拿 app_grey 当「次要文字」，得出的 1.61:1 是误报。
        # 主色按钮标签同理：原来取 app_bar_text（#a5b4fc）算出 1.50:1 也是误报，
        # 真实标签经 opt23 后是 ?attr/app_surface（浅 #ffffff / 深 #1a1d21）。
        for label, fg, bg in (
            ('正文/卡片', d.get('app_text_primary'), card),
            ('正文/页面', d.get('app_text_primary'), page),
            ('次要文字/卡片', d.get('app_text_secondary'), card),
            ('提示文字/卡片', d.get('app_text_hint'), card),
            ('危险文字/卡片', d.get('app_lit_text_dc2626'), card),
            ('主色按钮标签/主色', d.get('app_surface'), d.get('app_colorPrimary')),
        ):
            if fg and bg:
                res[label] = round(contrast(over(fg, bg), bg), 2)
        return res

    report['contrast'] = {s: probe(s) for s in ('indigo', 'indigo.Night') if s in ovs}

    # 硬编码白字回归计数：主色按钮标签应已全部走 ?attr/app_surface（opt23），
    # 残留的只允许出现在非主色填充上。
    hw = []
    for f in sorted(glob.glob(os.path.join(RES, 'layout', '*.xml'))):
        for i, ln in enumerate(open(f, encoding='utf-8').read().split('\n'), 1):
            if '@android:color/white' in ln:
                hw.append('%s:%d' % (os.path.basename(f), i))
    report['hardcoded_white'] = hw

    # 深色分层：卡片不能融进页底（阈值 ELEV_MIN）
    report['elevation'] = elevation_audit(ovs)
    report['icon_contrast'] = icon_contrast_audit(ovs)
    lit_hits, lit_agg = vector_literals_audit()
    report['vector_literals'] = {'total': len(lit_hits), 'kinds': lit_agg,
                                 'top': ['%s:%d %s' % (h['file'], h['line'], h['value']) for h in lit_hits[:12]]}

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
    print('\n=== 应用语义 dimens 档位（dimens.xml）===  %d 个 token，%d 个离群'
          % (report['app_dimens']['total'], len(report['app_dimens']['off_ladder'])))
    for r in report['app_dimens']['off_ladder']:
        print('  %-40s %6g%s -> 最近档 %g  [%s]'
              % (r['name'], r['value'], r['unit'], r['nearest'], r['kind']))

    print('\n=== 圆角语义角色 ===')
    for role in ('pill', 'xl', 'lg', 'md', 'sm', 'xs', 'none'):
        toks = report['radius_roles'].get(role)
        if toks:
            vals = sorted({v for _, v in toks})
            print('  %-5s %5gdp  %2d 个 token  值%s'
                  % (role, SPEC.RADIUS_ROLES[role], len(toks),
                     '一致' if len(vals) == 1 else '漂移 %s' % vals))
    if report['radius_unknown']:
        print('  不在档位上的圆角 token：%s' % report['radius_unknown'])

    print('\n=== fw_size_* 分组（名字含值，只审计不改值）===')
    for g, d in sorted(report['fw_size'].items()):
        print('  %-8s %2d 档，非 4dp 网格 %d 个' % (g, d['tokens'], len(d['off_4dp'])))
        print('           %s' % d['values'])

    print('\n=== 对比度 ===')
    for s, r in report['contrast'].items():
        print('  [%s]' % s)
        for k, v in r.items():
            flag = 'OK ' if v >= 4.5 else 'LOW'
            print('    %s %-14s %5.2f:1' % (flag, k, v))
    print('\n=== 硬编码白字残留 ===')
    print('  %d 处（主色按钮标签应已全部改走 ?attr/app_surface）' % len(report['hardcoded_white']))
    for x in report['hardcoded_white']:
        print('    %s' % x)

    print('\n=== 深色分层（页底 → 卡片 → 描边，阈值 %.2f）===' % ELEV_MIN)
    bad_elev = 0
    for k, v in report['elevation'].items():
        flag = ('OK ' if v['ok'] else 'LOW') if v['dark'] else '-- '
        bad_elev += 0 if v['ok'] else 1
        print('  %s %-13s 卡/页 %4.2f  线/卡 %4.2f   (%s / %s / %s)%s'
              % (flag, k, v['card_vs_bg'], v['line_vs_card'], v['bg'], v['card'], v['line'],
                 '' if v['dark'] else '   浅色靠阴影+描边'))
    print('  低于阈值的深色方案：%d 套' % bad_elev)

    print('\n=== 图标对比（布局里的矢量图标 vs 卡片底，阈值 3.0）===')
    for row in report['icon_contrast']:
        pairs = '  '.join('%s %.2f' % (s, v) for s, v in row['pairs'].items())
        flag = 'LOW' if any(v < 3.0 for v in row['pairs'].values() if v) else 'OK '
        print('  %s %-24s %2d 处  %s' % (flag, row['icon'], row['refs'], pairs))

    print('\n=== drawable 裸 hex（未经 attr 的硬编码色）===')
    print('  %d 处，%d 种色值' % (report['vector_literals']['total'], len(report['vector_literals']['kinds'])))
    for k, n in sorted(report['vector_literals']['kinds'].items(), key=lambda x: -x[1]):
        print('    %-10s %2d' % (k, n))


if __name__ == '__main__':
    main()

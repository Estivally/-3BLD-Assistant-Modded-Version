# -*- coding: utf-8 -*-
"""ui_dimens_spec —— 尺寸语义档位规格（唯一事实来源）

为什么需要这个文件
==================
opt21 / opt22 需要「吸附到哪几档」，ui_audit 需要「判定是否合规」。
两边如果各自写一份梯子，迟早漂移 —— 所以梯子和角色表都放这里，双方 import。

三条梯子
========
RADIUS_LADDER  7 档圆角：0 / 8 / 12 / 16 / 20 / 24 / 999(pill)
SPACE_LADDER   2dp 网格到 40，之后 4dp 到 160，再往上 8dp（到 400）
TEXT_LADDER    12 档字号：8 10 12 14 16 18 20 24 28 34 40 56

圆角的语义命名（P2-6）
======================
现有 token 名已经够用（radius_sm/md/lg/xl + fw_radius_*），**不重命名**：
public.xml 把资源 ID 钉死，smali 里 ThemeSettingActivity 还会按字符串
"radius_sm" / "radius_md" 运行时查 dimen。重命名 = 编译过、运行崩。
所以语义化落在「档位表 + 审计」上：同一语义角色只允许一个值。

fw_size_* 为什么不吸附（P1-4 的结论）
====================================
75 个 fw_size_* 的**名字里写着值**（fw_size_15 = 15dp）。改值 = 制造一处
「名字说谎」，正是本轮要消灭的缺陷；改名又撞 public.xml + smali 字符串查表。
所以 fw_size_* 只做**分组审计**：按它被用在哪个 XML 属性上分成三组，
报告每组档位分布与离群值，把「该不该收敛」变成可见的决策，而不是偷偷改值。
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, 'src', 'res')

RADIUS_LADDER = [0, 8, 12, 16, 20, 24, 999]
SPACE_LADDER = sorted(set(list(range(0, 41, 2)) + list(range(40, 161, 4)) + list(range(160, 401, 8))))
TEXT_LADDER = [8, 10, 12, 14, 16, 18, 20, 24, 28, 34, 40, 56]

# 语义圆角角色 → 允许值（同角色只允许一个值，不同角色值必须不同）
RADIUS_ROLES = {
    'pill': 999,   # 胶囊 / 圆环 / 头像
    'xl': 24,      # 大卡片 / 弹层
    'lg': 16,      # 卡片
    'md': 12,      # 输入框 / 次级卡片
    'sm': 8,       # 按钮 / chip
    'xs': 4,       # 徽章 / 小标签
    'none': 0,     # 分隔线 / 直角
}

# 位移上限：超过就不吸附（宁可不收敛，也不改变真实布局）
MAX_DELTA = {'radius': 4.0, 'space': 2.0, 'text': 1.0}

# fw_size_* 的三组用途（按引用它的 XML 属性归类）
SIZE_GROUPS = {
    'control': ('layout_height', 'minHeight', 'height'),   # 按钮 / 行高 / 输入框
    'box': ('layout_width', 'width', 'maxWidth', 'minWidth'),  # 图标 / 方格 / 侧栏
    'space': ('padding', 'paddingStart', 'paddingEnd', 'paddingTop', 'paddingBottom',
              'paddingHorizontal', 'paddingVertical', 'margin', 'marginStart',
              'marginEnd', 'marginTop', 'marginBottom', 'marginHorizontal',
              'marginVertical', 'layout_marginTop', 'layout_marginBottom',
              'layout_marginStart', 'layout_marginEnd', 'layout_margin'),
}

FW_SIZE_RE = re.compile(r'fw_size_(\d+)')
REF_RE = re.compile(r'(@dimen/(fw_size_\d+))')


def snap(v, ladder):
    return min(ladder, key=lambda x: (abs(x - v), x))


def ladder_for(kind):
    return {'radius': RADIUS_LADDER, 'space': SPACE_LADDER, 'text': TEXT_LADDER}[kind]


def classify_token(name, unit):
    """dimens.xml 里的语义 token 归到哪条梯子。"""
    if unit == 'sp':
        return 'text'
    if 'radius' in name or name.endswith('_corner'):
        return 'radius'
    return 'space'


def radius_role(value):
    """把一个圆角值映射到语义角色；不在档位上返回 None。"""
    for role, v in RADIUS_ROLES.items():
        if v == value:
            return role
    return None


def fw_size_usage():
    """扫 src/res 的布局/drawable/style，统计每个 fw_size_N 被用在哪些属性上。

    返回 {name: {'attrs': set(attr), 'files': set(relpath), 'count': int}}
    """
    usage = {}
    for root, dirs, files in os.walk(RES):
        for fn in files:
            if not fn.endswith('.xml') or fn in ('public.xml', 'framework_dimens.xml'):
                continue
            path = os.path.join(root, fn)
            try:
                body = open(path, encoding='utf-8', errors='ignore').read()
            except OSError:
                continue
            for m in re.finditer(r'([A-Za-z_:]*?)(layout_width|layout_height|padding[A-Za-z]*|'
                                 r'margin[A-Za-z]*|layout_margin[A-Za-z]*|width|height|'
                                 r'minHeight|maxWidth|minWidth)="(@dimen/(fw_size_\d+))"', body):
                attr, name = m.group(2), m.group(4)
                rec = usage.setdefault(name, {'attrs': set(), 'files': set(), 'count': 0})
                rec['attrs'].add(attr)
                rec['files'].add(os.path.relpath(path, RES))
                rec['count'] += 1
    return usage


def fw_size_group(name, usage):
    """按引用属性把 fw_size_* 分组。

    square  —— 同一 token 同时当 width 和 height 用（图标 / 方格），本来就该是正方形
    control —— 只当高度用（按钮 / 行高）
    box     —— 只当宽度用（侧栏 / 标签宽度）
    space   —— 只当 padding / margin 用
    unknown —— 没被任何 XML 直接引用（可能只在 style 或 smali 里用）
    """
    rec = usage.get(name)
    if not rec:
        return 'unknown'
    a = rec['attrs']
    has_w = any(x in a for x in ('layout_width', 'width', 'maxWidth', 'minWidth'))
    has_h = any(x in a for x in ('layout_height', 'height', 'minHeight'))
    has_sp = any(x.startswith(('padding', 'margin', 'layout_margin')) for x in a)
    if has_sp and not (has_w or has_h):
        return 'space'
    if has_w and has_h:
        return 'square'
    if has_h:
        return 'control'
    if has_w:
        return 'box'
    return 'unknown'


def fw_size_report(usage=None):
    """fw_size_* 的分组统计：每组多少档、值域、以及不在 4dp 网格上的离群值。"""
    usage = fw_size_usage() if usage is None else usage
    groups = {}
    for name in usage:
        v = int(FW_SIZE_RE.search(name).group(1))
        groups.setdefault(fw_size_group(name, usage), []).append(v)
    out = {}
    for g, vals in groups.items():
        vals = sorted(set(vals))
        out[g] = {
            'tokens': len(vals),
            'values': vals,
            'off_4dp': [v for v in vals if v % 4 != 0],
        }
    return out

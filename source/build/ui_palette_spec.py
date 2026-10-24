# -*- coding: utf-8 -*-
"""ui_palette_spec —— 语义调色板规格（可审阅的分组表，不含 IO）

设计原则
========
现状：theme_schemes.xml 有 8 套 overlay × 172 个 item。其中 25 个是**语义锚点**
（app_background / app_surface / app_line / app_black / app_colorPrimary ...），
另外 147 个是 app_lit_{bg,text,tint}_<hex> 的**历史自由值** —— 名字里直接写死
indigo 方案的 hex，但值在 4 套配色里各自漂移（例：app_lit_bg_006366f1 在 teal 是
#0067ede2）。这是「6 种白面 / 4 种主色 / 10 种危险红」的真正根因。

本规格把 147 个自由值归入 25 个语义角色，规则两条：
  1) **名称家族覆盖表**（OVERRIDE）：按 indigo 基准 hex 判定语义归属。
     优先级最高，跨 8 套一致，不受配色差异干扰。
  2) **ΔE 就近吸附**：未命中覆盖表的，吸附到同一 overlay 内最近的锚点角色，
     且仅当 ΔE ≤ THRESH（9.0）才改写 —— 只合并「近重复色」，保留刻意差异。

角色值本身**不写死**，而是从每套 overlay 自己的 25 个语义锚点推导（见 derive()）。
好处：teal 仍是 teal、graphite 仍是 graphite，配色身份不被抹平；只有漂移被收敛。
"""

# ---- 吸附阈值（ΔE2000 近似，CIELAB 欧氏距离）----
THRESH = 9.0

# ---- 有彩/无彩：超过这个 chroma 视为「刻意的高饱和装饰色」----
CHROMA_DECORATIVE = 40

# ---- 参与 ΔE 吸附的候选角色 ----
BG_ROLES = ['page', 'card', 'sunken', 'border', 'primary', 'primary_dark', 'primary_light',
            'primary_container', 'success', 'success_container', 'danger', 'danger_container',
            'warning', 'warning_container', 'info', 'info_container', 'accent']
FG_ROLES = ['text_primary', 'text_body', 'text_secondary', 'text_tertiary', 'text_disabled',
            'primary', 'primary_light', 'success', 'danger', 'warning', 'info', 'on_primary']

# ---- 名称家族覆盖表：indigo 基准 hex -> 角色 ----
# 'keep' = 刻意的遮罩/玻璃层/高饱和装饰，不参与收敛
OVERRIDE = {
    # 主色家族（靛蓝 / 紫）
    '4f46e5': 'primary_dark', '6366f1': 'primary', '6660f0': 'primary', '818cf8': 'primary_light',
    '7c3aed': 'primary_dark', '8b5cf6': 'primary', 'a5b4fc': 'primary_light',
    'c7d2fe': 'primary_container', 'b7c7ff': 'primary_container', 'b8c7f8': 'primary_container',
    'ccd9f8': 'primary_container', 'd8e1ff': 'primary_container', 'eef1ff': 'primary_container',
    'eef2ff': 'primary_container', 'eef4ff': 'primary_container', 'f5f3ff': 'primary_container',
    'e6eaf2': 'primary_container', 'd9e2f2': 'primary_container',
    'd9e1ed': 'primary_container', 'dbe3f0': 'primary_container', 'd8e1f0': 'primary_container',
    # 信息蓝
    '2563eb': 'info', '1d4ed8': 'info', '3b82f6': 'info', '007aff': 'info', '59c1ff': 'info',
    '93c5fd': 'info_container', 'bfdbfe': 'info_container', 'd6e6ff': 'info_container',
    'dce7ff': 'info_container', 'dceafe': 'info_container', 'eaf2ff': 'info_container',
    # 成功绿
    '059669': 'success', '047857': 'success', '2f8f7a': 'success', '4ade80': 'success',
    '86efac': 'success_container', 'cfefdc': 'success_container', 'dcfce7': 'success_container',
    'f0fdf4': 'success_container', 'f6fcf8': 'success_container',
    # 危险红
    'dc2626': 'danger', 'b91c1c': 'danger', '991b1b': 'danger',
    'fff5f5': 'danger_container', 'fff6f6': 'danger_container', 'f6d1d1': 'danger_container',
    # 警告琥珀
    'b45309': 'warning', 'd97706': 'warning',
    'f6c7a6': 'warning_container', 'f8d8b3': 'warning_container',
    'fff7ee': 'warning_container', 'fff9f2': 'warning_container', 'fffdfbf6': 'warning_container',
    # 中性面
    'ffffff': 'card', 'ffffffff': 'card', 'fdfdff': 'card', 'fcfdfe': 'card', 'fcfcfe': 'card',
    'fffdfeff': 'card', 'f8faff': 'card',
    'fafafa': 'page', 'f8f9fb': 'page', 'f8fafc': 'page', 'f8fafd': 'page', 'fff9fbff': 'page',
    'f3f4f6': 'sunken', 'f3f5fb': 'sunken', 'f3f6fb': 'sunken', 'f3f7ff': 'sunken',
    'f4f7fb': 'sunken', 'f5f6ff': 'sunken', 'f6f8fb': 'sunken', 'f1f5f9': 'sunken',
    'f0f0f0': 'sunken', 'f5f5f5': 'sunken',
    'fff6f7ff': 'sunken', 'fff7f8ff': 'sunken', 'fff7faff': 'sunken', 'fff8faff': 'sunken',
    'e5e7eb': 'border', 'e0e0e0': 'border', 'd1d5db': 'border', 'd9dee4': 'border',
    # 文本：四级（primary / body / secondary / tertiary）
    '111827': 'text_primary', '1f2937': 'text_primary', '2d3436': 'text_primary',
    '2e3440': 'text_primary', '233247': 'text_primary', '243244': 'text_primary',
    '333333': 'text_primary', '000000': 'text_primary',
    '334155': 'text_body', '374151': 'text_body', '3f4b5b': 'text_body', '42546c': 'text_body',
    '475569': 'text_body', '4b5563': 'text_body', '666666': 'text_body',
    '6b7280': 'text_secondary', '6b7785': 'text_secondary', '64748b': 'text_secondary',
    '94a3b8': 'text_tertiary', 'a3afbf': 'text_tertiary', 'b0bec5': 'text_tertiary',
    '7f8b9c': 'text_tertiary', '8b95a7': 'text_tertiary', 'a7b0bf': 'text_tertiary',
    'bfc9d6': 'text_tertiary', '9ca3af': 'text_tertiary', '728ea3': 'text_tertiary',
    'bdc7d8': 'text_tertiary',
    # 高饱和装饰黄（高亮笔 / 角标点）：保持辨识度，不参与收敛
    'ecd50a': 'keep', 'f7c948': 'keep',
}
# 注意：attr 名里的 8 位 hex 前两位是 **alpha**，base_hex_of_name() 会剥掉它。
# 所以覆盖表的键只能是 6 位 RGB；遮罩/玻璃层的 alpha 在改写时自动保留，
# 它们会收敛到 text_primary / text_body / border 的同 alpha 版本 —— 视觉等价。


# ---- 机械别名 -> 语义角色（名字保留，取值跟随语义，消除重复定义）----
SEMANTIC_ALIAS = {
    'app_bag': 'page',
    'app_bg_color': 'page',
    'app_card_background': 'card',
    'app_light_blue': 'info_container',
    'app_text_hint': 'text_tertiary',
}

# 覆盖表键统一成 6 位基准 hex：8 位键的前两位是 alpha，去掉
OVERRIDE = {(k[2:] if len(k) == 8 else k): v for k, v in OVERRIDE.items()}


def mix(fg, bg, t):
    """fg 以 t 比例叠在 bg 上（含 fg 自身 alpha 折算）。"""
    a = fg[0] / 255.0
    oa = t * a
    return (int(round(oa * 255)),) + tuple(
        int(round(fg[i] * oa + bg[i] * (1 - oa))) for i in (1, 2, 3))


def derive(d):
    """从一套 overlay 的 25 个语义锚点推导全部角色值。"""
    A = {}
    A['page'] = d['app_background']
    A['card'] = d['app_surface']
    A['border'] = d['app_line']
    A['text_primary'] = d['app_black']
    A['primary'] = d['app_colorPrimary']
    A['primary_dark'] = d['app_colorPrimaryDark']
    A['primary_light'] = d['app_colorPrimaryLight']
    A['accent'] = d['app_colorAccent']
    A['success'] = d['app_green_text']
    A['danger'] = d['app_lit_text_dc2626']
    A['warning'] = d['app_lit_text_b45309']
    A['info'] = d['app_lit_text_2563eb']
    A['text_disabled'] = d['app_text_disabled']
    card = A['card']
    tp = A['text_primary']
    # 文本四级由主文本按比例混向卡片色 —— 明暗两态自动成立
    A['text_body'] = mix(tp, card, 0.86)
    A['text_secondary'] = mix(tp, card, 0.60)
    A['text_tertiary'] = mix(tp, card, 0.38)
    A['sunken'] = mix(tp, card, 0.045)
    # 深色下主色是浅紫，白字对比不足 → 用页面底色作前景
    A['on_primary'] = (255, 255, 255, 255) if d['__dark__'] is False else (255,) + A['page'][1:]
    for r in ('primary', 'success', 'danger', 'warning', 'info'):
        A[r + '_container'] = mix(A[r], card, 0.12)   # 浅色底：填充块
        A[r + '_soft'] = mix(A[r], card, 0.35)        # 描边：边框线
    return A


# 有 _soft（描边）变体的角色
SOFTABLE = ('primary', 'success', 'danger', 'warning', 'info')
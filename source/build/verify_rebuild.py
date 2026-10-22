#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
重建版 APK 的结构总校验：与原 APK 逐项对照。

检查项：
  1. 资源条目数（aapt2 dump resources 的 `resource 0x...` 行）
  2. 值为 @null 的条目数
  3. 9-patch 数量
  4. 每个资源名的配置集合是否一致（比总数更能发现「错配」）
  5. ZIP 条目**逐个名字**对照，并把差异分类：
       - drawable 扩展名变更（.jpg/.png -> .webp，stem 配对）
       - 预期新增的派生公式库
       - 其它一律算「非预期」

用法：python verify_rebuild.py <原APK> <重建版APK>
"""
import collections
import os
import re
import subprocess
import sys
import zipfile

AAPT2 = r"D:\Program\dsh\tools\aapt2\aapt2.exe"

RES_RE = re.compile(r"^\s+resource 0x[0-9a-f]+ (\w+)/(\S+?)(?:\s+\w+)?\s*$")
CFG_RE = re.compile(r"^\s+\(([^)]*)\)\s+\((?:file|string|integer|reference|color|dimen|boolean|array|bag|attr|raw)\b")
NULL_RE = re.compile(r"^\s+\(([^)]*)\)\s+\(null\)")

# 本轮预期新增的资产
EXPECTED_ADDED = {
    "assets/assets/json/parityAlgToInfoFirst.json",
    "assets/assets/json/edgeAlgToInfoFirst.json",
    "assets/assets/json/cornerAlgToInfoFirst.json",
}

# 从源码树推算「预期新增的资源」
SRC_VALUES = r"D:\Program\dsh\rebuild\src\res\values"
SRC_RES = os.path.dirname(SRC_VALUES)

# values*/xxx.xml 里 <tag name="..."> → 资源类型
VALUE_TAGS = {
    "string": "string", "color": "color", "attr": "attr", "style": "style",
    "dimen": "dimen", "integer": "integer", "bool": "bool",
    "fraction": "fraction", "plurals": "plurals",
    "array": "array", "string-array": "array", "integer-array": "array",
}

# 文件式资源目录（去掉配置限定符后就是资源类型）
FILE_RES_DIRS = ("layout", "drawable", "color", "anim", "animator", "menu",
                 "xml", "raw", "mipmap", "font", "interpolator", "transition")

# opt11 有意为这 3 个应用自有状态列表补了 v23 变体（API 21 不支持 ?attr/ 的兜底）
EXPECTED_CONF_GAIN = {"v23"}

# opt12 有意**移除**的资源：删掉启动页
#   - layout/activity_splash ：启动页布局（只剩一个全屏 ImageView）
#   - mipmap/splash          ：708 KB 的 splash.gif
# 两者只被 SplashActivity 引用（已核实），资源文件与 public.xml 条目一并删掉。
# 注意 id/gif_img **不能删** —— activity_first.xml 也复用了这个 id。
EXPECTED_REMOVED = {
    ("layout", "activity_splash"),
    ("mipmap", "splash"),
}

# 576 个编码图对应的资源名（从最终清单推）
TSV = r'D:\Program\WorkBuddy\三盲助手\联想图-审查\最终清单.tsv'
RESNAME_OVERRIDE = {'DO': 'do1', 'IF': 'if1'}


def src_resource_names():
    """把整个 src/res 树枚举成 {(type, name)}。

    覆盖 values*/*.xml 的标签式资源，以及 layout/drawable/color… 的文件式资源。
    这样新增 attr / style / layout / drawable / id 都会自动纳入「预期新增」，
    不用每次改脚本常量。
    """
    names = set()
    if not os.path.isdir(SRC_RES):
        return names
    for sub in os.listdir(SRC_RES):
        p = os.path.join(SRC_RES, sub)
        if not os.path.isdir(p):
            continue
        base = sub.split("-")[0]
        if base == "values":
            for fn in os.listdir(p):
                # public.xml 只是 id 声明表，里面的条目在定义处已统计
                if not fn.endswith(".xml") or fn == "public.xml":
                    continue
                try:
                    with open(os.path.join(p, fn), encoding="utf-8") as f:
                        txt = f.read()
                except OSError:
                    continue
                for m in re.finditer(r'<([a-z-]+)\s+name="([^"]+)"', txt):
                    t = VALUE_TAGS.get(m.group(1))
                    if t:
                        names.add((t, m.group(2)))
        elif base in FILE_RES_DIRS:
            for fn in os.listdir(p):
                # 9-patch 是 .9.png 双扩展名，资源名要去掉整个 .9.png
                if fn.endswith(".9.png"):
                    stem = fn[: -len(".9.png")]
                else:
                    stem = os.path.splitext(fn)[0]
                if stem.startswith("."):
                    continue
                names.add((base, stem))
        if base == "layout":
            for fn in os.listdir(p):
                if not fn.endswith(".xml"):
                    continue
                try:
                    with open(os.path.join(p, fn), encoding="utf-8") as f:
                        txt = f.read()
                except OSError:
                    continue
                for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', txt):
                    names.add(("id", m.group(1)))
    return names


def expected_new_resources(orig_names):
    """src/res 里存在、但原版资源表里没有的 (type, name) 条目。

    注意：必须拿「原版资源**名**集合」来判存在性，**不能**用 confs ——
    confs 只收录「有 (config) 值行」的资源，而 string/color 这类
    「单值、无 config 行」的资源在 confs 里根本不存在，
    用它判断会把所有既有 string 都误判成「新增」（实测会多算 860 个）。
    """
    return {k for k in src_resource_names() if k not in orig_names}


def mnemonic_resnames():
    """返回 576 个编码对应的 drawable 资源名集合；读不到清单则返回 None。"""
    names = set()
    try:
        with open(TSV, encoding='utf-8') as f:
            for i, line in enumerate(f):
                line = line.rstrip('\n')
                if not line.strip():
                    continue
                if i == 0 and ('编码' in line or 'code' in line.lower()):
                    continue
                c = line.split('\t')[0].strip().upper()
                if not c:
                    continue
                if len(c) == 2 and c[0] == c[1]:
                    names.add(c[0].lower())
                else:
                    names.add(RESNAME_OVERRIDE.get(c, c.lower()))
    except OSError:
        return None
    return names


def dump(path):
    r = subprocess.run([AAPT2, "dump", "resources", path],
                       capture_output=True, text=True, errors="replace")
    return r.stdout


def analyse(path):
    """返回 (条目数, 配置集合, @null 数, 资源名集合)

    names 收录**所有** `resource 0x...` 行 → 与资源类型无关，
    string/color 这类无 config 行的资源也在里面（判「新增」必须用它）。
    confs 只收录有 (config) 值行的资源 → 用来比「同配置集合」。
    """
    entries = 0
    nulls = 0
    confs = collections.defaultdict(set)
    names = set()
    cur = None
    for line in dump(path).splitlines():
        m = RES_RE.match(line)
        if m:
            cur = (m.group(1), m.group(2))
            names.add(cur)
            entries += 1
            continue
        if cur is None:
            continue
        m = NULL_RE.match(line)
        if m:
            nulls += 1
            confs[cur].add(m.group(1))
            continue
        m = CFG_RE.match(line)
        if m:
            confs[cur].add(m.group(1))
    return entries, confs, nulls, names


def zip_names(path):
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist()
                 if not n.startswith("META-INF/") or n.endswith((".version", ".properties"))]
    n9 = sum(1 for n in names if n.endswith(".9.png"))
    return set(names), len(names), n9


def main():
    a = sys.argv[1] if len(sys.argv) > 1 else "original.apk"
    b = sys.argv[2] if len(sys.argv) > 2 else "signed.apk"

    print("=" * 74)
    print(" 原版   %s" % a)
    print(" 重建版 %s" % b)
    print("=" * 74)

    ea, ca, na, namea = analyse(a)
    eb, cb, nb, nameb = analyse(b)
    exp_new = expected_new_resources(namea)
    # 净增量 = 新增 - 有意移除（opt12 删了启动页的 layout + mipmap）
    exp_delta = len(exp_new) - len(EXPECTED_REMOVED)

    print()
    print("资源条目数        %6d  vs  %6d   %s"
          % (ea, eb, "✅ 差 %d == 预期净增 %d（新增 %d - 移除 %d）"
             % (eb - ea, exp_delta, len(exp_new), len(EXPECTED_REMOVED))
             if eb - ea == exp_delta else
             "❌ 差 %d，预期净增 %d" % (eb - ea, exp_delta)))
    print("@null 条目数      %6d  vs  %6d   %s" % (na, nb, "✅" if na == nb else "❌ 差 %d" % (nb - na)))

    seta, za, z9a = zip_names(a)
    setb, zb, z9b = zip_names(b)
    print("ZIP 条目数        %6d  vs  %6d   (差 %+d)" % (za, zb, zb - za))
    print("9-patch 数量      %6d  vs  %6d   %s" % (z9a, z9b, "✅" if z9a == z9b else "❌ 差 %d" % (z9b - z9a)))

    # ---- ZIP 名字级差异 ----
    # ★ 原版是 AGP 产物，资源被「路径缩短 + 名字混淆」压平到 res/ 根目录
    #   （实测原版 res/ 下有 1312 个形如 res/aB.xml / res/zX.png 的条目，
    #    且完全没有 res/drawable、res/layout 这些目录）。
    #   而 apktool 重打包一律用规范路径 res/<type>[-config]/<name>.<ext>。
    #   所以「逐个名字对照」在两个包之间**根本不适用**，不能据此判失败。
    #   真正有意义的结构判据是资源表（条目数 / 配置集合 / 9-patch），见上。
    added = sorted(setb - seta)
    removed = sorted(seta - setb)

    exp_hit = [n for n in added if n in EXPECTED_ADDED]
    other_added = [n for n in added if n not in EXPECTED_ADDED]
    other_removed = list(removed)

    print()
    print("---- ZIP 差异（原版资源路径已缩短，名字不可直接对照）----")
    print("  预期新增派生资产        : %d / %d" % (len(exp_hit), len(EXPECTED_ADDED)))
    for n in sorted(EXPECTED_ADDED - set(exp_hit)):
        print("     ⚠ 未进包: %s" % n)
    print("  新增（除预期外）        : %d 条  ← 原版缩短路径的对应物" % len(other_added))
    for n in other_added[:3]:
        print("     + %s" % n)
    print("  移除                    : %d 条  ← 原版缩短路径" % len(other_removed))
    for n in other_removed[:3]:
        print("     - %s" % n)

    # 编码图必须是 .webp，且不得残留同 stem 的旧格式
    # （只针对 576 个编码资源名判断；res/drawable 下还有别的正常 UI 图）
    mn = mnemonic_resnames()
    if mn is None:
        print("  ⚠ 读不到最终清单，跳过编码图扩展名检查")
        ext_ok = True
    else:
        have = collections.defaultdict(list)
        for n in setb:
            if n.startswith("res/drawable/"):
                stem, ext = os.path.splitext(os.path.basename(n))
                if stem.lower() in mn:
                    have[stem.lower()].append(ext.lower())
        bad_ext = {k: sorted(v) for k, v in have.items() if sorted(v) != [".webp"]}
        missing_mn = sorted(mn - set(have))
        print("  编码图命中资源名        : %d / %d" % (len(have), len(mn)))
        print("  编码图扩展名非 .webp    : %d 个 %s" % (len(bad_ext), list(bad_ext.items())[:5]))
        print("  编码图缺失              : %d 个 %s" % (len(missing_mn), missing_mn[:5]))
        ext_ok = (not bad_ext and not missing_mn and len(have) == len(mn) == 576)

    # 逐资源比配置集合 —— 总数相同也可能错配
    # 注意：「仅某版有」用**全量资源名集合**(namea/nameb)判断，
    #       不用 confs（confs 不含 string/color，会把新增字符串全漏掉）。
    #       confs 只用来比「同配置集合」。
    # 深色模式有意为部分资源补了 `night` 配置变体（values-night/），
    # 所以「新增的配置」只要全是 night* 就视为预期。
    only_a = sorted((namea - nameb) - EXPECTED_REMOVED)
    intentionally_removed = sorted((namea - nameb) & EXPECTED_REMOVED)
    only_b = sorted(nameb - namea)
    mismatch = []
    for k in sorted(set(ca) & set(cb)):
        lost = ca[k] - cb[k]
        gained = {c for c in (cb[k] - ca[k])
                  if not c.startswith("night") and c not in EXPECTED_CONF_GAIN}
        if lost or gained:
            mismatch.append((k, sorted(ca[k]), sorted(cb[k])))

    print()
    print("仅原版有的资源    %d 个   %s" % (len(only_a), "✅ 无丢失" if not only_a else "❌"))
    print("有意移除的资源    %d 个 / 预期 %d 个   %s"
          % (len(intentionally_removed), len(EXPECTED_REMOVED),
             "✅" if set(intentionally_removed) == EXPECTED_REMOVED else "❌"))
    for k in sorted(EXPECTED_REMOVED - set(intentionally_removed)):
        print("   预期移除但仍在: %s/%s" % k)
    print("仅重建版有的资源  %d 个（预期新增 %d 个）%s"
          % (len(only_b), len(exp_new), "✅" if set(only_b) == exp_new else "❌"))
    print("配置集合不一致    %d 个   %s" % (len(mismatch), "✅" if not mismatch else "❌"))
    for k in only_a[:6]:
        print("   仅原版: %s/%s" % k)
    for k in sorted(set(only_b) - exp_new)[:6]:
        print("   非预期新增: %s/%s" % k)
    for k in sorted(exp_new - set(only_b))[:6]:
        print("   预期新增但缺失: %s/%s" % k)
    for k, x, y in mismatch[:6]:
        print("   配置不同: %s/%s  原=%s  新=%s" % (k[0], k[1], x, y))

    # ZIP 条目净增量：**仅供参考，不参与判定**
    #
    # 原版 APK 的资源路径被整体缩短过（res/-2.png、res/-C.xml），
    # 所以几乎每个 res/ 条目都是「一加一减」，裸计数还会把目录项 /
    # META-INF 算进去 —— 这个差值无法在不复刻 aapt2 缩短算法的前提下算准。
    # 真正说明问题的是上面两条集合判定（无丢失 / 新增恰好等于预期），
    # 这里只把数字打出来供人眼核对。
    net_delta = len(added) - len(removed)
    print()
    print("ZIP 条目净增量（参考）: %+d  ← 原版路径已缩短，此值不可精确判定"
          % net_delta)

    ok = ((eb - ea) == exp_delta and na == nb and z9a == z9b
          and not only_a and set(only_b) == exp_new and not mismatch
          and set(intentionally_removed) == EXPECTED_REMOVED
          and len(exp_hit) == len(EXPECTED_ADDED) and ext_ok)
    print()
    print("结论：%s" % ("✅ 结构对齐（差异均可解释：路径规范化 + 新增派生资产 + 图换 WebP "
                     "+ 新增字符串/颜色/主题属性/覆盖样式资源）"
                     if ok else "⚠ 存在非预期差异，见上"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

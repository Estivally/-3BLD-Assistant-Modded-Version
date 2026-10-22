#!/usr/bin/env bash
# ============================================================================
# 三盲助手 6.2.2 —— 从原 APK 一键重建（反编译 → 修复 → 重打包 → 签名）
# ============================================================================
#
# 目标：产出可安装、可运行、资源语义与原版等价的 APK。
# 不做任何功能改造（用户明确「暂不改，先跑通」）。
#
# 工具来源（本机 GitHub 不可达，从 Maven 镜像取）：
#   apktool : https://maven.aliyun.com/repository/public/org/apktool/apktool-cli/3.0.3/
#             （22 MB fat jar，自带全部依赖，可直接 java -jar；
#               内部还带着 prebuilt/windows/aapt2.exe，可解出来单独用）
#
# ---------------------------------------------------------------------------
# 四个坑，以及现在的处理方式
# ---------------------------------------------------------------------------
#
#   坑 1：扩展名与真实内容不符
#       原 APK 里有 3 个 .png 其实是 JPEG、1 个 .png 其实是 WebP。
#       aapt2 对 .png 会校验 PNG 签名，报 "failed to read PNG signature" 直接失败。
#       另有 15 个 .webp 里是 JPEG —— aapt2 不校验 .webp，保持原样不动。
#       → fix_ext.py 只改名「.png 内容不是 PNG」的那几个。
#
#   坑 2：112 个 9-patch 被 apktool 丢弃
#       apktool 3.0.3 逐条报 "Could not decode file, replacing by NULL value"
#       且不写文件，产物里这些资源全变成 @null。
#       → restore_9patch.py 依据 aapt2 的资源转储，把原 APK 里的原始字节
#         还原到 drawable-<config>/<name>.9.png。
#
#   坑 3：还原出来的 9-patch 过不了 aapt2 校验  ★核心
#       这些 9-patch 是 **aapt v1** 编译的产物。aapt v1 会：
#           带边框源图 → 解析出拉伸区间/内边距 → **裁掉 1 像素边框**
#           → 写出净图 → 把九宫格信息塞进自定义 PNG 块 npTc
#       而 aapt2 是重写版，**只认边框像素、不读 npTc**。
#       于是这些文件在 aapt2 眼里是「尺寸莫名小、边框像素随便什么色」的坏图：
#           "top-left corner pixel must be either opaque white or transparent"
#           "image must be at least 3x3 (1x1 image with 1 pixel border)"
#           "found an invalid color"
#
#       曾经的做法是循环降级为普通 .png —— 但 112 个里降了 90 个，
#       AppCompat 的 popup 背景 / switch / spinner / 列表分隔线全失真，代价太大。
#
#       现在的做法：**从 npTc 解出九宫格信息，把边框重建回去**。
#       实测 112/112 全部通过 aapt2，且编译产物的拉伸区间与内边距
#       与原版**逐字节一致**（compare_9patch.py 验证）。
#       → rebuild_9patch.py
#
#       ★ 为什么这一步不能省：apktool 有时**能**自己把 9-patch 解出来，
#         但它只还原可拉伸区间，**会把内边距丢掉**。实测 112 个里有 18 个
#         内边距被清零（abc_popup_background_mtrl_mult 的 24px、
#         abc_menu_hardkey_panel_mtrl_mult 的 12px、
#         abc_btn_switch_to_on_mtrl_0000x 的 5px/11px）——
#         表现是 popup 里的文字贴边、switch 滑块位置偏移。
#         所以无论 apktool 解得出解不出，这一步都要跑。
#
#       ★ 内边距的字段顺序是 **(左, 右, 上, 下)**，不是直觉上的 (左, 上, 右, 下)。
#         这个坑踩过：按直觉解读导致 10 个文件内边距错位
#         （例如 spinner 的「右 48」被写成了「上 48」）。
#         顺序由 probe_padding_order.py 的受控实验钉死：造一个
#         「左=2 右=3 上=5 下=4」的源图交给 aapt2，回写的就是 [2,3,5,4]。
#
#   坑 4：@null 占位条目与真实文件冲突
#       apktool 为每个被丢弃的 9-patch 在 values-<config>/drawables.xml 里
#       写了 <drawable name="X">@null</drawable>。还原真实文件后二者冲突：
#           "resource 'drawable/xxx' has a conflicting value for configuration"
#       → dedup_drawables.py 精确删除「值为 @null 且同配置下已有真实文件」的条目。
#
# ---------------------------------------------------------------------------
# 用法：bash rebuild.sh
# 环境变量（可选）：
#   JAVA_HOME / JAVA    JDK 17 路径
#   ANDROID_SDK         Android SDK 路径（用于 zipalign / apksigner）
#   KEYSTORE / KS_PASS / KS_ALIAS   签名配置（默认用 debug keystore）
# ============================================================================
set -euo pipefail

# ---- 路径配置 ---------------------------------------------------------------
ROOT="D:/Program/dsh/rebuild"
APKTOOL="D:/Program/dsh/tools/apktool-cli.jar"
JAVA="${JAVA:-D:/Program/dsh/toolchain/jdk-17/jdk-17.0.20.1+1/bin/java.exe}"
PY="${PY:-D:/Program/WorkBuddy/.workbuddy/binaries/python/versions/3.13.12/python.exe}"
SDK="${ANDROID_SDK:-C:/Users/Estiavl/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"

ORIGINAL="$ROOT/original.apk"
SRC="$ROOT/src"
# 原始 9-patch 档案：保存 aapt v1 的原始字节（含 npTc）。
# rebuild_9patch.py 每次都从这里取源，因此可以反复跑而不失真（幂等）。
PRISTINE="$ROOT/src9_orig"

# JVM 的临时目录必须显式指定到可写位置，否则 apktool 报
# "java.io.IOException: 拒绝访问"（沙箱下默认 %TEMP% 不可写）
JVM_TMP="-Djava.io.tmpdir=$ROOT/tmp"

cd "$ROOT"
mkdir -p tmp

step() { echo; echo "=============================================================="; echo " $1"; echo "=============================================================="; }

step "第 1 步 / 8：反编译"
# 注意：这里**不做批量删除**。apktool 的 `-f` 是覆盖式写入，
# 同一份输入 + 同一个 apktool 版本，输出是确定的，不会留下脏文件；
# 少数需要清理的残留（改名过的图片）由 fix_ext.py 精确处理。
# 确实需要「从零开始」时用 CLEAN=1，它把旧树**移走**而不是删掉。
if [ "${CLEAN:-0}" = "1" ] && [ -d "$SRC" ]; then
    mv "$SRC" "$ROOT/src.bak.$(date +%Y%m%d_%H%M%S)"
    echo "  旧反编译树已移走（CLEAN=1）"
fi
"$JAVA" $JVM_TMP -jar "$APKTOOL" d -f -o "$SRC" "$ORIGINAL" > decode.log 2>&1
echo "  完成（日志：decode.log）"
echo "  被 apktool 丢弃的文件: $(grep -c 'Could not decode file' decode.log || true) 个"

step "第 2 步 / 8：修正扩展名与真实格式不符的图片"
"$PY" fix_ext.py "$SRC/res" --apply

step "第 3 步 / 8：还原被丢弃的 9-patch（原始字节 + 存档）"
"$SDK/build-tools/36.0.0/aapt2.exe" dump resources "$ORIGINAL" > res_dump.txt 2>/dev/null
"$PY" restore_9patch.py "$ORIGINAL" res_dump.txt decode.log "$SRC/res" "$PRISTINE"

step "第 4 步 / 8：重建 9-patch 边框（从 npTc 恢复可拉伸区域与内边距）"
"$PY" rebuild_9patch.py "$SRC/res" "$PRISTINE"

step "第 5 步 / 8：清理冲突条目与重复副本"
"$PY" dedup_drawables.py "$SRC/res"
"$PY" dedup_9patch.py "$ORIGINAL" "$SRC/res" --apply

step "第 6 步 / 8：重打包"
rm -f "$ROOT/rebuilt.apk"
"$JAVA" $JVM_TMP -jar "$APKTOOL" b "$SRC" -o "$ROOT/rebuilt.apk" > build.log 2>&1
grep -E "^(W:|E:)" build.log && echo "  ⚠ 构建日志里有警告/错误，见 build.log" || echo "  ✅ 0 警告 0 错误"
ls -lh "$ROOT/rebuilt.apk" | awk '{print "  产物大小: " $5}'

step "第 7 步 / 8：校验 9-patch 语义与原版一致"
"$PY" compare_9patch.py

step "第 8 步 / 8：对齐 + 签名"
"$BT/zipalign.exe" -p -f 4 "$ROOT/rebuilt.apk" "$ROOT/aligned.apk"
"$BT/apksigner.bat" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$ROOT/signed.apk" "$ROOT/aligned.apk"
echo "  签名校验："
"$BT/apksigner.bat" verify "$ROOT/signed.apk" && echo "  ✅ 签名有效"

echo
echo "=============================================================="
echo " 完成：$ROOT/signed.apk"
echo "=============================================================="
ls -lh "$ROOT/signed.apk" | awk '{print "  大小: " $5}'

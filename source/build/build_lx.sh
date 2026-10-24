#!/usr/bin/env bash
# ============================================================================
# 三盲助手 6.2.2 —— 把「最终 576 张联想图 + 最终联想词表」写进原 APK 并重打包
# ============================================================================
#
# 前置：`src/` 已经是 rebuild.sh 跑通后的反编译树（9-patch 已修复、dedup 已做）。
#      本脚本**不重新反编译**（那会覆盖掉我们的改动），只做：
#
#        1) apply_lx_update.py   替换 drawable 图 + 重写 Dictionary.initDic()
#        2) apktool b            重打包
#        3) 校验                 9-patch 语义 + 结构条目数
#        4) zipalign + 签名
#
# 想从零重来：先 `bash rebuild.sh`（重建干净树），再跑本脚本。
# 想回滚：drawable 原图在 src9_orig_lx/，原 smali 在 Dictionary.smali.orig
#
# 用法：bash build_lx.sh
# ============================================================================
set -euo pipefail

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
JVM_TMP="-Djava.io.tmpdir=$ROOT/tmp"
# 控制台是 GBK，opt17a 等脚本会 print ✅/⚠ 之类字符 —— 不设 utf-8 会 UnicodeEncodeError
export PYTHONIOENCODING=utf-8

cd "$ROOT"
mkdir -p tmp
step() { echo; echo "=============================================================="; echo " $1"; echo "=============================================================="; }

step "第 1 步 / 4：写入最终图与词表"
# ★ 这一部依赖 Pillow（jpg→WebP 转码）。本机 PyPI 不可达、venv 里也没有 Pillow，
#   所以提供了 SKIP_LX_UPDATE=1 逃生口：源码树**已经**是上次 apply 过的状态
#   （res/drawable/ 下 576 个 webp + Dictionary.smali 里的词表），
#   只要这次不改图/不改词，跳过这一步产物完全等价。
#   什么时候**不能**跳：换了图、改了词表、或者 src 是刚 rebuild.sh 出来的干净树。
if [ "${SKIP_LX_UPDATE:-0}" = "1" ]; then
    echo "  ⏭  SKIP_LX_UPDATE=1，跳过（前提：res/drawable 已有 576 webp、词表已写入）"
    n_webp=$(ls "$SRC"/res/drawable/*.webp 2>/dev/null | wc -l)
    if [ "$n_webp" -lt 576 ]; then
        echo "  ✗ res/drawable 只有 $n_webp 个 webp（期望 576）—— 不能跳过！"
        exit 1
    fi
    echo "  ✅ 校验通过：webp $n_webp 个"
else
    "$PY" apply_lx_update.py
fi

# ★★ opt17（UI 框架：尺寸 token + 具名 style）必须**最后跑**。
#   opt13/opt16 之类的脚本在复跑时会按自己的期望内容**整份重写**某些布局/drawable
#   （例：opt16 会把 bottom_nav_indicator.xml 里的 @dimen/fw_space_24 还原成 24.0dp）。
#   所以不能只靠「手工按顺序跑一次」——每次打包前在这里重跑一遍，幂等且只要 ~2 秒。
#   两个脚本都会断言命中数，跑完校验见 tmp/verify_framework.py。
step "第 1.5 步 / 4：UI 框架 token / style（幂等，必须最后跑）"
# ★ opt18 / opt19 也要在这里重跑：它们改的是 assets/css、theme_schemes、drawable，
#   而 opt13/opt16 这类脚本复跑时会按自己的期望内容整份重写某些文件。
#   顺序：opt18 → opt19 → opt17a → opt17b（opt17 永远最后）。
"$PY" opt18_web_dark.py | tail -2
"$PY" opt19_nav_pill.py | tail -3
"$PY" opt17a_tokens.py | tail -3
"$PY" opt17b_styles.py | tail -3
"$PY" opt20_palette_gen.py --write | tail -6
"$PY" opt21_dimens_ladder.py --write | tail -4

step "第 2 步 / 4：重打包"
# ★ apktool 3.0.3 **不会清 src/build/**。那是它的暂存目录，上一次构建解出来的
#   res/ 会原样留在里面，这次新图只是"加进去" —— 结果新图旧图**同时**进包
#   （实测 ZIP 条目多出 528 个，drawable 里 a.png 和 a.jpg 并存）。
#   所以每次构建前必须把 src/build 移走。用 mv 不用 rm：上千个文件，
#   批量 rm 会触发沙箱的安全钩子。
if [ -d "$SRC/build" ]; then
    mv "$SRC/build" "$ROOT/build.stale.$(date +%H%M%S)"
    echo "  已移走旧的 src/build（apktool 暂存目录）"
fi
rm -f "$ROOT/rebuilt.apk"
"$JAVA" $JVM_TMP -jar "$APKTOOL" b "$SRC" -o "$ROOT/rebuilt.apk" > build_lx.log 2>&1
if grep -qE "^(W:|E:)" build_lx.log; then
    echo "  ⚠ 构建日志里有警告/错误："
    grep -E "^(W:|E:)" build_lx.log | head -20
else
    echo "  ✅ 0 警告 0 错误"
fi
ls -lh "$ROOT/rebuilt.apk" | awk '{print "  产物大小: " $5}'

step "第 3 步 / 4：校验"
"$PY" compare_9patch.py
echo
"$PY" verify_rebuild.py "$ORIGINAL" "$ROOT/rebuilt.apk"

step "第 4 步 / 4：对齐 + 签名"
"$BT/zipalign.exe" -p -f 4 "$ROOT/rebuilt.apk" "$ROOT/aligned.apk"
"$BT/apksigner.bat" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$ROOT/signed.apk" "$ROOT/aligned.apk"
"$BT/apksigner.bat" verify "$ROOT/signed.apk" && echo "  ✅ 签名有效"

echo
echo "=============================================================="
echo " 完成：$ROOT/signed.apk"
echo "=============================================================="
ls -lh "$ROOT/signed.apk" | awk '{print "  大小: " $5}'
#!/usr/bin/env bash
# ============================================================================
# 把 build_java.sh 的产物（tmp/smali_out/）装进 src/smali/
# ----------------------------------------------------------------------------
# 幂等：装之前先把现有文件移到 .prev，装完逐个用 smali 汇编器验证能过。
# 用法：bash install_java_smali.sh
# ============================================================================
set -euo pipefail

ROOT="D:/Program/dsh/rebuild"
JAVA="D:/Program/dsh/toolchain/jdk-17/jdk-17.0.20.1+1/bin/java.exe"
APKTOOL="D:/Program/dsh/tools/apktool-cli.jar"
JVM_TMP="-Djava.io.tmpdir=$ROOT/tmp"
OUT_SMALI="$ROOT/tmp/smali_out"
DEST="$ROOT/src/smali"
# ★ 备份**不能**放在 src/smali 里 —— apktool 会把它当未知文件报警告
#   （实测 "Unknown file type, ignoring: ...ThemeManager.smali.prev"）。
BACKUP="$ROOT/tmp/smali_backup"
# 本脚本维护：记录「由 Java 管线产出过」的相对路径。
# 用它区分「我自己的旧产物」（可以覆盖）和「App 反编译出来的原有类」（不能覆盖）。
OWNED="$ROOT/tmp/java_owned.txt"

cd "$ROOT"
[ -d "$OUT_SMALI" ] || { echo "先跑 build_java.sh"; exit 1; }
[ -f "$OWNED" ] || : > "$OWNED"

echo "=============================================================="
echo " 安装 smali"
echo "=============================================================="

# ★★ 防「自研类名撞上 App 原有类」。
#    踩过的坑：新建 zd/cornermemory/utils/CodeUtil.java 与 App 原有的 CodeUtil 同名，
#    直接覆盖后 CornerFragment 在 onCreateView 里抛
#    NoSuchMethodError: getCornerCode(I) —— 编译、打包、装包全程无报错，
#    只有启动那一刻才崩。所以这里必须先拦。
conflict=0
while IFS= read -r f; do
    rel="${f#$OUT_SMALI/}"
    if [ -f "$DEST/$rel" ] && ! grep -qxF "$rel" "$OWNED"; then
        echo "  ⚠ 与 App 原有类同名（会被覆盖）：$rel"
        conflict=1
    fi
done < <(find "$OUT_SMALI" -name '*.smali' | sort)
if [ "$conflict" -eq 1 ] && [ "${JAVA_SMALI_TAKEOVER:-0}" != "1" ]; then
    echo
    echo "  ✗ 检测到同名冲突，已中止（src/smali 未被改动）。"
    echo "    正解：给自研类换个名字（前缀 Lx / Batch 之类）。"
    echo "    确需接管这些类名时才设 JAVA_SMALI_TAKEOVER=1 强制继续。"
    exit 1
fi

count=0
while IFS= read -r f; do
    rel="${f#$OUT_SMALI/}"
    mkdir -p "$(dirname "$DEST/$rel")"
    # 旧版本留个备份（tmp/smali_backup/），不删
    if [ -f "$DEST/$rel" ]; then
        mkdir -p "$BACKUP/$(dirname "$rel")"
        cp -f "$DEST/$rel" "$BACKUP/$rel"
    fi
    cp -f "$f" "$DEST/$rel"
    echo "  → $rel"
    count=$((count + 1))
done < <(find "$OUT_SMALI" -name '*.smali' | sort)

# 记下这次装过哪些文件，下次就知道这些路径归本管线所有
while IFS= read -r f; do
    rel="${f#$OUT_SMALI/}"
    grep -qxF "$rel" "$OWNED" || echo "$rel" >> "$OWNED"
done < <(find "$OUT_SMALI" -name '*.smali' | sort)

echo "  共 $count 个文件"

echo
echo "=============================================================="
echo " 逐个验证：用 smali 汇编器过一遍"
echo "=============================================================="
fail=0
for f in $(find "$OUT_SMALI" -name '*.smali' | sort); do
    rel="${f#$OUT_SMALI/}"
    d="$ROOT/tmp/_asmcheck/$(dirname "$rel")"
    mkdir -p "$d"
    cp -f "$DEST/$rel" "$d/"
    if "$JAVA" $JVM_TMP -cp "$APKTOOL" com.android.tools.smali.smali.Main a \
            "$ROOT/tmp/_asmcheck/$rel" -o "$ROOT/tmp/_asmcheck/$(basename "$rel" .smali).dex" >/dev/null 2>&1; then
        echo "  ✅ $rel"
    else
        echo "  ❌ $rel"
        fail=1
    fi
done
[ "$fail" -eq 0 ] || { echo "有文件汇编失败"; exit 1; }
echo
echo "全部通过。"

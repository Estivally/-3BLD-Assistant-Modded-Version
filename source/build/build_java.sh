#!/usr/bin/env bash
# ============================================================================
# 把 src_java/ 下的自研 Java 源码编译成 smali，装进 src/smali/
# ----------------------------------------------------------------------------
# 流程：javac（源码）→ d8（dex）→ baksmali（smali）→ 拷进 src/smali/
#
# 为什么要这么绕：这是个反编译重建工程，没有 Gradle 源码工程。
# 手写 smali 容易出错，所以新代码一律写 Java、用 javac 做类型检查，
# 再转成 smali 塞回去。
#
# ★ 关键点（都是踩过的坑）
#   1) javac 在这台机器上默认 GBK → 带中文的源码必须 -encoding UTF-8
#   2) 给 javac 的 -cp 必须是 Windows 路径（cygpath -w），
#      Unix 路径会让它报"找不到符号 android.content"，看着像缺依赖
#   3) tmp/stub/ 是「只为让 javac 过」的假类。javac 把继承来的方法调用
#      写在**接收者的静态类型**上，所以 stub 只要声明这些方法就行，
#      真实继承关系运行时再解析。stub 只进 -cp，不进最终 dex。
#   4) d8 加 --classpath <stub> 可以消掉 "Type ... was not found" 警告
#
# 用法：bash build_java.sh
# ============================================================================
set -euo pipefail

ROOT="D:/Program/dsh/rebuild"
JAVA_HOME_DIR="D:/Program/dsh/toolchain/jdk-17/jdk-17.0.20.1+1"
JAVAC="$JAVA_HOME_DIR/bin/javac.exe"
JAVA="$JAVA_HOME_DIR/bin/java.exe"
APKTOOL="D:/Program/dsh/tools/apktool-cli.jar"
SDK="${ANDROID_SDK:-C:/Users/Estiavl/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-30/android.jar"
JVM_TMP="-Djava.io.tmpdir=$ROOT/tmp"

SRC_JAVA="$ROOT/src_java"
STUB_SRC="$ROOT/tmp/stub/src"
STUB_CLASSES="$ROOT/tmp/stub/classes"
OUT_CLASSES="$ROOT/tmp/classes"
OUT_DEX="$ROOT/tmp/dex"
OUT_SMALI="$ROOT/tmp/smali_out"
LIBS="$ROOT/tmp/libs/appcompat.jar"

cd "$ROOT"
step() { echo; echo "=============================================================="; echo " $1"; echo "=============================================================="; }

mkdir -p "$STUB_CLASSES" "$OUT_CLASSES" "$OUT_DEX" "$OUT_SMALI"

# 每次重编都清空，避免旧 class 残留混进 dex
find "$OUT_CLASSES" -name '*.class' -delete 2>/dev/null || true
rm -rf "$OUT_SMALI"
mkdir -p "$OUT_SMALI"

# --- cygpath：给 javac 的路径必须是 Windows 形式 -----------------------------
STUB_W=$(cygpath -w "$STUB_CLASSES")
ANDROID_W=$(cygpath -w "$ANDROID_JAR")
LIBS_W=$(cygpath -w "$LIBS")
OUT_W=$(cygpath -w "$OUT_CLASSES")

step "第 1 步 / 4：编译 stub（只为 javac 过检，不进 dex）"
find "$STUB_SRC" -name '*.java' > tmp/_stub_srcs.txt
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$ANDROID_W;$LIBS_W" \
    -d "$STUB_CLASSES" \
    @tmp/_stub_srcs.txt
echo "  ✅ stub 编译完成：$(find "$STUB_CLASSES" -name '*.class' | wc -l) 个 class"

step "第 2 步 / 4：编译 src_java"
find "$SRC_JAVA" -name '*.java' > tmp/_java_srcs.txt
cat tmp/_java_srcs.txt | sed "s|^|    |"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$STUB_W;$ANDROID_W;$LIBS_W" \
    -d "$OUT_CLASSES" \
    @tmp/_java_srcs.txt
echo "  ✅ 编译完成：$(find "$OUT_CLASSES" -name '*.class' | wc -l) 个 class"

step "第 3 步 / 4：d8 转 dex"
find "$OUT_CLASSES" -name '*.class' > tmp/_classes.txt
"$BT/d8.bat" --release --min-api 21 \
    --lib "$ANDROID_W" \
    --classpath "$STUB_W" \
    --output "$OUT_DEX" \
    @tmp/_classes.txt 2>&1 | sed 's/^/  /'
ls -l "$OUT_DEX/classes.dex" | awk '{print "  classes.dex: " $5 " B"}'

step "第 4 步 / 4：baksmali 还原成 smali"
"$JAVA" $JVM_TMP -cp "$APKTOOL" com.android.tools.smali.baksmali.Main d \
    "$OUT_DEX/classes.dex" -o "$OUT_SMALI"
echo "  产出 smali："
find "$OUT_SMALI" -name '*.smali' | sed "s|$OUT_SMALI/||" | sed 's|^|    |'

echo
echo "=============================================================="
echo " 下一步：确认无误后跑 install_java_smali.sh 拷进 src/smali/"
echo "=============================================================="

#!/usr/bin/env bash
# 开发机沙箱库：vendored QuickJS + 桥接层 → 桌面 JVM 可加载的动态库。
#
# Windows 必须用 MinGW-w64：上游 QuickJS 无条件 #include <sys/time.h> 并使用
# __attribute__((packed)) / __builtin_*，MSVC 与 VS 自带的 clang-cl（UCRT 头）都编不了；
# 桥接层的 _WIN32 垫片只依赖 GetTickCount64 / GetCurrentThreadStackLimits / _msize。
# Linux（含 WSL）gcc 直接可编。
#
# 产物：lib_book_source/build/desktop-js/{ebook_js.dll | libebook_js.so}
# 消费方：DesktopJsHostTest 经 QuickJsNative 的 System.loadLibrary + 测试任务的
# java.library.path 加载。库缺席时测试按「跳过」处置，所以本脚本**不进** test 依赖链，
# 只由 :lib_book_source:buildDesktopJsLib 显式触发。
#
# android/log.h 垫片在这里**现场生成**：本机加密工具会把新落盘的 .h 变成密文，
# 构建期生成绕开这个窗口（本脚本自己每跑一次重写一份）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
Q="$ROOT/third_party/quickjs"
TP="$ROOT/third_party"
BR="$ROOT/lib_book_source/src/main/cpp/bridge"
OUT="$ROOT/lib_book_source/build/desktop-js"

mkdir -p "$OUT/obj" "$OUT/shim/android"
cat > "$OUT/shim/android/log.h" <<'SHIM'
#ifndef SPK_ANDROID_LOG_H
#define SPK_ANDROID_LOG_H
/* 桌面构建垫片：桥接层只用它打「接线坏了」这类日志。真机由 NDK 提供。 */
#include <stdio.h>
#include <stdarg.h>
enum {
    ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL,
};
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void) prio;
    va_list ap; va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag ? tag : "?");
    int n = vfprintf(stderr, fmt, ap);
    va_end(ap); fputc('\n', stderr);
    return n;
}
#endif
SHIM

# —— JDK 的 jni.h：桥接层要它 ——
JH="${JAVA_HOME:-}"
if [ -z "$JH" ]; then
  JH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.home = //p' | tail -1)"
fi
[ -n "$JH" ] || { echo "找不到 JDK（JAVA_HOME 未设置且 PATH 里没有 java）" >&2; exit 2; }

KSRC="quickjs libregexp libunicode cutils dtoa"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    # —— 定位 MinGW-w64：显式 MINGW_BIN > PATH > winget 的 WinLibs 安装位置 ——
    MINGW_BIN="${MINGW_BIN:-$(dirname "$(command -v x86_64-w64-mingw32-gcc.exe 2>/dev/null || true)")}"
    if [ ! -x "$MINGW_BIN/x86_64-w64-mingw32-gcc.exe" ]; then
      for d in "$LOCALAPPDATA/Microsoft/WinGet/Packages/"BrechtSanders.WinLibs.*.UCRT*/mingw64/bin; do
        if [ -x "$d/x86_64-w64-mingw32-gcc.exe" ]; then MINGW_BIN="$d"; break; fi
      done
    fi
    [ -n "$MINGW_BIN" ] && [ -x "$MINGW_BIN/x86_64-w64-mingw32-gcc.exe" ] || {
      echo "找不到 MinGW-w64：winget install BrechtSanders.WinLibs.POSIX.UCRT（或设 MINGW_BIN 指向其 bin）" >&2
      exit 3
    }
    GCC="$MINGW_BIN/x86_64-w64-mingw32-gcc.exe"
    GXX="$MINGW_BIN/x86_64-w64-mingw32-g++.exe"
    echo "compiler: $("$GCC" --version | head -1)"
    for f in $KSRC; do
      "$GCC" -O2 -fwrapv -funsigned-char -D_GNU_SOURCE -DCONFIG_VERSION='"2026-06-04"' \
          -I"$Q" -c "$Q/$f.c" -o "$OUT/obj/$f.o"
    done
    "$GXX" -O2 -I"$TP" -I"$OUT/shim" -I"$JH/include" -I"$JH/include/win32" \
        -c "$BR/js_bridge.cpp" -o "$OUT/obj/js_bridge.o"
    # 静态带 libgcc/libstdc++/winpthread：DLL 自包含，开发机不用再配运行时 PATH
    "$GXX" -shared -o "$OUT/ebook_js.dll" "$OUT"/obj/*.o \
        -static-libgcc -static-libstdc++ -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic -lm
    echo "built: $OUT/ebook_js.dll"
    ;;
  *)
    echo "compiler: $(gcc --version | head -1)"
    for f in $KSRC; do
      gcc -O2 -fwrapv -funsigned-char -fPIC -D_GNU_SOURCE -DCONFIG_VERSION='"2026-06-04"' \
          -I"$Q" -c "$Q/$f.c" -o "$OUT/obj/$f.o"
    done
    g++ -O2 -fPIC -I"$TP" -I"$OUT/shim" -I"$JH/include" -I"$JH/include/linux" \
        -c "$BR/js_bridge.cpp" -o "$OUT/obj/js_bridge.o"
    g++ -shared -o "$OUT/libebook_js.so" "$OUT"/obj/*.o -ldl -lpthread -lm
    echo "built: $OUT/libebook_js.so"
    ;;
esac

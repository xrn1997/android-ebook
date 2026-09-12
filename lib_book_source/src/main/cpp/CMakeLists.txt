cmake_minimum_required(VERSION 3.22.1)
project(ebook_js C CXX)

# vendored 内核与本模块的距离只有构建脚本自己知道，不复制到别处（改路径只改这一行）
set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(QJS_DIR ${THIRD_PARTY_DIR}/quickjs)

add_library(quickjs STATIC
        ${QJS_DIR}/quickjs.c
        ${QJS_DIR}/libregexp.c
        ${QJS_DIR}/libunicode.c
        ${QJS_DIR}/cutils.c
        ${QJS_DIR}/dtoa.c)
# PRIVATE：内核自己全部是 `#include "xxx.h"`（相对源文件所在目录），不需要搜索路径；
# 留这一行只是为了让将来往这个 target 里添编译单元时不必先补路径。
# **不再** PUBLIC 给消费者，原因见下面 ebook_js 那段。
target_include_directories(quickjs SYSTEM PRIVATE ${QJS_DIR})
# CONFIG_VERSION 必须定义：JS_DumpMemoryUsage() 无条件拼接它，漏了那一行编不过
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
# -fwrapv / -funsigned-char 是内核依赖的行为，不是风格选项；-Werror 刻意不开
# （NDK clang 对 bionic 头会给出上游没有的额外警告）
target_compile_options(quickjs PRIVATE
        -O2 -fwrapv -funsigned-char -Wall
        -Wno-sign-compare -Wno-missing-field-initializers -Wno-unused-parameter)

add_library(ebook_js SHARED bridge/js_bridge.cpp)
# 消费者只挂 third_party **父目录**、引头写 `#include "quickjs/quickjs.h"`。
# 不把 ${QJS_DIR} 直接放进搜索路径：那里面有上游签入的 `VERSION` 文件，而 Windows 的文件系统
# 大小写不敏感——libc++ 的 `#include <version>` 会命中它，症状是在完全没碰过的 STL 头链里报
# 「expected unqualified-id」，且只在 Windows 上出现（Linux/macOS 区分大小写，撞不上）。
# SYSTEM 保留（父目录同样标 SYSTEM）：内核头经我们这个 TU 实例化时产生的警告不由本仓背。
target_include_directories(ebook_js SYSTEM PRIVATE ${THIRD_PARTY_DIR})
target_compile_options(ebook_js PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
find_library(log-lib log)
# 不链 pthread：bionic 自 API 23 起把 pthread 实现在 libc 里，NDK sysroot 没有 libpthread.so
# （-lpthread 会让 ld.lld 当场报 unable to find library）。内核 Atomics 等待队列要的符号
# 由 libc 正常解析，-latomic 由 AGP 的链接行自己追加。
target_link_libraries(ebook_js quickjs ${log-lib} dl m)

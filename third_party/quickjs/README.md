# QuickJS（vendored）

上游：<https://github.com/bellard/quickjs>，提交 `04be246001599f5995fa2f2d8c91a0f198d3f34c`，
`VERSION` 文件记为 `2026-06-04`。许可证 `LICENSE`（MIT），随源码一并保留。

**这些文件是上游原文，未经修改。** 校验：`cd third_party/quickjs && sha256sum -c PIN.sha256`
（17 个上游字节文件全在内：15 个参与编译的源与头，加 `LICENSE`、`VERSION`）。

**行尾是这行校验的前提**：上游文件为纯 LF，而 Windows 上 `core.autocrlf=true` 会把检出转成
CRLF——每行多一个 `\r`，内容一个字没错却逐字节不等。根 `.gitattributes` 的
`third_party/quickjs/** -text` 挡掉这个转换；校验不过时先确认不是行尾问题
（`tr -dc '\r' < quickjs.c | wc -c` 为零才算干净），再去怀疑内核被改过。

## 为什么是 vendored 而不是子模块或预编译产物

执行陌生 JavaScript 是本仓唯一的「远程代码执行」面，内核必须在审查时当场可读、构建时不依赖
外网可达性（见 ADR-0028 决策 1）。子模块的 checkout 内容随远端移动，预编译 `.a` 无法审计。

## 为什么是这份清单：15 个编译单元 + 2 个随行元数据

`quickjs.c libregexp.c libunicode.c cutils.c dtoa.c` 是内核的全部必需编译单元；其余为它们
include 的头（`libunicode-table.h` 是上游签入的生成物，**不需要任何代码生成步骤**）。
`LICENSE` 与 `VERSION` 不参与编译，随行保留是为了许可证与上游版本能当场对账。
刻意排除：

- `quickjs-libc.c`——POSIX/`os.*`/worker/信号处理层。沙箱的前提就是脚本看不到这些能力。
- `libbf.c`——本版上游已删除 bigfloat/`CONFIG_BIGNUM`，不存在可选形态。

## 内核里两处与「零能力面」相关的事实（2026-09-09 核查）

- `quickjs.c` 含 `pthread_*` 的 Atomics.wait 等待队列（约 15 处）。它不是 `os.*` 能力，
  而是语言内建对象的一部分，去掉就等于改内核，所以随包存在，靠 `JS_SetCanBlock(rt, 0)`
  在运行时让它直接失败——执行器线程上做不起「等别人唤醒」。
- `dtoa.c:33` 有 `#include <setjmp.h>`，全文没有任何 `setjmp`/`longjmp`/`jmp_buf` 使用，
  是上游的死 include。内核的超时与栈溢出**不走** `setjmp`：超时走 `JS_SetInterruptHandler`，
  深递归走 `JS_SetMaxStackSize` 的内核栈检查。

## 升级内核

一次显式改动：取新提交 → 重拷同一份文件清单 → 重新生成 `PIN.sha256` → 重跑
`lib_book_source/src/androidTest` 的沙箱用例（限值、中断、异常档案三项）。
构建期需要的宏见 `lib_book_source/src/main/cpp/CMakeLists.txt`。

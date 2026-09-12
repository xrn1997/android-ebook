// 沙箱桥接层：内核与主进程之间唯一的原生代码。
//
// 它只做四件事：建 runtime 并按限值管住它、把绑定挂成全局量、跑一段脚本文本、
// 把结果说清楚（描述符）。它不认识白名单、不认识 Binder、不认识规则语法。
//
#include <jni.h>
#include <android/log.h>

// 下面三项都是「桥接层自己接管资源限值」要用的：malloc/free/realloc 与 malloc_usable_size 用于
// 复刻内核的默认分配器（只为多记一次「分配被拒」），栈区探测用于问出本线程真实的栈。
#if defined(_WIN32)
// 桌面（Windows）构建：内核认的 sys/time.h 与 pthread.h 由 MinGW-w64 提供（MSVC 不行，见工程说明），
// 这里只补三处 Android/bionic 专属的符号。只用到两个 Win32 API，故直接声明、不引 <windows.h>——
// 引了会把 min/max 一类宏带进这个 TU，而后面的内核头对宏很敏感。
extern "C" {
// 含休眠的启动毫秒数，与 CLOCK_BOOTTIME 同口径（QueryUnbiasedInterruptTime 不含休眠，不能用）
__declspec(dllimport) unsigned long long __stdcall GetTickCount64(void);
// 本线程栈区（低端在 *low）。Win8+ 才有；退化时 thread_stack_low 返回 false，走假定值兜底
__declspec(dllimport) void __stdcall GetCurrentThreadStackLimits(unsigned long long *low,
                                                                unsigned long long *high);
}
#include <malloc.h>
// MinGW/MSVC 的 CRT 没有 malloc_usable_size，等价物是 _msize（同为「该指针的可用字节数」）。
// 不是完整等号：Windows 的分配器与 glibc 不同，usable size 的记账口径因此有微小差异，
// 这只影响堆限值的边界松紧，不影响「有没有被拒」这一判据。
// 注意这是宏，不会误伤 bridge_malloc_usable_size——那是另一个标识符。
#define malloc_usable_size(ptr) _msize(const_cast<void *>(ptr))
#else
#include <malloc.h>
#include <pthread.h>
#endif

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <string>
#include <vector>

// 前缀 quickjs/ 是必需的，不是风格：见 CMakeLists 里 THIRD_PARTY_DIR 那段——内核目录里有
// 上游签入的 VERSION 文件，直接把它挂进搜索路径会在 Windows 上遮蔽 libc++ 的 <version>。
#include "quickjs/quickjs.h"

#define LOG_TAG "EbookJs"
// 原生侧的日志只用于「接线坏了」这一类当场没法变成返回值的情况（Kotlin 侧的日志统一走 lib_common）
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr char DISPATCHER_CLASS[] = "com/ebook/source/sandbox/HostDispatcher";
constexpr char DISPATCHER_METHOD[] = "handle";
constexpr char DISPATCHER_SIG[] = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

// 微任务轮数上限。中断器只管解释器，`while (true) Promise.resolve().then(f)` 这类自驱动的
// 微任务队列要在这里断，否则 wallClockMs 形同虚设。
constexpr int MAX_PENDING_JOBS = 10000;

// 垫片里 reject() 抛出的前缀（Task 4）。桥接层不认识白名单，只搬运这一条前缀约定。
constexpr char UNSUPPORTED_PREFIX[] = "__UNSUPPORTED__:";

// 三句兜底描述符写成编译期常量：走到这些分支时内核可能已经处于 OOM 状态，
// 再走一遍「建对象 + stringify」正是最不可靠的路，而返回值绝不能是 null。
constexpr char DESCRIPTOR_UNSERIALIZABLE[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"脚本的完成值无法序列化成 JSON（循环引用或 BigInt）\"}";
constexpr char DESCRIPTOR_BRIDGE_BROKEN[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"沙箱桥接层未初始化\"}";
constexpr char DESCRIPTOR_UNDELIVERABLE[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"结果字符串无法送达主进程\"}";

// 内存超限的兜底描述符。堆真的到限时，内核那句 `out of memory` 自己也要分配（先建错误对象、
// 再建 message，quickjs.c:7639），同样可能被当场拒掉——那种失败到了分类这一步只剩「这条异常说不出
// 任何独立的信息」，唯一的直接证据是分配器拒过分配（判法见 take_exception_descriptor）。
// 仍写成静态常量，而且这里比别的兜底更硬：走到这一步时**堆就是满的**，现建「对象 + 两个字符串属性」
// 正是那笔被拒的分配，走 exception_descriptor 只会再失败一次、落回 DESCRIPTOR_UNSERIALIZABLE，
// 于是分类又变回 RUNTIME。message 用内核本来想说的那句（quickjs.c 的 JS_ThrowOutOfMemory 抛的就是
// InternalError("out of memory")），JsProtocol.mapStatus 的关键字判定因此不需要为这条路新增任何词汇。
constexpr char DESCRIPTOR_OOM[] =
    "{\"kind\":\"exception\",\"timedOut\":false,\"errorName\":\"InternalError\","
    "\"errorMessage\":\"out of memory\"}";

JavaVM *g_vm = nullptr;
jclass g_dispatcher = nullptr;    // 全局引用：本地引用在回调发生时早没了
jmethodID g_handle_mid = nullptr;
jmethodID g_get_bytes_mid = nullptr;  // String.getBytes(String)
jstring g_utf8_name = nullptr;        // 全局引用的 "UTF-8"，免得每次回调建一个

// 执行器进程内一份：runtime 常驻，context 每个任务重建（ADR-0028 决策 5）。
struct Bridge {
    JSRuntime *rt = nullptr;
    JSContext *ctx = nullptr;
    std::atomic<int64_t> deadline_ms{INT64_MAX};
    std::atomic<int> interrupted{0};
    /**
     * 本次执行里分配器有没有拒过一次分配——「堆到限」唯一的直接证据（判法见 take_exception_descriptor）。
     *
     * 需要它，是因为堆到限时内核**说不出**那句话：JS_ThrowError2 建错误对象、建 message、乃至最后的
     * stringify 各要一次分配（quickjs.c:7639）。设备两跑各撞到一种落空形态：一次落成裸 `null`
     * （quickjs.c:7648 那条兜底分支），一次是对象在、message 为空且 stringify 也被拒。两种都不该报成 RUNTIME。
     */
    std::atomic<int> oom_refused{0};
    /** Kotlin 传下来的栈上限，只当**天花板**：每次执行再按本线程剩余栈收一道，见 stack_budget */
    size_t stack_ceiling = 0;
};

// 机器级单调钟：与主进程的 SystemClock.elapsedRealtime() 同口径——两者都把休眠时间算进去
// （停表的是 CLOCK_MONOTONIC，用它当截止钟会让「手机休眠期间的执行」看起来还没到期）
int64_t boot_time_ms() {
#if defined(_WIN32)
    // Windows 没有 CLOCK_BOOTTIME。GetTickCount64 是含休眠的启动毫秒数，与它同口径；
    // 不要换成 QueryUnbiasedInterruptTime —— 那个「unbiased」正是不含休眠，改用它会让
    // 休眠期间的执行不再计入截止时间，deadline 形同放宽。
    return static_cast<int64_t>(GetTickCount64());
#else
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
#endif
}

/*
 * 分配器：逐条复刻 quickjs.c 的 js_def_malloc / js_def_free / js_def_realloc /
 * js_def_malloc_usable_size，只在**每一条拒绝分配的路径**上多给 Bridge 记一票。
 *
 * 为什么要自己接管分配器，而不是事后从异常里推断：堆到限时内核走的是 JS_ThrowError2 的兜底分支
 * （quickjs.c:7648 那句注释是原话——"out of memory: throw JS_NULL to avoid recursing"），
 * 错误对象自己都建不出来，抛出的是裸 `null`，`name`/`message` 从来没有存在过；建出来了但 message
 * 那笔分配被拒也一样（内核不检查 JS_DefinePropertyValue 的返回值）。按 message 关键字归类就会把
 * 「内存超限」报成一次普通 RUNTIME——设备首跑实测到的正是后一种形态。
 * 而「这次执行里有没有分配被拒」只有分配器知道——从外面的用量数据推不出来：被拒的那一笔可能
 * 是一个几百 MB 的字符串，此时已用量离限值还远得很。
 *
 * 记账口径必须与上游逐字一致（malloc_size 走 usable size + MALLOC_OVERHEAD），否则限值本身会偏：
 * 这里多算一点就误杀正常源，少算一点就等于限值形同虚设。MALLOC_OVERHEAD 在非 Apple 平台取 8。
 */
constexpr size_t MALLOC_OVERHEAD_BYTES = 8;

// s->opaque 由 JS_NewRuntime2 的入参带进来（见 nativeCreate），指向本次执行的 Bridge。
Bridge *bridge_of(JSMallocState *s) { return static_cast<Bridge *>(s->opaque); }

void note_allocation_refused(JSMallocState *s) {
    if (Bridge *b = bridge_of(s)) b->oom_refused.store(1, std::memory_order_relaxed);
}

/*
 * 这里刻意**不**给「被拒之后的小额分配」留宽限窗口（曾想让内核自己把 out of memory 说完整，
 * 因为 JS_ThrowError2 建错误对象与 message 各要一次分配，quickjs.c:7639）。设备二跑否掉了它：
 * 到限之后先到来的分配是脚本自己那个每轮加一千字的循环，窗口在它下次越限时就被吃光，等错误路径
 * 要分配时已无余额——抛出的仍是裸 `null`，分类没有任何改善，而窗口开着的那段时间确确实实越过了
 * 配置的限值。分类不需要它（判据见 take_exception_descriptor：分配器拒过 + 这条异常说不出别的），
 * 所以只留代价、不留收益。
 */
void *bridge_malloc(JSMallocState *s, size_t size) {
    if (s->malloc_size + size > s->malloc_limit) {
        note_allocation_refused(s);
        return nullptr;
    }
    void *ptr = std::malloc(size);
    if (!ptr) {
        note_allocation_refused(s);
        return nullptr;
    }
    s->malloc_count++;
    s->malloc_size += malloc_usable_size(ptr) + MALLOC_OVERHEAD_BYTES;
    return ptr;
}

void bridge_free(JSMallocState *s, void *ptr) {
    if (!ptr) return;
    s->malloc_count--;
    s->malloc_size -= malloc_usable_size(ptr) + MALLOC_OVERHEAD_BYTES;
    std::free(ptr);
}

void *bridge_realloc(JSMallocState *s, void *ptr, size_t size) {
    if (!ptr) {
        if (size == 0) return nullptr;
        return bridge_malloc(s, size);
    }
    const size_t old_size = malloc_usable_size(ptr);
    if (size == 0) {
        s->malloc_count--;
        s->malloc_size -= old_size + MALLOC_OVERHEAD_BYTES;
        std::free(ptr);
        return nullptr;
    }
    if (s->malloc_size + size - old_size > s->malloc_limit) {
        note_allocation_refused(s);
        return nullptr;
    }
    void *next = std::realloc(ptr, size);
    if (!next) {
        note_allocation_refused(s);
        return nullptr;
    }
    s->malloc_size += malloc_usable_size(next) - old_size;
    return next;
}

size_t bridge_malloc_usable_size(const void *ptr) {
    return malloc_usable_size(const_cast<void *>(ptr));
}

const JSMallocFunctions BRIDGE_MALLOC_FUNCS = {
    bridge_malloc,
    bridge_free,
    bridge_realloc,
    bridge_malloc_usable_size,
};

/*
 * 栈预算。
 *
 * 内核那道栈检查判的是「距 stack_top 已经用掉多少字节」（quickjs.c 的 update_stack_limit：
 * stack_limit = stack_top - stack_size），所以**预算大于本线程剩余的栈**时，深递归会先把 native
 * 栈撞穿——SIGSEGV、整个 `:js` 进程没，内核那句 "stack overflow" 异常根本来不及抛（设备上实测到的
 * 第一个坑正是这个：配置值 1 MB，而跑用例的那条线程只有 512 KB 栈）。
 *
 * 剩余栈不是常数，也不该写成常数：执行器跑在 binder 线程上，测试跑在应用新建的 Java 线程上
 * （ART 给这类线程的默认栈又与主线程不同档），同一进程里 binder 每次还可能换人。
 * 所以每次执行前按**本线程**现算，配置值退成天花板——天花板之内的深度才是稳定行为，
 * 超出部分只是「这台机器的这条线程今天还能给多少」。
 */

// nativeEval 之上还压着 JNI trampoline、ART 与 Kotlin 的帧，内核每次 JS 调用又要在 C 栈上放一帧
// （连 opnd 空间一起，见 quickjs.c 里 alloca_size 的算法）。这一笔不归栈检查支配。
// 96 KB ≈ 6 个页：Android 的 16 KB page 模式（API 36+ 的镜像）下一页就是 16 KB，按 4 KB 留会不够。
constexpr size_t STACK_HEADROOM_BYTES = 96 * 1024;

// 问不到本线程栈区时的假定值，取最窄的已知形态：猜松了会撞穿进程，猜紧了只是递归浅一点。
constexpr size_t ASSUMED_THREAD_STACK_BYTES = 512 * 1024;

// 问出本线程栈区的低端地址（栈向低地址长）。bionic 把 pthread 实现在 libc 里，不需要额外链接。
bool thread_stack_low(uintptr_t *out) {
#if defined(_WIN32)
    // Windows 没有 pthread_getattr_np。GetCurrentThreadStackLimits 直接给出本线程栈区，
    // 口径与「栈区低端地址」一致，且天然跟随当前线程（不依赖 pthread_self）。
    unsigned long long low = 0, high = 0;
    GetCurrentThreadStackLimits(&low, &high);
    if (low == 0 || high <= low) return false;
    *out = static_cast<uintptr_t>(low);
    return true;
#else
    pthread_attr_t attr{};
    if (pthread_getattr_np(pthread_self(), &attr) != 0) return false;
    void *base = nullptr;
    size_t size = 0;
    const int rc = pthread_attr_getstack(&attr, &base, &size);
    pthread_attr_destroy(&attr);
    if (rc != 0 || base == nullptr || size == 0) return false;
    *out = reinterpret_cast<uintptr_t>(base);
    return true;
#endif
}

size_t stack_budget(size_t ceiling) {
    const auto sp = reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
    uintptr_t low = 0;
    const bool known = thread_stack_low(&low) && sp > low;
    const size_t span = known ? sp - low : ASSUMED_THREAD_STACK_BYTES;
    if (span <= STACK_HEADROOM_BYTES) return 1;
    // 1 而不是 0：内核里 stack_size==0 的语义是「不限」（update_stack_limit 明写 no limit），
    // 把「剩余栈不够」表达成 0 会在最危险的姿势上把这道检查整个关掉。取 1 字节则第一次函数调用
    // 就以 stack overflow 失败——一句能归类、能显示、不撞进程的话。
    const size_t budget = std::min(ceiling, span - STACK_HEADROOM_BYTES);
    return budget == 0 ? 1 : budget;  // ceiling 为 0 在内核里同样是「不限」，沙箱不接受这种配置
}

// 内核中断器：返回非 0 就让当前求值以 InternalError("interrupted") 结束。
// 只在解释器轮询点被调用，因此打不断一次阻塞中的原生调用——host call 的时限由主进程侧兜。
int on_interrupt(JSRuntime *, void *opaque) {
    auto *b = static_cast<Bridge *>(opaque);
    if (boot_time_ms() >= b->deadline_ms.load(std::memory_order_relaxed)) {
        b->interrupted.store(1, std::memory_order_relaxed);
        return 1;
    }
    return 0;
}

// 一次执行的三份状态都在这里归零、离开时收尾（死线放回去，否则任务之间任何一次求值——
// 比如下一次 reset 重放垫片——都会被上一条规则的死线打断）。oom_refused 必须与死线同域：
// 它是「本次执行里堆到过限」的证据，留着不清会让下一条正常的规则也报成内存超限。
struct DeadlineScope {
    Bridge *b;
    explicit DeadlineScope(Bridge *bridge, int64_t deadline) : b(bridge) {
        b->interrupted.store(0, std::memory_order_relaxed);
        b->oom_refused.store(0, std::memory_order_relaxed);
        b->deadline_ms.store(deadline, std::memory_order_relaxed);
    }
    DeadlineScope(const DeadlineScope &) = delete;
    DeadlineScope &operator=(const DeadlineScope &) = delete;
    ~DeadlineScope() { b->deadline_ms.store(INT64_MAX, std::memory_order_relaxed); }
};

// JS 值的作用域包装。nativeEval 有五六条出口，手工配对的 JS_FreeValue 必漏一条——
// 漏了不报错，只在 JS_FreeContext 时留一行没人看的 leak 日志。
// 释放 JS_EXCEPTION 是安全的（内核的 JS_FreeValue 对无引用标签直接返回）。
struct ValueGuard {
    JSContext *ctx;
    JSValue v;
    ValueGuard(JSContext *c, JSValue value) : ctx(c), v(value) {}
    ValueGuard(const ValueGuard &) = delete;
    ValueGuard &operator=(const ValueGuard &) = delete;
    ~ValueGuard() { JS_FreeValue(ctx, v); }
    JSValueConst get() const { return v; }
    void reset(JSValue next) {
        JS_FreeValue(ctx, v);
        v = next;
    }
};

/**
 * 一段**带结尾 '\0'** 的 UTF-8 文本。
 *
 * 存在的唯一理由：`JS_Eval` 与 `JS_ParseJSON` 都明确要求 `input[input_len] == '\0'`
 * （quickjs.h:835、:861 的注释原话是 "'input' must be zero terminated"），词法器在读最后一个
 * token 时会向前探一字节。JNI 拿回来的字节数组长度就是内容长度、没有结尾符，直接把
 * `vector::data()` 配 `vector::size()` 递进去，内核读的是**一字节越界**——不报错、不闪退，
 * 只在越界那字节恰好不是 0 时把上一段内存当脚本尾部读进去。这里把结尾符存进 vector 自己
 * 的地盘，`len` 单独记正文长度，两侧永远不可能对不上。
 */
struct Utf8Slice {
    std::vector<char> bytes;
    size_t len = 0;

    const char *data() const { return bytes.data(); }
    bool empty() const { return len == 0; }
};

// jstring → UTF-8 字节。刻意不走 GetStringUTFChars：那一路给的是「修改版 UTF-8」，
// 增补平面字符（emoji、CJK 扩展 B 的生僻字）是两段三字节代理对，内核的 unicode_from_utf8
// 认不出、逐个替换成 U+FFFD——症状是「正文里的 emoji 全变乱码」且只在真机上出现。
// 交给 JDK 的 getBytes("UTF-8")，编码由它负责。
bool jstring_to_utf8(JNIEnv *env, jstring text, Utf8Slice &out) {
    if (!text || !g_get_bytes_mid) return false;
    auto bytes = reinterpret_cast<jbyteArray>(env->CallObjectMethod(text, g_get_bytes_mid, g_utf8_name));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (!bytes) return false;
    const jsize n = env->GetArrayLength(bytes);
    jbyte *raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) {
        env->DeleteLocalRef(bytes);  // 每一条出口都要还：这些局部引用挂在 nativeEval 那一帧上
        return false;
    }
    out.bytes.assign(reinterpret_cast<char *>(raw), reinterpret_cast<char *>(raw) + n);
    out.bytes.push_back('\0');  // 见 Utf8Slice：结尾符是内核的合同，不是可选的礼貌
    out.len = static_cast<size_t>(n);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);  // 只读：不回写、不重复释放
    env->DeleteLocalRef(bytes);
    return true;
}

// 值 → JSON 文本。转义、嵌套、代理对全交给内核（它的 stringify 会把 < 0x20 与**每一个**代理码元
// 写成 \uXXXX——quickjs.c:49975 的 `c < 32 || is_surrogate(c)`，所以产物里没有裸控制字符）。
// 出向仍显式取 cesu8=1：过 JNI 的下一站是 NewStringUTF，它认的是「修改版 UTF-8」，
// 而内核的 cesu8=0 分支会把代理对合成一段 4 字节序列（cutils.c:229 起）——那在修改版 UTF-8 里
// 是非法起始字节，ART 直接替换成 U+FFFD。今天两条分支产物相同，但这个选择不该押在内核
// 恰好转义了代理对上；万一 stringify 的行为变了，错的方向是「正文里的 emoji 变乱码」而不是报错。
// 失败返回空串让调用方换兜底常量。
std::string to_json(JSContext *ctx, JSValueConst v) {
    JSValue text = JS_JSONStringify(ctx, v, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(text)) {
        JS_FreeValue(ctx, text);
        JS_FreeValue(ctx, JS_GetException(ctx));  // 必须取走：挂着的异常会让下一次求值当场失败
        return {};
    }
    std::string out;
    const char *c = JS_ToCStringLen2(ctx, nullptr, text, 1);
    if (c) {
        out = c;
        JS_FreeCString(ctx, c);
    } else {
        JS_FreeValue(ctx, JS_GetException(ctx));  // toString 自己抛的（符号化对象等）
    }
    JS_FreeValue(ctx, text);
    return out;
}

// 装一个字段。描述符对象是当场新建、脚本没机会冻结的，装不上只可能是内核 OOM——
// 那种情况取走异常、少一个字段（Kotlin 侧字段全有默认值），但不允许把异常留给下一次求值。
// val 的所有权**始终**交给 JS_SetPropertyStr：内核在每一条失败路径上都会 JS_FreeValue(val)
// （quickjs.c:10107 的 atom 分配失败、JS_SetPropertyInternal 的 add_property 失败等），
// 所以这里绝不再补一次 free——补了就是双重释放。
void put(JSContext *ctx, JSValueConst obj, const char *key, JSValue v) {
    if (JS_IsException(v)) {
        JS_FreeValue(ctx, v);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return;
    }
    if (JS_SetPropertyStr(ctx, obj, key, v) < 0) JS_FreeValue(ctx, JS_GetException(ctx));
}

void put_str(JSContext *ctx, JSValueConst obj, const char *key, const char *value) {
    put(ctx, obj, key, JS_NewString(ctx, value));
}

std::string finish(JSContext *ctx, JSValue desc) {
    std::string out = to_json(ctx, desc);
    JS_FreeValue(ctx, desc);
    return out.empty() ? std::string(DESCRIPTOR_UNSERIALIZABLE) : out;
}

std::string ok_descriptor(JSContext *ctx, JSValueConst value) {
    // undefined 与 null 必须显式落成 null，两个理由各致命一次：
    // stringify 会**丢掉**值为 undefined 的属性（`data` 就没了），而 `String(undefined)` 是 "undefined"。
    // 叠加的结果是「一条忘了给 result 赋值的规则产出词『undefined』当正文」——
    // 正是 2b 定的口径里禁止的「把没有值说成有值」。
    const bool nothing = JS_IsUndefined(value) || JS_IsNull(value);
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "ok");
    put(ctx, d, "data", nothing ? JS_NULL : JS_DupValue(ctx, value));
    // 没有 text 字段：文本由主进程侧的 JsOutcome.text 从 data 现场导出（见 Task 7 开头）
    return finish(ctx, d);
}

// 每种 kind 只发文档里那几个字段：JsRuntimeBridge 解描述符用的是与跨进程帧同样的严格解码，
// 多一个字段就是协议不符。
std::string exception_descriptor(JSContext *ctx, const std::string &name, const std::string &message,
                                 bool timed_out) {
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "exception");
    put(ctx, d, "timedOut", JS_NewBool(ctx, timed_out));
    put_str(ctx, d, "errorName", name.c_str());
    put_str(ctx, d, "errorMessage", message.c_str());
    return finish(ctx, d);
}

std::string unsupported_descriptor(JSContext *ctx, const std::string &message) {
    JSValue d = JS_NewObject(ctx);
    put_str(ctx, d, "kind", "unsupported");
    put_str(ctx, d, "errorMessage", message.c_str());
    return finish(ctx, d);
}

std::string read_string_prop(JSContext *ctx, JSValueConst obj, const char *prop, const std::string &fallback) {
    JSValue v = JS_GetPropertyStr(ctx, obj, prop);
    if (JS_IsException(v)) {
        // `throw null` / `throw undefined`：读属性这一步自己就抛了
        JS_FreeValue(ctx, v);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return fallback;
    }
    std::string out = fallback;
    if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
        // 这一处**必须**是 cesu8=0（标准 UTF-8）：产物下一步就喂回 JS_NewString，
        // 后者认的是内核自己的 UTF-8 词法（增补平面字符是一段 4 字节）。
        // 与 to_json 的方向正好相反，两个 flag 不能互换——见各自的注释。
        const char *c = JS_ToCString(ctx, v);
        if (c) {
            out = c;
            JS_FreeCString(ctx, c);
        } else {
            JS_FreeValue(ctx, JS_GetException(ctx));  // getter 自己抛的
        }
    }
    JS_FreeValue(ctx, v);
    return out;
}

// 内核当前挂着的异常 → 描述符。取走异常是这一步的副作用，调用方不必再管。
std::string take_exception_descriptor(JSContext *ctx, Bridge *b) {
    JSValue ex = JS_GetException(ctx);
    // 超时优先于内存——与 JsProtocol.mapStatus「先看中断标志」的次序保持一致。
    // 只判一次：下面两条兜底与它共用同一个前提，分两处读时钟就有长出第三种口径的机会。
    const bool timed_out = b->interrupted.load(std::memory_order_relaxed) != 0 ||
        boot_time_ms() >= b->deadline_ms.load(std::memory_order_relaxed);
    // 两个条件都要：分配器拒过（本次执行确实撞过限值），且这条异常说不出别的（裸 null、
    // stringify 也失败、或只回了自己的类名）。
    // 只凭「说不出话」会把脚本自己的 `throw null` 说成内存问题；只凭「拒过一次」会把一次无关的
    // 投机分配失败（正则回溯、rope 压缩都可能拒一次又恢复）扣在随后那条**可描述**的真异常头上。
    // 刻意**不**再加第三条「此刻用量还贴着限值」：设备二跑实测证明异常沿栈展开时临时值已被释放，
    // 到分类这一步的读数必然偏低，拿它当闸门会把真 OOM 又判回 RUNTIME。
    const bool oom = b->oom_refused.load(std::memory_order_relaxed) != 0 && !timed_out;
    // 裸 null/undefined（内核连错误对象都没建出来的形态，quickjs.c:7648）必须判在 stringify **之前**：
    // String(null) 是 "null"、这笔分配多半拿得到，先 stringify 就等于把「内存超限」
    // 洗成一条看着有内容的普通错误。
    const bool bare = JS_IsNull(ex) || JS_IsUndefined(ex);
    if (bare && oom) {
        JS_FreeValue(ctx, ex);
        return std::string(DESCRIPTOR_OOM);
    }
    std::string name = "Error";
    std::string message;
    if (!bare) {
        name = read_string_prop(ctx, ex, "name", "Error");
        message = read_string_prop(ctx, ex, "message", "");
    }
    bool silent = false;  // 连 stringify 都拿不到字节：设备首跑实测到的正是这一支
    if (message.empty()) {
        // 没有 message 的抛出（`throw 42`、`throw null`、message 属性没定义成的 Error）：
        // 用它的字符串形态，别空着。注意 Error.prototype.toString 对**没有 message** 的对象
        // 只回类名（quickjs.c:41539 那一支不拼冒号），所以下面用 `message == name` 认出
        // 「说出来的其实只有类名」= 内核那句 out of memory 没能落地。
        const char *c = JS_ToCString(ctx, ex);
        if (c) {
            message = c;
            JS_FreeCString(ctx, c);
        } else {
            JS_FreeValue(ctx, JS_GetException(ctx));
            silent = true;
            message = "（异常信息本身取不出来）";
        }
    }
    if (oom && (silent || message == name)) {
        JS_FreeValue(ctx, ex);
        return std::string(DESCRIPTOR_OOM);
    }
    JS_FreeValue(ctx, ex);

    if (message.rfind(UNSUPPORTED_PREFIX, 0) == 0) {
        return unsupported_descriptor(ctx, message.substr(sizeof(UNSUPPORTED_PREFIX) - 1));
    }
    return exception_descriptor(ctx, name, message, timed_out);
}

// 排空微任务队列。返回 false 表示这一轮已经失败、out 里装好了失败描述符。
bool drain_jobs(JSContext *ctx, Bridge *b, std::string &out) {
    JSContext *job_ctx = ctx;
    for (int i = 0; i < MAX_PENDING_JOBS; i++) {
        const int ret = JS_ExecutePendingJob(b->rt, &job_ctx);
        if (ret == 0) return true;  // 队列空了：脚本里的 await 与 .then 到这一刻才算跑完
        if (ret < 0) {
            // 内核按 runtime 取 job，抛错的可能不是本 context，描述符要问那个 context
            out = take_exception_descriptor(job_ctx, b);
            return false;
        }
    }
    if (JS_IsJobPending(b->rt)) {
        out = unsupported_descriptor(ctx, "微任务数量超过上限，脚本里大概有自驱动的 Promise 循环");
        return false;
    }
    return true;
}

// 沙箱里没有事件循环：一个等待中的 Promise 永远不会落定。取 `then` 判一下，拒掉——
// 折叠成「没有值」就是骗脚本说这页没内容（2b 的口径）
bool is_thenable(JSContext *ctx, JSValueConst v) {
    if (!JS_IsObject(v)) return false;
    JSValue then = JS_GetPropertyStr(ctx, v, "then");
    const bool broken = JS_IsException(then);  // 自定义 getter 抛的
    const bool yes = !broken && JS_IsFunction(ctx, then);
    JS_FreeValue(ctx, then);
    if (broken) JS_FreeValue(ctx, JS_GetException(ctx));
    return yes;
}

// 描述符过 JNI。**除下面注明的极边缘一种情形外绝不返回 null**：Kotlin 侧签名是非空 String，
// JNI 边界上凭空出现的空值会变成一条没有消息的 NPE，比任何一种失败描述都难查。
//
// 兜底串缓存成全局引用：NewStringUTF 在 OOM 下可能连续失败两次，原实现第二次失败就直接把 null
// 交回 Kotlin。首次用到兜底时建一个全局引用存下来（返回全局引用是 JNI 允许的，调用方拿到的就是
// 它），之后 OOM 路径只读这个引用、不再分配字符串，因而不会再失败。极端并发下首次初始化最多
// 多建一个全局引用（该引用随后即被丢弃），不构成正确性问题——这条路本就只在 JVM 已无内存时走到。
jstring deliver(JNIEnv *env, const std::string &text) {
    jstring result = env->NewStringUTF(text.c_str());
    if (result) return result;
    // NewStringUTF 失败（OutOfMemoryError）必带着待处理异常，不清会污染下一次 JNI 调用
    env->ExceptionClear();
    static jstring fallback = nullptr;
    if (!fallback) {
        jstring local = env->NewStringUTF(DESCRIPTOR_UNDELIVERABLE);
        if (!local) {
            // 连兜底串都建不出来（JVM 已耗尽内存、整进程都在崩），此时无法再给出任何描述，
            // 只能返回 null；这里是本函数唯一的非空契约缺口，Kotlin 侧会看到 NPE。
            env->ExceptionClear();
            return nullptr;
        }
        fallback = static_cast<jstring>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
    }
    return fallback;
}

// 脚本可见的唯一原生入口。白名单不在这里判——HostDispatcher 在 Kotlin 侧判第二次。
//
// 局部引用预算：这个函数跑在 nativeEval 那一帧里（不是自己的 JNI 栈帧），所以它建的每个
// 局部引用都要在每一条出口显式 DeleteLocalRef。脚本里一个 `for` 调几千次 `java.log` 就会
// 建几千个——ART 的自动局部引用表到 512 条即 `JNI ERROR: local reference table overflow`
// 直接 abort 整个 :js 进程，症状是「读到某本书就闪退」而不是「这条规则报错」。
JSValue host_call(JSContext *ctx, JSValueConst, int argc, JSValueConst *argv) {
    if (argc != 2) return JS_ThrowTypeError(ctx, "%s", "__host_call 需要 2 个参数");
    JNIEnv *env = nullptr;
    if (!g_vm || g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JS_ThrowInternalError(ctx, "%s", "host 回调：取不到 JNIEnv");
    }
    if (!g_dispatcher || !g_handle_mid) return JS_ThrowInternalError(ctx, "%s", "host 回调：主进程入口未就绪");

    // 出向用 cesu8=1：那正是 JNI「修改版 UTF-8」，代理对能无损过 NewStringUTF
    const char *api = JS_ToCStringLen2(ctx, nullptr, argv[0], 1);
    if (!api) return JS_EXCEPTION;  // 转换失败时内核的异常就挂在原地，直接返回 JS_EXCEPTION 才对
    const char *args = JS_ToCStringLen2(ctx, nullptr, argv[1], 1);
    if (!args) {
        JS_FreeCString(ctx, api);
        return JS_EXCEPTION;
    }
    jstring japi = env->NewStringUTF(api);
    jstring jargs = env->NewStringUTF(args);
    JS_FreeCString(ctx, api);
    JS_FreeCString(ctx, args);
    if (!japi || !jargs) {
        env->ExceptionClear();  // 同上：NewStringUTF 失败必带异常，留着它会打断本帧之后所有 JNI 调用
        if (japi) env->DeleteLocalRef(japi);
        if (jargs) env->DeleteLocalRef(jargs);
        return JS_ThrowInternalError(ctx, "%s", "host 回调：参数送不进主进程");
    }

    auto jreply = reinterpret_cast<jstring>(
        env->CallStaticObjectMethod(g_dispatcher, g_handle_mid, japi, jargs));
    env->DeleteLocalRef(japi);
    env->DeleteLocalRef(jargs);
    if (env->ExceptionCheck()) {  // dispatcher 内部已经兜住自己的异常；走到这里说明是 JVM 级问题
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (jreply) env->DeleteLocalRef(jreply);
        return JS_ThrowInternalError(ctx, "%s", "host 回调在主进程抛了异常");
    }
    if (!jreply) return JS_ThrowInternalError(ctx, "%s", "host 回调返回了 null");

    Utf8Slice reply;
    const bool decoded = jstring_to_utf8(env, jreply, reply);
    env->DeleteLocalRef(jreply);
    if (!decoded || reply.empty()) return JS_ThrowInternalError(ctx, "%s", "host 回调的应答读不出来");

    JSValue value = JS_ParseJSON(ctx, reply.data(), reply.len, "<host_reply>");
    if (JS_IsException(value)) {
        JS_FreeValue(ctx, value);
        JS_FreeValue(ctx, JS_GetException(ctx));
        return JS_ThrowInternalError(ctx, "%s", "主进程回的应答不是合法 JSON");
    }
    return value;
}

// __host_call 挂在 globalThis 上，而 globalThis 是 **context 级**的：重建 context 之后必须再装一次
bool install_host_call(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue fn = JS_NewCFunction(ctx, host_call, "__host_call", 2);
    // fn 的所有权无论成败都归 SetPropertyStr（失败路径上内核自己 free，见 put 的注释）
    const bool ok = JS_SetPropertyStr(ctx, global, "__host_call", fn) >= 0;
    if (!ok) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        LOGE("install __host_call failed");
    }
    JS_FreeValue(ctx, global);
    return ok;
}

// FindClass 必须在「被应用类调进来」的时刻做，不能放在 JNI_OnLoad：后者的 FindClass 用系统类加载器，
// 找不到应用类，症状是执行器一启动就 NoClassDefFoundError（Android JNI 的经典坑）。
bool ensure_dispatcher(JNIEnv *env) {
    if (g_dispatcher && g_handle_mid && g_get_bytes_mid && g_utf8_name) return true;

    jclass local = env->FindClass(DISPATCHER_CLASS);
    if (!local) {
        env->ExceptionClear();
        LOGE("FindClass(%s) failed", DISPATCHER_CLASS);
        return false;
    }
    g_dispatcher = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!g_dispatcher) return false;
    g_handle_mid = env->GetStaticMethodID(g_dispatcher, DISPATCHER_METHOD, DISPATCHER_SIG);

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class) {
        g_get_bytes_mid = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
        env->DeleteLocalRef(string_class);
    } else {
        env->ExceptionClear();
    }
    jstring utf8 = env->NewStringUTF("UTF-8");
    if (utf8) {
        g_utf8_name = reinterpret_cast<jstring>(env->NewGlobalRef(utf8));
        env->DeleteLocalRef(utf8);
    }
    if (!g_handle_mid || !g_get_bytes_mid || !g_utf8_name) {
        LOGE("dispatcher/JNI method lookup incomplete: handle=%p getBytes=%p", (void *) g_handle_mid,
             (void *) g_get_bytes_mid);
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeCreate(JNIEnv *env, jobject, jlong heap_bytes,
                                                         jlong stack_bytes) {
    if (!ensure_dispatcher(env)) return 0;
    auto *b = new Bridge();
    b->stack_ceiling = static_cast<size_t>(stack_bytes);
    // 不用 JS_NewRuntime()：要的是「分配被拒」这一票，而它只有分配器自己知道（见 BRIDGE_MALLOC_FUNCS）。
    // 第二个入参会落进 malloc_state.opaque，分配器据此找到本 Bridge。
    b->rt = JS_NewRuntime2(&BRIDGE_MALLOC_FUNCS, b);
    if (!b->rt) {
        delete b;
        return 0;
    }
    // 堆限不住一次 host call 拿回来的 900 KB 字符串（那是主进程的账），它管的是脚本自己的分配
    JS_SetMemoryLimit(b->rt, static_cast<size_t>(heap_bytes));
    // 栈限**不在这里**定死：内核判的是「距 stack_top 用了多少字节」，而用了多少栈是**哪条线程在跑**
    // 决定的，创建时看到的线程不作数。这里只把配置值存成天花板，每次执行前按本线程剩余栈现算
    // 再调 JS_SetMaxStackSize（见 stack_budget 与 nativeEval）。
    JS_SetCanBlock(b->rt, 0);  // Atomics.wait 一类阻塞原语直接失败：执行器线程上不能有待不起的等待
    JS_SetInterruptHandler(b->rt, on_interrupt, b);
    JS_SetRuntimeOpaque(b->rt, b);
    return reinterpret_cast<jlong>(reinterpret_cast<intptr_t>(b));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeReset(JNIEnv *env, jobject, jlong handle,
                                                        jstring prelude_source) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b || !b->rt) return JNI_FALSE;
    if (b->ctx) {
        JS_FreeContext(b->ctx);  // 任务边界：脚本留下的全局量、闭包、待办微任务一起没了
        b->ctx = nullptr;
    }
    b->ctx = JS_NewContext(b->rt);
    if (!b->ctx) return JNI_FALSE;
    if (!install_host_call(b->ctx)) return JNI_FALSE;

    Utf8Slice src;
    if (!jstring_to_utf8(env, prelude_source, src) || src.empty()) {
        LOGE("prelude source unreadable");
        return JNI_FALSE;
    }
    // 垫片随包冻结、永不来自书源，所以它的失败就是本仓的缺陷：写进日志，不让它静默
    JSValue r = JS_Eval(b->ctx, src.data(), src.len, "prelude.js", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JSValue ex = JS_GetException(b->ctx);
        const char *msg = JS_ToCString(b->ctx, ex);
        LOGE("prelude eval failed: %s", msg ? msg : "(unreadable)");
        if (msg) JS_FreeCString(b->ctx, msg);
        JS_FreeValue(b->ctx, ex);
        JS_FreeValue(b->ctx, r);
        return JNI_FALSE;
    }
    JS_FreeValue(b->ctx, r);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b) return;
    // 顺序必须是 context → runtime → Bridge：中断器的 opaque 指向 b，而它登记在 rt 上，
    // JS_FreeRuntime 内部还会走一遍 GC（可能触发轮询点），b 必须还活着。
    if (b->ctx) JS_FreeContext(b->ctx);
    if (b->rt) JS_FreeRuntime(b->rt);
    delete b;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ebook_source_sandbox_QuickJsNative_nativeEval(JNIEnv *env, jobject, jlong handle,
                                                       jstring bindings_json, jstring source,
                                                       jlong deadline_mono_ms) {
    auto *b = reinterpret_cast<Bridge *>(reinterpret_cast<intptr_t>(handle));
    if (!b || !b->ctx) return deliver(env, DESCRIPTOR_BRIDGE_BROKEN);
    DeadlineScope scope(b, deadline_mono_ms);

    Utf8Slice bindings_utf8;
    Utf8Slice source_utf8;
    if (!jstring_to_utf8(env, source, source_utf8) || source_utf8.empty() ||
        !jstring_to_utf8(env, bindings_json, bindings_utf8)) {
        return deliver(env, DESCRIPTOR_BRIDGE_BROKEN);
    }

    // 栈基线与栈预算每次执行前刷新：JS_SetMaxStackSize 判的是「距 stack_top 的字节数」，
    // 而 binder 线程每次可能换人（见 Task 7 开头第 4 条）。换人不只换基线——每条线程的**栈区大小**
    // 各不相同，所以预算要按这一条线程现算：取配置天花板与「本线程剩余栈减余量」中较小者。
    // 少了这一道，1 MB 的天花板压在 512 KB 栈的线程上，深递归就是 SIGSEGV 而不是一句 stack overflow。
    JS_UpdateStackTop(b->rt);
    JS_SetMaxStackSize(b->rt, stack_budget(b->stack_ceiling));

    // 绑定必须是**值**，不是拼进脚本文本的字符串：一段含引号与反斜杠的 HTML 会把整次求值变成语法错误
    ValueGuard global(b->ctx, JS_GetGlobalObject(b->ctx));
    JSValue bindings = JS_ParseJSON(b->ctx, bindings_utf8.data(), bindings_utf8.len, "<bindings>");
    if (JS_IsException(bindings)) {
        JS_FreeValue(b->ctx, bindings);
        JS_FreeValue(b->ctx, JS_GetException(b->ctx));
        // 空绑定 = 所有绑定都是 undefined，脚本会自己报出缺哪一个，比回一句「帧读不出来」有用
        bindings = JS_NewObject(b->ctx);
    }
    // bindings 的所有权在这里交出去（走 put 而不是裸 SetPropertyStr：内核 OOM 时 JS_NewObject
    // 也会回一个 JS_EXCEPTION，那个标签值不该作为属性挂在全局对象上）。
    // put 的两条路都不需要在此处补 JS_FreeValue：成功归全局属性，失败内核已 free。
    put(b->ctx, global.get(), "__bindings", bindings);

    ValueGuard value(b->ctx,
                     JS_Eval(b->ctx, source_utf8.data(), source_utf8.len, "rule.js", JS_EVAL_TYPE_GLOBAL));
    std::string out;
    if (JS_IsException(value.get())) {
        out = take_exception_descriptor(b->ctx, b);
    } else if (drain_jobs(b->ctx, b, out)) {
        // 语料里多数规则写成 `result = '正文' + xxx`，完成值是 undefined：改取全局 result
        if (JS_IsUndefined(value.get())) value.reset(JS_GetPropertyStr(b->ctx, global.get(), "result"));
        if (JS_IsException(value.get())) {
            // 读全局 result 这一步自己就抛了（脚本把 result 定义成抛异常的 getter）。
            // 不判的话 JS_EXCEPTION 会被当作完成值送进 stringify，产出的是兜底描述符，
            // 真话「你的 result getter 抛了」就丢了。
            out = take_exception_descriptor(b->ctx, b);
        } else {
            out = is_thenable(b->ctx, value.get())
                ? unsupported_descriptor(b->ctx, "脚本返回了 Promise，沙箱不等待异步完成")
                : ok_descriptor(b->ctx, value.get());
        }
    }
    return deliver(env, out);
}

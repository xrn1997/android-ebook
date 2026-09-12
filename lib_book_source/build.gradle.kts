import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
    alias(libs.plugins.xrn1997.android.native)
    // 沙箱控制面的帧（sandbox/JsProtocol）是本模块第一批 @Serializable 声明，需要 kotlinx-serialization
    // 编译插件生成 serializer()——只 implementation 运行时库的话注解不报错、`.serializer()` 却找不到符号。
    // 与 lib_ebook_api / lib_book_common 同一 alias，不写版本号。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ebook.source"
    // 约定插件给的是 release 最小集（只 arm64-v8a）；模拟器调试在这里显式放宽，
    // 放宽点写在模块里而不是插件里，是为了让「谁多带了一份内核」当场可见
    buildTypes.getByName("debug").ndk.abiFilters += "x86_64"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    // 解析器消费 api 层契约（BookSourceRule/实体模型）与 db 实体（BookParser 签名）
    api(project(":lib_ebook_api"))
    api(project(":lib_ebook_db"))
    // jsoup 只是解析实现的内部依赖（`Jsoup.parse` 的结果进局部变量，本模块没有任何公开签名暴露
    // org.jsoup 类型），故 implementation：不让它顺着编译期传递给下游，下游要 jsoup 该自己声明。
    // 原先的 juniversalchardet 已删——本模块（含测试）找不到 org.mozilla.universalchardet 的引用，
    // 编码探测住在 lib_book_common 那侧，它自己已声明。
    implementation(libs.jsoup)
    implementation(libs.common)
    // 规则装载（script/ScriptRuleSet）要把原始 JSON 读成 JsonObject 再取字段，
    // 沙箱控制面（sandbox/JsProtocol）要把帧解成 JsonElement 再按标量取值——这两处是本模块的 JSON 依赖。
    // 只 implementation、不 api：解析器对下游的契约（BookParser 签名）不带 JSON 形态，
    // 例外是沙箱协议类型（JsOutcome.data / HostReply.data）公开签名上是 JsonElement——
    // implementation 不传递给依赖方，消费侧（lib_book_common）自己声明了这个别名，故仍编得过、也不被我们绑版本。
    // 必须自己声明而不是从上游传递拿到：lib_ebook_api 侧同为 implementation（implementation 不传递给
    // 依赖方的编译类路径），版本仍走版本目录别名，不写版本号。
    implementation(libs.kotlinx.serialization.json)
    // OkHttp 由 lib_ebook_api 的 api(libs.okhttp.logging) 传递提供，本模块直接使用 okhttp3.OkHttpClient，不重复声明别名

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // 本模块第一批 instrumented 用例（sandbox/QuickJsBridgeTest）：ext.junit 提供 AndroidJUnit4，
    // core 提供 ApplicationProvider（Task 8 的 SandboxConnectionTest 要它拿 Context 去 bindService）——
    // 一次补齐，免得下个任务再回来改同一个文件。espresso 不声明：桥接层用例零 UI，
    // 只为拿一个 AndroidJUnitRunner 而拖进整条 espresso 依赖链不值。
    // runner 必须自己写：约定插件 AndroidLibraryConventionPlugin 把 testInstrumentationRunner 设成
    // `androidx.test.runner.AndroidJUnitRunner`，那个类住在 androidx.test:runner 里，而其他模块
    // （如 lib_ebook_db）的 debugAndroidTestRuntimeClasspath 上它是 espresso-core 传递来的
    // （`+--- androidx.test.espresso:espresso-core:3.7.0` → `|    +--- androidx.test:runner:1.7.0`）。
    // 不声明的话 APK 照编，装机跑到 instrumented 那一步才「Class not found」——只补 espresso 那条链
    // 太贵，直接声明这个 artifact。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

// —— 开发机沙箱库（桌面 JVM 跑真 QuickJS 的前置）——
// 只在显式请求时构建：DesktopJsHostTest 在 libebook_js 缺席时按「跳过」处置而不是假绿，
// 所以不把它挂进 test 依赖链——没装 MinGW 的机器跑 test 不应被拖去装工具链。
// 前置见 scripts/build-desktop-js.sh 头注释：Windows 必须是 MinGW-w64，MSVC/clang-cl 编不了上游内核。
val desktopJsDir = layout.buildDirectory.dir("desktop-js")

// Windows 上裸调 `bash` 会命中 System32 的 WSL bash，编出来的是 Linux .so，Windows JVM 加载不了——
// 必须显式用 Git Bash。非默认安装位用 GIT_BASH 环境变量指。
fun desktopBash(): String {
    if (!org.gradle.internal.os.OperatingSystem.current().isWindows) return "bash"
    val candidates = listOfNotNull(
        System.getenv("GIT_BASH")?.let { "$it\\bin\\bash.exe" },
        System.getenv("GIT_BASH"),
        "C:\\Program Files\\Git\\bin\\bash.exe",
        "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
    )
    return candidates.firstOrNull { it.isNotBlank() && File(it).isFile }
        ?: throw GradleException(
            "找不到 Git Bash（Windows 上裸 bash 是 WSL 的，编不出 .dll）。" +
                "装 Git for Windows 或设 GIT_BASH 指向其安装根目录",
        )
}

tasks.register<Exec>("buildDesktopJsLib") {
    group = "verification"
    description = "构建开发机沙箱库（MinGW-w64 / Linux gcc → ebook_js 动态库，供桌面真内核用例加载）"
    workingDir = rootDir
    commandLine(desktopBash(), "scripts/build-desktop-js.sh")
    outputs.file(desktopJsDir.map { it.file("ebook_js.dll") })
    outputs.file(desktopJsDir.map { it.file("libebook_js.so") })
}
tasks.withType<Test>().configureEach {
    // QuickJsNative 用 System.loadLibrary("ebook_js")；目录里没有库时加载失败 →
    // QuickJsNative.loaded=false → DesktopJsHostTest 按 assume 跳过，其余用例不受影响
    systemProperty("java.library.path", desktopJsDir.get().asFile.absolutePath)
    // 语料录制是显式动作：-Pcorpus.record / -Pcorpus.batch 一族透传给测试 JVM
    listOf("corpus.record", "corpus.keyword", "corpus.batch", "corpus.batchOffset", "corpus.batchLimit",
        "corpus.keywords", "corpus.conn").forEach { name ->
        (project.findProperty(name) as String?)?.let { systemProperty(name, it) }
    }
}

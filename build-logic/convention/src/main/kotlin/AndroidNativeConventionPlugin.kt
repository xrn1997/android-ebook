import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * 含 NDK/CMake 原生代码的模块共用的构建约定（ADR-0028 决策 8）。
 *
 * 为什么单独成插件而不是在模块里写一遍：ABI 口径是**安全与产品语义的一部分**而不只是构建细节——
 * release 只出 arm64-v8a（脚本书源只在真机上跑，多余 ABI 等于多份可被研究的内核副本）。
 * 这类策略必须一处生效、一处审查。
 *
 * **debug 的额外 ABI 刻意不在这里放宽**：模拟器调试要的 x86_64 由各模块在自己的 `android {}`
 * 里显式加（见 `lib_book_source/build.gradle.kts` 的 `buildTypes["debug"]`），这样「哪个模块
 * 多带了一份内核」在 diff 里当场可见；放进插件等于把它藏进一处不会随模块新增而被复查的地方。
 *
 * ndkVersion 钉死而不是用 AGP 默认：默认值随 AGP 小版本漂移，会让「同一份 C++ 在不同人机器上
 * 编出不同 .so」变成不可复现的问题。升级 NDK 是一次显式改动（需重跑 Task 7 的设备用例）。
 * 钉的是**值**，落点在 `gradle/libs.versions.toml`（`androidNdk` / `cmake`）——仓规要求工具版本
 * 只经版本目录管理；ABI 集合是策略不是版本，故留在这里而不是目录里。
 */
class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            // 不用 `findByType(...)?.apply {}`：Android 插件缺席时那是静默空转——插件照应用成功，
            // 而 NDK 版本、ABI 与 CMake 路径一条都没生效，症状要等到打出全 ABI 或用了错误 NDK
            // 才浮现。这里硬失败，让应用顺序写错在配置阶段就报出来。
            val android = extensions.findByType(CommonExtension::class.java)
                ?: error(
                    "xrn1997.android.native 必须在 Android 插件（com.android.library / com.android.application）" +
                        "之后应用：工程 ${path} 上找不到 CommonExtension",
                )
            android.apply {
                ndkVersion = libs.findVersion("androidNdk").orElseThrow().requiredVersion
                defaultConfig.ndk.abiFilters += RELEASE_ABIS
                externalNativeBuild.cmake {
                    // AGP 9 的 Cmake.path 是 File?（旧 DSL 是 String），用 file(...) 相对本模块解析
                    path = file("src/main/cpp/CMakeLists.txt")
                    version = libs.findVersion("cmake").orElseThrow().requiredVersion
                }
            }
        }
    }

    private companion object {
        val RELEASE_ABIS = setOf("arm64-v8a")
    }
}

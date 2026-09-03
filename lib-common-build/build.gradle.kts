// 根项目：镜像 android-ebook 根 build.gradle.kts 的插件 apply false 预声明，
// 把 AGP/Kotlin/KSP 等插件类加载进本构建的插件 classpath scope
// （lib_common 只应用约定插件，约定插件 compileOnly 依赖这些类；
//   无此声明则 NoClassDefFound / Kotlin 扩展冲突，与主构建行为不一致）
// 注：AGP 9 内置 Kotlin 后，Android 模块不再应用 org.jetbrains.kotlin.android，
//     KGP 类（kotlin { } 扩展）仍由 org.jetbrains.kotlin.jvm 预声明加载
// 版本对齐：AGP/Kotlin/KSP/Hilt 一律取自 android-practice 的版本目录（settings 里 from(files(...))
// 已把它加载为 libs，[plugins] 段含以下全部条目），避免此处与对方各存一份字面版本——
// 漂移时 Gradle 报 "plugin is already on the classpath with a different version"，难归因。
// 唯一例外 TheRouter：android-practice 目录里没有它的 plugin 条目，而本处版本必须与
// android-ebook 侧一致（跨仓耦合点，不该绑到 android-practice 的源头），故保留字面值，
// 升级时需与本仓 gradle/libs.versions.toml 的 therouter 同步。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    id("cn.therouter") version "1.4.0-rc1" apply false
}

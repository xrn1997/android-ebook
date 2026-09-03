// 根项目：镜像 android-ebook 根 build.gradle.kts 的插件 apply false 预声明，
// 把 AGP/Kotlin/KSP 等插件类加载进本构建的插件 classpath scope
// （lib_common 只应用约定插件，约定插件 compileOnly 依赖这些类；
//   无此声明则 NoClassDefFound / Kotlin 扩展冲突，与主构建行为不一致）
// 注：AGP 9 内置 Kotlin 后，Android 模块不再应用 org.jetbrains.kotlin.android，
//     KGP 类（kotlin { } 扩展）仍由 org.jetbrains.kotlin.jvm 预声明加载
// 版本对齐：lib_common 的插件版本来自 android-practice 版本目录（settings 里 from(files(...)) 引用），
// 这里的 apply false 预声明必须与其保持一致，否则 Gradle 报
// "plugin is already on the classpath with a different version"。
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.android.test") version "9.2.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("cn.therouter") version "1.4.0-rc1" apply false
}

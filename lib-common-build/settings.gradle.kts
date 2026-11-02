// 迷你独立构建：只承载 android-foundation 的 lib_common 模块
// 替代 includeBuild(android-foundation 全仓) 全量引入
// 说明：lib_common 无本地 project 依赖（全部第三方库），可独立构建；
//       约定插件（xrn1997.*）由 android-ebook 的 build-logic 提供：
//       compose 插件 ID 已与 android-foundation 统一为 xrn1997.android.compose（无别名），
//       但注意 ID 相同≠实现相同——本复合构建里 lib_common 用的是 android-ebook 版实现
//       （按 isModule 自套基础插件、不注入 compose ui-test 依赖），差异见 docs/adr/0020。

pluginManagement {
    includeBuild("../build-logic")
    repositories {
        // 阿里云 google 镜像：AGP / AndroidX 制品默认走 dl.google.com，国内干净环境（无 Gradle 缓存）易解析失败
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://www.jitpack.io")
        maven("https://mirrors.cloud.tencent.com/maven")
        maven("https://maven.aliyun.com/repository/apache-snapshots")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // 阿里云 google 镜像：AGP / AndroidX 制品默认走 dl.google.com，国内干净环境（无 Gradle 缓存）易解析失败
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://www.jitpack.io")
        maven("https://mirrors.cloud.tencent.com/maven")
        maven("https://maven.aliyun.com/repository/apache-snapshots")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
    // lib_common 的依赖版本来自 android-foundation 的版本目录（自本目录两级上溯 ../../android-foundation 定位源码，不绑定盘符）
    // Gradle 9 不支持多 toml 合并，缺失条目用 VersionCatalogBuilder 程序化补充
    // （kotlin-metadata：android-ebook 的 HiltConventionPlugin 需要，android-foundation catalog 缺失）
    // （android-desugarJdkLibs：android-ebook 的 KotlinAndroid.kt 约定插件 coreLibraryDesugaring 需要，
    //    android-foundation catalog 缺失，缺失会导致 Provider.get() 抛 "No value present"）
    // （android-test / kotlin-jvm：本构建根 plugins 块按 android-ebook 根预声明，
    //    android-foundation catalog 未收录这两个 plugin 条目）
    // （androidx-tracing-ktx：android-ebook 的 AndroidLibraryConventionPlugin 无条件注入，
    //    android-foundation catalog 未收录；版本与本仓 gradle/libs.versions.toml 的 androidxTracing 同步）
    versionCatalogs {
        create("libs") {
            from(files("../../android-foundation/gradle/libs.versions.toml"))
            library("kotlin-metadata", "org.jetbrains.kotlin", "kotlin-metadata-jvm").versionRef("kotlin")
            version("androidDesugarJdkLibs", "2.1.5")
            library("android-desugarJdkLibs", "com.android.tools", "desugar_jdk_libs").versionRef("androidDesugarJdkLibs")
            version("androidxTracing", "1.3.0")
            library("androidx-tracing-ktx", "androidx.tracing", "tracing-ktx").versionRef("androidxTracing")
            plugin("android-test", "com.android.test").versionRef("androidGradlePlugin")
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
        }
    }
}

rootProject.name = "lib-common-build"

include(":lib_common")
project(":lib_common").projectDir = File("../../android-foundation/lib_common")

// 独立 build 目录：避免与 android-foundation 自身构建共享 lib_common/build（Windows 文件锁冲突）
gradle.beforeProject {
    if (name == "lib_common") {
        layout.buildDirectory = File(rootDir, "build/lib_common")
    }
}

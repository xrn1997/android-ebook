// Gradle 9 的 updateDaemonJvm/toolchain auto-provisioning 需要 foojay resolver +
// toolchainManagement 里的下载仓库声明，否则 Android Studio sync 报
// "Toolchain download repositories have not been configured"
pluginManagement {
    includeBuild("build-logic")
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

// org.gradle.toolchains.foojay-resolver-convention 插件自动注册 repository("foojay")，
// 无需手写 toolchainManagement 块（Gradle 9 下载 JDK toolchain 所需，否则 sync 失败）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
}


// io.github.xrn1997:common 0.3.0 已发布到 Maven Central，但当前处于 common 继续迭代期，
// 本次改动依赖尚未随 0.3.0 发布的新 API（共享 Call.Factory 脱敏日志、ExplodeEffect、DisplayUtil 新签名等），
// 临时启用 includeBuild 走本地源码联调（lib_common 改动即时生效）。
// common 新版本发布并升级 libs.versions.toml 后，注释掉下方 includeBuild 改回中央坐标解析：
 includeBuild("lib-common-build") {
     dependencySubstitution {
         substitute(module("io.github.xrn1997:common"))
             .using(project(":lib_common"))
     }
 }

rootProject.name = "android-ebook"
include(":module_app")
include(":lib_book_common")
include(":module_main")
include(":module_book")
include(":module_find")
include(":module_me")
include(":lib_ebook_api")
include(":module_login")
include(":lib_ebook_db")

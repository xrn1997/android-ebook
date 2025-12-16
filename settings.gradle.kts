pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        maven(" https://maven.aliyun.com/repository/central")
        maven ("https://maven.aliyun.com/repository/public")
        maven ("https://maven.aliyun.com/repository/gradle-plugin")
        maven ("https://www.jitpack.io")
        maven ("https://mirrors.cloud.tencent.com/maven")
        maven ("https://maven.aliyun.com/repository/apache-snapshots")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        maven (" https://maven.aliyun.com/repository/central")
        maven ("https://maven.aliyun.com/repository/public")
        maven ("https://maven.aliyun.com/repository/gradle-plugin")
        maven ("https://www.jitpack.io")
        maven ("https://mirrors.cloud.tencent.com/maven")
        maven ("https://maven.aliyun.com/repository/apache-snapshots")
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
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

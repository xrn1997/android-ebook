import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
    alias(libs.plugins.xrn1997.hilt)
    alias(libs.plugins.xrn1997.android.room)
    id("kotlin-parcelize")
}
android {
    namespace = "com.ebook.db"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    api(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    // BundledSQLiteDriver 的直接依赖：lib_ebook_db 直接使用 androidx.sqlite.driver.bundled，
    // 显式声明不依赖传递可用性（room3-runtime 的传递依赖可能随上游变更而失效）
    implementation(libs.sqlite.bundled)

    // 生产同款 bundled 引擎的 JVM 变体：让 JVM 单测（BookSourceMigration6To7Test）能在**真引擎**上
    // 驱动手写迁移的 SQL，而不必仿真 DROP COLUMN。上面那条 Android 变体只带 Android ABI 的原生库
    // （AAR 的 jni/ 下是 .so），JVM 加载不到；本坐标自带 natives/<os>_<arch>/ 下的桌面原生库。
    // 这条依赖是**承重**的，不是锦上添花：单测的 classpath 会同时带上 Android 变体，删掉本行后
    // 先命中的是 Android 那份类，报 UnsatisfiedLinkError: no sqliteJni in java.library.path
    // （实测如此）；两变体类名相同，先命中谁由 classpath 顺序决定，不能指望 Android 那份让路
    testImplementation(libs.sqlite.bundled.jvm)

    testImplementation(libs.junit)
    // Robolectric：SearchHistoryDao 等 Room DAO 的 JVM 内存库回归测试
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

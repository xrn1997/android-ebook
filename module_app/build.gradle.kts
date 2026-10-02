import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.application)
    alias(libs.plugins.ksp)
    id("therouter")
    alias(libs.plugins.xrn1997.hilt)
}

/**
 * 应用版本名——**发版时唯一要改的一处**。定档判据（用户视角三问）见 AGENTS.md「语义版本映射」，
 * 规范依据与各段边界见 `docs/versioning-practice.md`。
 */
val APP_VERSION_NAME = "1.4.0"

/**
 * 由 [APP_VERSION_NAME] 反解 `versionCode`：`MAJOR*1_000_000 + MINOR*1_000 + PATCH`（各段上限 999）。
 *
 * 为什么不让两处各写一遍：`versionCode` 必须严格递增，改了版本名却忘改 code（或反之）会造出
 * 「装了旧版就再也装不上新版」的静默故障，构建期没有任何提示。算式、为什么是三位进制而不是两位
 * （两位进制下 `1.100.0` 与 `2.0.0` 会算出同一个 code）见 `docs/versioning-practice.md` §10.3。
 *
 * 预发布后缀（`1.4.0-rc.1`）**不参与计算**：rc 与同号正式版共用同一个 code。本仓发行物走 GitHub
 * Release 分发，同 code 覆盖安装是允许的；若哪天需要区分，得另占一位。
 */
fun versionCodeOf(versionName: String): Int {
    val segments = versionName.substringBefore('-').split('.')
    require(segments.size == 3) {
        "versionName 必须是三段式 MAJOR.MINOR.PATCH，当前为「$versionName」"
    }
    val numbers = segments.map { segment ->
        segment.toIntOrNull() ?: error("versionName 的每一段都必须是数字，当前为「$versionName」")
    }
    val (major, minor, patch) = numbers
    require(major in 0..2100) { "MAJOR 超出 0..2100：$major" }
    require(minor in 0..999) { "MINOR 超出 0..999：$minor" }
    require(patch in 0..999) { "PATCH 超出 0..999：$patch" }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.ebook"
    defaultConfig {
        applicationId = "com.ebook"
        versionName = APP_VERSION_NAME
        versionCode = versionCodeOf(APP_VERSION_NAME)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "network"
    productFlavors {
        create("real") {
            dimension = "network"
        }
        create("mock") {
            dimension = "network"
            applicationIdSuffix = ".mock"
        }
    }
    buildTypes {
        release {
            // 集成态 R8 执行者；崩溃排查依赖 build/outputs/mapping/ 的 mapping.txt（见 ADR-0024）
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
val isModule = project.findProperty("isModule").toString().toBoolean()
dependencies {
    implementation(project(":lib_book_common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    if (!isModule) {
        implementation(project(":module_main"))
        implementation(project(":module_find"))
        implementation(project(":module_me"))
        implementation(project(":module_book"))
        implementation(project(":module_login"))
    }


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

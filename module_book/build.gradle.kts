import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.xrn1997.android.component)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
}
android {
    namespace = "com.ebook.book"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        named("debug") {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
        named("release") {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        // ViewBinding 已随 Compose 全量迁移完成而移除（见 ADR-0001），无需保留配置项
    }
    testOptions {
        unitTests {
            // Robolectric 要读到合并后的资源（阅读器翻页测试里会取 R.string.*）
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
    implementation(fileTree("libs") { include("*.jar") })
    implementation(project(":lib_book_common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    ksp(libs.router.apt)
    implementation(libs.router)

    // Dagger
    ksp(libs.dagger.compiler)

    // Compose
    implementation(libs.coil.kt.compose)
    implementation(libs.androidx.compose.material.iconsExtended)
    // Compose 页面 hiltViewModel()（替代 Fragment 的 viewModels() 注入路径）
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    // Robolectric：ReaderPagerController 窗口状态机的 JVM 回归测试（只为提供 Context 与资源，
    // 不涉及 View/Compose 渲染）；与 lib_ebook_db 的 DAO 测试同一口径
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

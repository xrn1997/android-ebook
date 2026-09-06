import com.android.build.api.dsl.LibraryDefaultConfig
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
        // 集成态（library）：consumer 规则随 AAR 传播给 module_app 的 R8。
        // AGP 9 双态下 defaultConfig 接收器按模式是具体类型，须经 is
        // LibraryDefaultConfig 智能转换两种模式才都编译通过（同 module_me
        // versionCode 的 ApplicationDefaultConfig 先例）；
        // 独立态（application）无 consumer 概念，此声明不生效。
        if (this is LibraryDefaultConfig) {
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    buildTypes {
        release {
            // 集成态本标志对 library 无操作（AGP 只对 application 执行 R8）；
            // 独立态打 release 包时 R8 在本模块执行，模块级尽早暴露规则缺口
            //（见 ADR-0024）。
            isMinifyEnabled = true
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
    // 基线仪器测试（ImportBaselineTest）用的 Hilt 测试脚手架：@HiltAndroidTest 需要
    // hilt-android-testing 提供 HiltTestApplication/HiltAndroidRule，且组件树要在
    // androidTest 这次编译里由 KSP 生成。xrn1997.hilt 约定插件只给 main 挂了
    // ksp/implementation，androidTest 的 kspAndroidTest 必须自行声明，
    // 否则注解不被处理、注入点全部为空。
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}

import com.android.build.api.dsl.LibraryDefaultConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.component)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
}
val isModule = project.findProperty("isModule").toString().toBoolean()
android {
    namespace = "com.ebook.find"
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
            // 独立态（isModule=true，application）：R8 在本模块执行，模块级尽早暴露规则缺口。
            // 集成态（isModule=false，library）：本标志必须为 false，否则 AGP 会对 library
            // 执行 R8 剥离类，导致 module_app 的 R8 找不到依赖（见 ADR-0024 修订）。
            isMinifyEnabled = isModule
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
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
    implementation(libs.coil.kt.compose)
    ksp(libs.router.apt)
    implementation(libs.router)
    //Dagger
    ksp(libs.dagger.compiler)
    // Compose 页面 hiltViewModel()（替代 Fragment 的 viewModels() 注入路径）
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
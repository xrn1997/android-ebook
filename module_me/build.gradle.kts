import com.android.build.api.dsl.ApplicationDefaultConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.component)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
}
android {
    namespace = "com.ebook.me"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 版本号仅对独立运行（isModule=true，application）有意义，供设置页「版本」展示与检查更新占位；
        // library 集成模式版本由宿主 module_app 提供。AGP 9 的 LibraryDefaultConfig 已移除
        // versionCode/versionName，须经 is ApplicationDefaultConfig 智能转换才能两种模式都编译通过。
        if (this is ApplicationDefaultConfig) {
            versionCode = 1
            versionName = "1.0.0"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
        release {
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
    implementation(project(":lib_book_common"))
    // 直接使用的依赖显式声明（此前仅靠 lib_book_common 的 api() 传递暴露，
    // lib_book_common 一旦改 implementation 就会静默断裂）
    implementation(project(":lib_ebook_api"))
    implementation(libs.common)
    ksp(libs.dagger.compiler)
    implementation(libs.router)
    ksp(libs.router.apt)
    ksp(libs.glide.compiler)
    implementation(libs.coil.kt.compose)
    implementation(libs.androidx.compose.material.iconsExtended)
    // 头像裁剪的 EXIF 旋转纠正（此前仅靠 Coil 传递提供，显式声明避免升级断链）
    implementation(libs.androidx.exifinterface)
    // Compose 页面 hiltViewModel()（替代 Fragment 的 @Inject 注入路径）
    implementation(libs.androidx.hilt.navigation.compose)
    //测试依赖
    // 不声明 androidTestImplementation：本模块无 src/androidTest 源码目录，
    // library 集成（isModule=false）下该变体被 disableUnnecessaryAndroidTests 禁用，
    // 声明会触发 AGP 警告 "androidTestImplementation dependencies are ignored..."
    testImplementation(libs.junit)
}
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
        consumerProguardFiles("proguard-rules.pro")
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

    testImplementation(libs.junit)
    // Robolectric：SearchHistoryDao 等 Room DAO 的 JVM 内存库回归测试
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

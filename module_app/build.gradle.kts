import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.application)
    alias(libs.plugins.ksp)
    id("therouter")
    alias(libs.plugins.xrn1997.hilt)
}
android {
    namespace = "com.ebook"
    defaultConfig {
        applicationId = "com.ebook"
        versionCode = 1
        versionName = "1.1.7alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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

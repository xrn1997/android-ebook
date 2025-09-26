import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
    id("kotlin-parcelize")
}

android {
    namespace = "com.ebook.api"
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
dependencies {
    api(libs.androidx.appcompat)
    api(libs.annotations)

    //network
    api(libs.retrofit.converter.scalars)
    api(libs.retrofit.adapter.rxjava3)
    api(libs.retrofit.converter.gson)
    api(libs.okhttp.logging)

    //json解析
    api(libs.gson)
    api(libs.fastjson2)
    //rx管理View的生命周期
    api(libs.trello.rxlifecycle) {
        exclude(group = "com.android.support")
    }
    api(libs.trello.rxlifecycle.components) {
        exclude(group = "com.android.support")
    }
    api(libs.trello.rxlifecycle.android) {
        exclude(group = "com.android.support")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.xrn1997.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    api(libs.retrofit.converter.scalars)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.ebook.common"
    buildTypes {
        debug {
            buildConfigField("boolean", "IS_DEBUG", "true")
        }
        release {
            buildConfigField("boolean", "IS_DEBUG", "false")
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
    // common 库（composite build 直接引用 android-practice 的 lib_common 项目）
    api(libs.common)
    api(project(":lib_ebook_api"))
    api(project(":lib_ebook_db"))
    api(libs.androidx.appcompat)
    api(libs.androidx.constraintlayout)
    api(libs.material)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.fragment.ktx)
    api(libs.androidx.lifecycle.livedata.ktx)
    api(libs.androidx.lifecycle.runtimeCompose)
    api(libs.androidx.lifecycle.viewModelCompose)
    // Compose BOM
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.runtime)
    debugApi(libs.androidx.compose.ui.tooling)
    // 共享书籍封面组件 BookCover（Coil AsyncImage + 占位 Painter）
    api(libs.coil.kt.compose)
    ksp(libs.router.apt)
    api(libs.router)
    //glide图片加载
    api(libs.glide.core) {
        exclude(group = "com.android.support")
    }
    ksp(libs.glide.compiler)

    api(libs.dagger)
    ksp(libs.dagger.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    //JSOUP
    api(libs.jsoup)
    //kotlinx-serialization
    implementation(libs.kotlinx.serialization.json)
    //高斯模糊类
    api(libs.glide.transformations)
    //AutoFitTextView
    api(libs.autofittextview)
    //SwitchButton
    api(libs.switchbutton.library)
    api(libs.victor.lib)
    api(libs.retrofit.converter.scalars)
    api(libs.juniversalchardet)
    // PermissionX
    api(libs.permissionx)

//    debugApi(libs.leakcanary.android)
}

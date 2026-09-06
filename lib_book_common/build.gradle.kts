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

    api(libs.dagger)
    ksp(libs.dagger.compiler)

    // TransactionModule 需要 Room 的 withWriteTransaction 扩展（lib_ebook_db 用 implementation 声明，不传递）
    implementation(libs.room.runtime)

    testImplementation(libs.junit)
    // Robolectric：AndroidUserSessionManager 需要真实 SharedPreferences 与 Application 上下文
    // （与 lib_ebook_db 的 SearchHistoryDaoTest 同一套跑法）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    //JSOUP
    api(libs.jsoup)
    //kotlinx-serialization
    implementation(libs.kotlinx.serialization.json)
    api(libs.retrofit.converter.scalars)
    api(libs.juniversalchardet)
    // PermissionX
    api(libs.permissionx)

//    debugApi(libs.leakcanary.android)
}

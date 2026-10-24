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
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
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
    testOptions {
        unitTests {
            // Robolectric 要读到合并后的资源（共享 UI 组件的渲染/语义回归测试取 R.string.*）
            isIncludeAndroidResources = true
        }
    }
    // 导入特性下沉后，module_book 的单测需要复用本模块的 Fake DAO 与书源假件；
    // 这些假件只存在于测试类路径，不进产物（见 src/testFixtures）。
    testFixtures {
        enable = true
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
    // ── api：调用方要直接编译的契约面 ───────────────────────────────────────────
    // common 库（composite build 直接引用 android-practice 的 lib_common 项目）
    api(libs.common)
    api(project(":lib_ebook_api"))
    api(project(":lib_ebook_db"))
    // 原生书源解析器一族已迁入 lib_book_source；本层公开面（SourceResolver.resolveSource /
    // SourceBinding.Found）直接暴露其类型，故用 api 而非 implementation，业务模块才能只依赖本模块就编译通过
    api(project(":lib_book_source"))
    // Activity/Compose 基类与页面 API：业务模块的 Compose Activity 直接继承/调用
    api(libs.androidx.activity.ktx)
    api(libs.androidx.activity.compose)
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
    // 路由与注入：Provider/Activity 的 @Route/@AndroidEntryPoint 直接在调用方编译
    ksp(libs.router.apt)
    api(libs.router)
    api(libs.dagger)
    ksp(libs.dagger.compiler)

    // ── implementation：只在本模块实现内使用 ────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // 共享书籍封面组件 BookCover（Coil AsyncImage + 占位 Painter）：
    // 业务模块经 BookCover/Avatar 使用，不直接 import Coil，故不再向调用方暴露编译面
    implementation(libs.coil.kt.compose)
    //JSOUP
    implementation(libs.jsoup)
    //kotlinx-serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.juniversalchardet)
    // TransactionModule 需要 Room 的 withWriteTransaction 扩展（lib_ebook_db 用 implementation 声明，不传递）
    implementation(libs.room.runtime)

    // appcompat/material/fragment/constraintlayout 由 libs.common 的 api 传递提供；
    // 本模块实现内不直接引用，不再重复声明。

    testImplementation(libs.junit)
    // Robolectric：AndroidUserSessionManager 需要真实 SharedPreferences 与 Application 上下文
    // （与 lib_ebook_db 的 SearchHistoryDaoTest 同一套跑法）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI 测试：本模块是全仓共享 UI 组件的唯一产地（CommonCard/CommonListItem/InfoChip/
    // EmptyState…），组件的契约（可省字段不渲染、动作槽可点）只能在组件自己的模块里锁住；
    // 此前本模块零 Compose 测试，改共享件只能靠消费方回归，故补齐这套基建（口径同 module_book）
    testImplementation(libs.bundles.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

//    debugApi(libs.leakcanary.android)
}

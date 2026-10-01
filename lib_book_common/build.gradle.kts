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
    // 原生书源解析器一族已迁入 lib_book_source；本层公开面（BookSourceManager.getParserFor）
    // 直接返回其类型，故用 api 而非 implementation，业务模块才能只依赖本模块就编译通过
    api(project(":lib_book_source"))
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
    // Compose UI 测试：本模块是全仓共享 UI 组件的唯一产地（CommonCard/CommonListItem/InfoChip/
    // EmptyState…），组件的契约（可省字段不渲染、动作槽可点）只能在组件自己的模块里锁住；
    // 此前本模块零 Compose 测试，改共享件只能靠消费方回归，故补齐这套基建（口径同 module_book）
    testImplementation(libs.bundles.androidx.compose.ui.test)
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

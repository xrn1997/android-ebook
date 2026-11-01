import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.xrn1997.android.library)
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// 本模块不内置服务器地址（ADR-0053）：ebook-server 基址由用户在「设置 → 服务器地址」
// 自行配置（ServerAddressStore 持久化），构建期不注入任何主机值——发版包里没有可提取的
// 默认地址，开发机也走同一形态（App 内手填一次，SP 记住），debug/release 零差异。

android {
    namespace = "com.ebook.api"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    // SP 测试假件（FakeSharedPreferences）经 testFixtures 给 lib_book_common / module_me
    // 的测试共用：三处都要制造「commit 写盘失败」，各留一份必然漂移
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
    api(libs.annotations)

    //network
    implementation(libs.retrofit.converter.scalars)
    api(libs.retrofit.kotlin.serialization)
    api(libs.okhttp.logging)

    //json解析
    implementation(libs.kotlinx.serialization.json)

    //HTML解析：Jsoup 只在本模块实现内使用（JsoupHelper 的返回值不是 org.jsoup 类型），
    // 不再向调用方暴露编译面
    implementation(libs.jsoup)
    implementation(libs.androidx.core.ktx)
    // common 库（composite build 直接引用 android-practice 的 lib_common 项目）
    // api 透出：RespDTO 等类型出现在本模块公开 API 签名中
    api(libs.common)
    testImplementation(libs.junit)
    // mock 数据源（CommentNetworkTest 等）的资产契约测试需要 runTest 驱动 suspend 方法
    testImplementation(libs.kotlinx.coroutines.test)
    // ServerAddressStore 需要真实 SharedPreferences 与 Application 上下文（Robolectric JVM）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

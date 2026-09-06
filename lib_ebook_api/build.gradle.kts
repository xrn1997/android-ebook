import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.xrn1997.android.library)
    id("kotlin-parcelize")
    alias(libs.plugins.ksp)
    alias(libs.plugins.xrn1997.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// 开发期服务端地址：从机器私有的 local.properties 读 ebook.server.host（不进版本库），
// 缺省 10.0.2.2（模拟器映射宿主机 localhost，适配「本机跑 ebook-server + 模拟器调试」形态）；
// 真机局域网联调时在本机 local.properties 写 ebook.server.host=<局域网 IP> 即可，无需改代码。
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val ebookServerHost = localProps.getProperty("ebook.server.host", "10.0.2.2")

android {
    namespace = "com.ebook.api"
    defaultConfig {
        buildConfigField("String", "EBOOK_SERVER_HOST", "\"$ebookServerHost\"")
        consumerProguardFiles("proguard-rules.pro")
    }
    buildFeatures {
        buildConfig = true
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
    api(libs.material)
    api(libs.annotations)

    //network
    api(libs.retrofit.converter.scalars)
    api(libs.retrofit.kotlin.serialization)
    api(libs.okhttp.logging)

    //json解析
    implementation(libs.kotlinx.serialization.json)

    //HTML解析
    api(libs.jsoup)
    implementation(libs.androidx.core.ktx)
    // common 库（composite build 直接引用 android-practice 的 lib_common 项目）
    // api 透出：RespDTO 等类型出现在本模块公开 API 签名中
    api(libs.common)
    testImplementation(libs.junit)
    // mock 数据源（CommentNetworkTest 等）的资产契约测试需要 runTest 驱动 suspend 方法
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

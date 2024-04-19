plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.xrn1997.common"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        dataBinding = true
        viewBinding = true
        compose = true
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    api("androidx.core:core-ktx:1.13.0")
    api(libs.appcompat)
    api(libs.material)
    //Activity和Fragment
    api(libs.fragment.ktx)
    api(libs.activity.ktx)

    //RecyclerView https://developer.android.google.cn/jetpack/androidx/releases/recyclerview?hl=zh_cn
    api(libs.androidx.recyclerview)
    //EventBus  https://github.com/greenrobot/EventBus
    api(libs.eventbus)
    //Rxjava https://github.com/ReactiveX/RxJava
    // 教程 https://www.jianshu.com/p/d9b504f5b3bd
    api("io.reactivex.rxjava3:rxkotlin:3.0.1")
    api(libs.rxandroid)
    api(libs.rxjava)
    //RxLifecycle  https://github.com/trello/RxLifecycle
    api(libs.rxlifecycle.kotlin)
    api("com.trello.rxlifecycle4:rxlifecycle-android:4.0.2")
    api("com.trello.rxlifecycle4:rxlifecycle-android-lifecycle:4.0.2")
    api(libs.rxlifecycle.android.lifecycle.kotlin)
    api(libs.rxlifecycle.components)
    api("com.trello.rxlifecycle4:rxlifecycle-components-preference:4.0.2")
    // ViewModel https://github.com/androidx/androidx/tree/androidx-main/lifecycle
    // 官网 https://developer.android.google.cn/jetpack/androidx/releases/lifecycle
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.livedata.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.lifecycle.viewmodel.savedstate)
    kapt(libs.androidx.lifecycle.common)
    //Android 工具类 https://github.com/Blankj/AndroidUtilCode
    api("com.blankj:utilcodex:1.31.1")
    //Glide https://muyangmin.github.io/glide-docs-cn/
    api("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    //刷新加载控件 https://github.com/scwang90/SmartRefreshLayout/tree/main
    api("io.github.scwang90:refresh-layout-kernel:2.1.0")      //核心必须依赖
    api("io.github.scwang90:refresh-header-classics:2.1.0")    //经典刷新头
    api("io.github.scwang90:refresh-header-radar:2.1.0")       //雷达刷新头
    api("io.github.scwang90:refresh-header-falsify:2.1.0")    //虚拟刷新头
    api("io.github.scwang90:refresh-header-material:2.1.0")    //谷歌刷新头
    api("io.github.scwang90:refresh-header-two-level:2.1.0")   //二级刷新头
    api("io.github.scwang90:refresh-footer-ball:2.1.0")        //球脉冲加载
    api("io.github.scwang90:refresh-footer-classics:2.1.0")    //经典加载
    //Gson https://github.com/google/gson/
    api(libs.gson)
    //Retrofit https://square.github.io/retrofit/
    api("com.squareup.retrofit2:adapter-rxjava3:2.11.0")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-gson:2.11.0")
    api("com.squareup.retrofit2:converter-scalars:2.11.0")
    //第三方日志打印框架 https://github.com/ihsanbal/LoggingInterceptor/
    api("com.github.ihsanbal:LoggingInterceptor:3.1.0") {
        exclude(group = "org.json", module = "json")
    }
    //Stetho https://github.com/facebook/stetho
    //教程 https://www.jianshu.com/p/6a407dd612ee
    api(libs.stetho)
    //监控okhttp的请求
    api(libs.stetho.okhttp3)
    //AutoFitTextView https://github.com/grantland/android-autofittextview
    api(libs.autofittextview)
    //删除动画（粒子效果） https://github.com/tyrantgit/ExplosionField
    //教程 http://t.csdn.cn/7atlJ
    api(libs.explosionfield)
    //PermissionX https://github.com/guolindev/PermissionX
    // 教程 https://blog.csdn.net/guolin_blog/category_10108528.html
    api(libs.permissionx)
    //Compose
    api("androidx.activity:activity-compose:1.8.2")
    api(libs.androidx.lifecycle.viewmodel.compose)
    api("androidx.compose.ui:ui:1.6.5")
    api("androidx.compose.ui:ui-graphics:1.6.5")
    api("androidx.compose.ui:ui-tooling-preview:1.6.5")
    api("androidx.compose.material3:material3:1.2.1")
    api("androidx.compose.foundation:foundation:1.6.5")
    api("androidx.compose.runtime:runtime-livedata:1.6.5")
    api(platform("androidx.compose:compose-bom:2024.04.00"))
    //Navigation
    api("androidx.navigation:navigation-compose:2.7.7")
    api("androidx.navigation:navigation-fragment-ktx:2.7.7")
    api("androidx.navigation:navigation-ui-ktx:2.7.7")
    api("androidx.navigation:navigation-dynamic-features-fragment:2.7.7")
    
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.00"))
}
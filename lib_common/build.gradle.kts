plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.kotlinKsp)
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

    api(libs.core.ktx)
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
    api(libs.rxkotlin)
    api(libs.rxandroid)
    api(libs.rxjava)
    //RxLifecycle  https://github.com/trello/RxLifecycle
    api(libs.rxlifecycle.kotlin)
    api(libs.rxlifecycle.rxlifecycle.android)
    api(libs.rxlifecycle.android.lifecycle)
    api(libs.rxlifecycle.android.lifecycle.kotlin)
    api(libs.rxlifecycle.components)
    api(libs.rxlifecycle.components.preference)
    // ViewModel https://github.com/androidx/androidx/tree/androidx-main/lifecycle
    // 官网 https://developer.android.google.cn/jetpack/androidx/releases/lifecycle
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.livedata.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.lifecycle.viewmodel.savedstate)
    kapt(libs.androidx.lifecycle.common)
    //Android 工具类 https://github.com/Blankj/AndroidUtilCode
    api(libs.utilcodex)
    //Glide https://muyangmin.github.io/glide-docs-cn/
    api(libs.glide)
    ksp(libs.compiler)
    //刷新加载控件 https://github.com/scwang90/SmartRefreshLayout/tree/main
    api(libs.refresh.layout.kernel)      //核心必须依赖
    api(libs.refresh.header.classics)    //经典刷新头
    api(libs.refresh.header.radar)       //雷达刷新头
    api(libs.refresh.header.falsify)    //虚拟刷新头
    api(libs.refresh.header.material)    //谷歌刷新头
    api(libs.refresh.header.two.level)   //二级刷新头
    api(libs.refresh.footer.ball)        //球脉冲加载
    api(libs.refresh.footer.classics)    //经典加载
    //Gson https://github.com/google/gson/
    api(libs.gson)
    //Retrofit https://square.github.io/retrofit/
    api(libs.adapter.rxjava3)
    api(libs.retrofit)
    api(libs.converter.gson)
    api(libs.converter.scalars)
    //第三方日志打印框架 https://github.com/ihsanbal/LoggingInterceptor/
    api(libs.ihsanbal.logging.intercepter) {
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
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.ui.tooling.preview)
    api(libs.androidx.material3)
    api(libs.androidx.foundation)
    api(libs.runtime.livedata)
    api(platform(libs.androidx.compose.bom))
    //Navigation
    api(libs.androidx.navi.compose)
    api(libs.androidx.navi.fragment.ktx)
    api(libs.androidx.navi.ui.ktx)
    api(libs.androidx.navi.dynamic.features.fragment)
    //Palette
    api(libs.androidx.palette)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
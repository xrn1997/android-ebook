# 本模块混淆规则（一份文件、双声明、双态生效）：
# - 集成态（isModule=false，本模块是 library）：经 consumerProguardFiles 随 AAR
#   传播进 module_app 的 R8；本模块的 proguardFiles 不生效（library 无 R8 任务）。
# - 独立态（isModule=true，本模块是 application）：buildTypes.release 的
#   proguardFiles 生效，R8 在本模块执行。
# 归属原则：只写本模块反射面需要的规则；第三方库自带的 consumer 规则不重复。
# TheRouter 规则依据：官方 README 要求（github.com/HuolalaTech/hll-wp-therouter-android）
# + 运行时 RouteMapKt 按类名字符串 Class.forName 的字节码证据。

# —— TheRouter（官方 README 全套）——
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}
-keepclasseswithmembers class * {
    @com.therouter.router.Autowired <fields>;
}

# —— ServiceProvider 实现类 ——
# 运行时按类名字符串反射创建（RouteMapKt forName），且不在 manifest、无 aapt
# keep 兜底。@Route 的 Activity 由 manifest aapt 规则兜底，无需显式 keep。
-keep @com.therouter.inject.ServiceProvider class * {
    *;
}

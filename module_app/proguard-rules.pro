# module_app 混淆规则（R8 唯一集成态执行者，release 已开启 isMinifyEnabled）。
# 规则来源与证据：
# - TheRouter 全套：官方 README 要求 + 运行时 RouteMapKt 按类名字符串
#   Class.forName 的字节码证据；@Route 的 Activity 由 manifest aapt 规则兜底，
#   无需显式 keep；ServiceProvider 实现类不在 manifest、无 aapt 兜底，必须显式 keep。
# - 行号属性：release 崩溃栈配合 build/outputs/mapping/<variant>/mapping.txt 还原；
#   renamesourcefileattribute 隐藏原始文件名（行号保留 + 不泄漏源文件名）。
# 功能模块的反射面规则在各模块自己的 proguard-rules.pro（经 consumer 规则传播）。
# 禁止无证据的 -keep/-dontwarn。

# —— 崩溃栈可读性 ——
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
-keep @com.therouter.inject.ServiceProvider class * {
    *;
}

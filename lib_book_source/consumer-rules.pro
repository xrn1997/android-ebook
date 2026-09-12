# lib_book_source：书源解析与脚本书源解释器。
# 反射面规则按需登记（ADR-0024：无证据不写 keep）；当前迁入的解析器一族无新增反射面。

# JNI 的反射面：js_bridge.cpp 用 FindClass + GetStaticMethodID 按名字与签名找 handle，
# 这种以字符串常量写死的引用 R8 看不见。不 keep 的后果不是崩溃而是执行器一起来就
# 「host 回调：主进程入口未就绪」，且只出现在开了混淆的 release 包上。
-keep class com.ebook.source.sandbox.HostDispatcher { *; }

# QuickJsNative 的四个 external fun 不写：AGP 自带规则已覆盖
# （gradle-9.2.1.jar 内 com/android/build/gradle/proguard-common.txt：
#   `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`），
# 这里再写一遍只会让后来人以为本仓的 native 面是自己照顾的。

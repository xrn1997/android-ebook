plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    // AGP 9 内置 Kotlin：Android 模块不再应用 org.jetbrains.kotlin.android，无需预声明
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.module.graph) apply true
    alias(libs.plugins.room) apply false
    alias(libs.plugins.therouter) apply false
}
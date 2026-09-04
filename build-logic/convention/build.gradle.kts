import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    alias(libs.plugins.android.lint)
}

group = "com.xrn1997.convention"

// Configure the build-convention plugins to target JDK 17
// This matches the JDK used to build the project, and is not related to what is running on device.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.android.tools.common)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.router.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.xrn1997.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        // 规范 ID 与 android-practice 侧一致（xrn1997.android.compose）：lib-common-build
        // 复合构建只 includeBuild 本仓 build-logic，却要满足 android-practice 各模块应用的
        // 同一 ID，故两边命名统一、不再留别名。注意统一的是 ID 而非实现：本仓版按 isModule
        // 自套基础插件，与 android-practice 版语义有差，差异与影响见 docs/adr/0020
        register("androidCompose") {
            id = libs.plugins.xrn1997.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.xrn1997.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.xrn1997.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
        register("androidRoom") {
            id = libs.plugins.xrn1997.android.room.get().pluginId
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("androidLint") {
            id =  libs.plugins.xrn1997.android.lint.get().pluginId
            implementationClass = "AndroidLintConventionPlugin"
        }
        register("androidComponent") {
            id = libs.plugins.xrn1997.android.component.get().pluginId
            implementationClass = "AndroidComponentConventionPlugin"
        }
    }
}

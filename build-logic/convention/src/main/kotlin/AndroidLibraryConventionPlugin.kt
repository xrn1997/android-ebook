/*
 * Copyright 2022 The Android Open Source Project
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.dsl.LibraryExtension
import com.xrn1997.convention.configureGradleManagedDevices
import com.xrn1997.convention.configureKotlinAndroid
import com.xrn1997.convention.configurePrintApksTask
import com.xrn1997.convention.disableUnnecessaryAndroidTests
import com.xrn1997.convention.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // AGP 9 内置 Kotlin：不再需要单独应用 org.jetbrains.kotlin.android（KGP），
            // Kotlin 支持由 AGP 提供，顶层 kotlin { } 扩展（KotlinAndroidProjectExtension）仍可用
            apply(plugin ="com.android.library")
            apply(plugin ="xrn1997.android.lint")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = 37
                // 单元测试中 android.util.Log 返回默认值（returnDefaultValues），
                // 与 lib_common（android-practice）一致：Logger 的纯 JVM 测试不 mock Log
                testOptions.unitTests.isReturnDefaultValues = true
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                testOptions.animationsDisabled = true
                configureGradleManagedDevices(this)
                // The resource prefix is derived from the module name,
                // so resources inside ":core:module1" must be prefixed with "core_module1_"
//                resourcePrefix = path.split("""\W""".toRegex()).drop(1).distinct().joinToString(separator = "_").lowercase() + "_"
            }
            extensions.configure<LibraryAndroidComponentsExtension> {
                configurePrintApksTask(this)
                disableUnnecessaryAndroidTests(target)
            }
            dependencies {
                // 仅当存在 androidTest 源码目录时才注入 androidTest 依赖：否则该变体会被
                // disableUnnecessaryAndroidTests 禁用，注入会触发 AGP 警告
                // "androidTestImplementation dependencies are ignored because androidTest is disabled"
                if (projectDir.resolve("src/androidTest").exists()) {
                    "androidTestImplementation"(libs.findLibrary("kotlin.test").get())
                }
                "testImplementation"(libs.findLibrary("kotlin.test").get())
                "testImplementation"(libs.findLibrary("junit").get())

                "implementation"(libs.findLibrary("androidx.tracing.ktx").get())
            }
        }
    }
}

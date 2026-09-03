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

import com.android.build.api.dsl.CommonExtension
import com.xrn1997.convention.configureAndroidCompose

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // 与 AndroidComponentConventionPlugin 保持一致：isModule=true 时模块作为
        // 独立 application 运行（如 module_main 同时应用两个约定插件，硬编码 library
        // 会导致同一项目同时应用 application 与 library 插件而构建失败）
        val isModule = target.findProperty("isModule")?.toString()?.toBoolean() ?: false
        with(target) {
            if (isModule) {
                apply(plugin = "xrn1997.android.application")
            } else {
                apply(plugin = "xrn1997.android.library")
            }
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")

            // ApplicationExtension 与 LibraryExtension 均继承 CommonExtension，
            // 统一按 CommonExtension 配置 compose，无需区分插件类型
            extensions.configure<CommonExtension> {
                configureAndroidCompose(this)
            }
        }
    }

}
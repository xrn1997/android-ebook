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

/**
 * Compose 约定插件：开启 compose 编译能力并装配 Compose BOM 等依赖。
 *
 * 命名：与 android-practice 侧同 ID（xrn1997.android.compose），供 `lib-common-build` 复合构建
 * 只用一套 build-logic 时两边都能解析（曾因 `xrn1997.android.library.compose` 与本 ID 并存而
 * 需要别名，现只留这一个）。compose 能力与模块类型无关，故 ID 不带 library./application. 前缀。
 *
 * **同 ID 不等于同实现**：android-practice 版不自套基础插件（要求先应用 library/application）、
 * 并额外注入 compose ui-test 依赖；本仓版按 `isModule` 自套基础插件。复合构建里的 lib_common
 * 用的是本仓实现。差异与待删方向（该 isModule 分支对 module_main 已冗余）见
 * `docs/adr/0020-compose-convention-plugin-id-unification.md` 与 `docs/test-coverage-todo.md`。
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // isModule=true 时模块作为独立 application 运行。module_main 同时应用本插件与
        // xrn1997.android.component，而 Gradle 按 ID 应用插件是幂等的，两处套同一基础插件不冲突；
        // 硬编码 library 则会在独立模式下与 application 冲突（此分支对 module_main 已属冗余，
        // 但它仍是「只挂 compose 插件」的模块能独立运行的唯一入口，删除前须先确认无此类调用方）
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

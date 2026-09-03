import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidComponentConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val isModule = target.findProperty("isModule")?.toString()?.toBoolean() ?: false
        with(target) {
                if (isModule) {
                    apply(plugin ="xrn1997.android.application")
                    apply(plugin ="therouter")
                } else {
                    apply(plugin ="xrn1997.android.library")
                }
            // AGP 9 新 DSL（newDsl=true）下 sourceSets 暴露在 CommonExtension 上
            extensions.configure<CommonExtension> {
                sourceSets.getByName("main") {
                    jniLibs.directories.add("jniLibs")
                    if (isModule) {
                        manifest.srcFile("src/main/module/AndroidManifest.xml")
                        // AGP 9 内置 Kotlin：Kotlin 源码目录必须加在 kotlin.directories，
                        // java.srcDirs 不再被 Kotlin 编译任务拾取（KSP 仍可见，会导致
                        // KSP 生成代码而 Kotlin 未编译的错位）
                        kotlin.directories.add("src/main/test")
                    } else {
                        manifest.srcFile("src/main/AndroidManifest.xml")
                    }
                }
            }
        }
    }
}
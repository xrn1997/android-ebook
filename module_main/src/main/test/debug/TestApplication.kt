package debug

import dagger.hilt.android.HiltAndroidApp

/**
 * module_main 独立运行时的 Application。
 *
 * 公共初始化、mock 绑定与宿主基座都在 `debug-host`；本模块没有跨模块路径要替换。
 */
@HiltAndroidApp
class TestApplication : BaseTestApplication()

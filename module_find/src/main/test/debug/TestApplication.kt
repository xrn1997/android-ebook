package debug

import com.ebook.common.event.KeyCode
import dagger.hilt.android.HiltAndroidApp

/**
 * module_find 独立运行时的 Application：把书籍详情路由换成本模块的桩页。
 *
 * 公共初始化、mock 绑定与宿主基座都在 `debug-host`；书源管理页已住在本模块，
 * 不再需要路径替换或占位页。
 */
@HiltAndroidApp
class TestApplication : BaseTestApplication() {
    override val pathReplacements: Map<String, String> = mapOf(
        KeyCode.Book.DETAIL_PATH to KeyCode.Find.TEST_DETAIL_PATH,
    )
}

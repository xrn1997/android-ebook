package com.ebook.common.analyze.source

import com.ebook.api.entity.PageRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ListPageUrl] 的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 锁住分页 URL 渲染的各种模板形态，尤其是「首页不带页码段」这条站点事实：
 * 内置书源笔趣阁的 `/xuanhuan`、`/so/关键词` 是首页，`/xuanhuan/1`、`/xuanhuan/` 一律 404。
 * 模板缺 {{page}} 时页码被整段丢弃、每页都请求首页（分类列表无限重复的根因），
 * 因此这里同时固化「有占位符就真的翻页」的行为，防止后续改动把翻页能力悄悄改回去。
 */
class ListPageUrlTest {

    private val defaultPage = PageRule()

    // ===== 路径段式分页（内置书源的实际形态）=====

    @Test
    fun `首页裁掉结尾的页码段`() {
        assertEquals("/xuanhuan", ListPageUrl.build("/xuanhuan/{{page}}", 1, defaultPage))
    }

    @Test
    fun `第二页起带上站点页码`() {
        assertEquals("/xuanhuan/2", ListPageUrl.build("/xuanhuan/{{page}}", 2, defaultPage))
        assertEquals("/xuanhuan/85", ListPageUrl.build("/xuanhuan/{{page}}", 85, defaultPage))
    }

    @Test
    fun `搜索地址同样在首页去掉页码段`() {
        // {{keyword}} 由调用方先替换，渲染器只管页码
        assertEquals("/so/大", ListPageUrl.build("/so/大/{{page}}", 1, defaultPage))
        assertEquals("/so/大/2", ListPageUrl.build("/so/大/{{page}}", 2, defaultPage))
    }

    @Test
    fun `页码段不在模板结尾时不裁段`() {
        // 只裁「结尾的页码段」：中段形态裁掉会留下 //，这类站点的第一页地址本就带 1
        assertEquals("/list/1/index.html", ListPageUrl.build("/list/{{page}}/index.html", 1, defaultPage))
        assertEquals("/paihang", ListPageUrl.build("/paihang", 3, defaultPage))
    }

    // ===== 查询参数式分页：首页本身就带 page=1，不能裁 =====

    @Test
    fun `查询参数式模板首页保留真实页码`() {
        assertEquals(
            "/list?page=1",
            ListPageUrl.build("/list?{{pageParam}}={{page}}", 1, defaultPage)
        )
        assertEquals(
            "/list?page=2",
            ListPageUrl.build("/list?{{pageParam}}={{page}}", 2, defaultPage)
        )
    }

    @Test
    fun `pageParam 占位符替换为配置的分页参数名`() {
        assertEquals(
            "/list?p=3",
            ListPageUrl.build("/list?{{pageParam}}={{page}}", 3, PageRule(param = "p"))
        )
    }

    // ===== 起始页与步长 =====

    @Test
    fun `按起始页与步长换算站点页码`() {
        val pageRule = PageRule(start = 10, step = 10)
        assertEquals("/list", ListPageUrl.build("/list/{{page}}", 1, pageRule))
        assertEquals("/list/20", ListPageUrl.build("/list/{{page}}", 2, pageRule))
        assertEquals(10, ListPageUrl.actualPage(1, pageRule))
        assertEquals(30, ListPageUrl.actualPage(3, pageRule))
    }

    @Test
    fun `start 非正数时不做换算且序号 1 就是首页`() {
        val pageRule = PageRule(start = 0)
        assertEquals(1, ListPageUrl.actualPage(1, pageRule))
        // 首页判定用「序号 1」而非 start=0，否则首页会渲染成 /x/1 而 404
        assertEquals("/x", ListPageUrl.build("/x/{{page}}", 1, pageRule))
        assertEquals("/x/2", ListPageUrl.build("/x/{{page}}", 2, pageRule))
    }
}

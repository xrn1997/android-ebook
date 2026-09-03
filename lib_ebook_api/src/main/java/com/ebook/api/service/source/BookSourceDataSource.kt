package com.ebook.api.service.source

import com.ebook.api.entity.BookSourceRule

/**
 * 书源数据源接口
 * 定义书源的通用操作
 */
interface BookSourceDataSource {
    /**
     * 获取页面内容
     * @param url 相对或绝对 URL
     * @param method 请求方法（GET/POST），为空时使用规则配置
     * @param body 请求体（POST 时使用），为空时使用规则配置
     * @return 页面 HTML 内容
     */
    suspend fun getPage(url: String, method: String = "", body: String = ""): String

    /**
     * 获取当前书源规则
     */
    fun getCurrentRule(): BookSourceRule
}

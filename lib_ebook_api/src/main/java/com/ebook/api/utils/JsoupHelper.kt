package com.ebook.api.utils

import com.ebook.api.entity.ReplaceRule
import org.jsoup.nodes.Element

/**
 * Jsoup 选择器工具类
 * 支持 CSS 选择器 + @属性 语法
 * 例如：img@src、a@href、.class@text
 */
object JsoupHelper {

    /**
     * 根据选择器提取文本
     * @param element 父元素
     * @param selector CSS 选择器（不含 @）
     * @return 提取的文本，失败返回空字符串
     */
    fun selectText(element: Element, selector: String): String {
        if (selector.isEmpty()) return ""
        return try {
            element.selectFirst(selector)?.text() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 根据选择器提取属性值
     * @param element 父元素
     * @param selector 格式为 "cssSelector@attrName"，如 "img@src"
     * @return 提取的属性值，失败返回空字符串
     */
    fun selectAttr(element: Element, selector: String): String {
        if (selector.isEmpty()) return ""
        return try {
            val parts = selector.split("@", limit = 2)
            if (parts.size == 2) {
                element.selectFirst(parts[0])?.attr(parts[1]) ?: ""
            } else {
                element.selectFirst(selector)?.text() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 根据选择器获取元素列表
     * @param element 父元素
     * @param selector CSS 选择器
     * @return 元素列表
     */
    fun selectElements(element: Element, selector: String): List<Element> {
        if (selector.isEmpty()) return emptyList()
        return try {
            element.select(selector)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 拼接相对 URL
     * @param base 基础 URL
     * @param relative 相对 URL
     * @return 完整 URL
     */
    fun parseUrl(base: String, relative: String): String {
        if (relative.isEmpty()) return ""
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        val baseUrl = base.trimEnd('/')
        return if (relative.startsWith("/")) {
            // 绝对路径，只需要域名
            val host = try {
                val uri = java.net.URI(base)
                "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            } catch (_: Exception) {
                baseUrl
            }
            "$host$relative"
        } else {
            "$baseUrl/$relative"
        }
    }

    /**
     * 替换文本中的匹配内容
     * @param text 原始文本
     * @param replaceRules 替换规则列表
     * @return 替换后的文本
     */
    fun applyReplaceRules(text: String, replaceRules: List<ReplaceRule>): String {
        var result = text
        for (rule in replaceRules) {
            if (!rule.enabled) continue
            try {
                result = result.replace(Regex(rule.pattern), rule.replacement)
            } catch (_: Exception) {
                // 正则无效时跳过
            }
        }
        return result
    }
}

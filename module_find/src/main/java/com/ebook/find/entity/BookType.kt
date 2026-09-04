package com.ebook.find.entity

/**
 * 书籍分类入口（书城页"书籍类型"胶囊的数据源）。
 *
 * 字段由当前书源规则的 `ruleFind.kinds` 映射而来（见
 * [com.ebook.find.repository.BookSourceRepository.getBookTypeList]），源条目
 * [com.ebook.api.entity.KindItem] 的字段非空带默认值，故本类不设可空；
 * 非空只保证"有值"不保证"有内容"（漏写字段得到空串），标题空白的条目由上游 getBookTypeList 过滤。
 *
 * @property bookType 分类名称（如"玄幻""都市"），显示在书城页的分类胶囊 `BookTypeChip` 内
 * @property url 分类列表页地址，传给 `ChoiceBookActivity` 加载该分类下的书籍
 */
data class BookType(
    val bookType: String = "",
    val url: String = ""
)

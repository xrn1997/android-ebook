package com.ebook.find.entity

/**
 * 书籍分类入口（书城页"书籍类型"胶囊的数据源）。
 *
 * 字段由当前书源规则的 `ruleFind.kinds` 映射而来，见 [BookSourceRepository.getBookTypeList]。
 *
 * @property bookType 分类名称（如"玄幻""都市"），显示在 [BookTypeChip] 胶囊内
 * @property url 分类列表页地址，传给 [ChoiceBookActivity] 加载该分类下的书籍
 */
data class BookType(
    @JvmField
    var bookType: String? = null,
    var url: String? = null
) {
    override fun toString(): String {
        return "BookType{" +
                "bookType='" + bookType + '\'' +
                ", url='" + url + '\'' +
                '}'
    }
}

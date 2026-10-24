package com.ebook.book.reader

/**
 * 阅读器面板枚举（原五个 PopupWindow 的显隐状态统一收口）。
 *
 * 由 ReadBookScreen（ReadBookActivity.kt）持有，底栏据其高亮当前打开的入口，
 * 故声明在 reader 包内供 chrome 层共用（不再放在 Activity 文件里，避免反向依赖）。
 */
internal enum class ReaderPanel { NONE, CHAPTER, LIGHT, FONT, SETTING, SOURCE_SWITCH }

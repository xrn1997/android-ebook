package com.ebook.db.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 下载章节列表
 */
@Parcelize
data class DownloadChapterList(
    @JvmField
    var data: List<DownloadChapter>? = null
) : Parcelable

package com.ebook.db.event

interface DBCode {
    interface BookContentView {
        companion object {
            const val DUR_PAGE_INDEX_BEGIN: Int = -1
            const val DUR_PAGE_INDEX_END: Int = -2
        }
    }
}

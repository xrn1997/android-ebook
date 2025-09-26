package com.ebook.common.event

interface RxBusTag {
    companion object {
        const val HAD_ADD_BOOK: String = "rxbus_add_book"

        const val HAD_REMOVE_BOOK: String = "rxbus_remove_book"

        const val UPDATE_BOOK_PROGRESS: String = "rxbus_update_book_progress"

        const val PAUSE_DOWNLOAD_LISTENER: String = "rxbus_pause_download_listener"

        const val PROGRESS_DOWNLOAD_LISTENER: String = "rxbus_progress_download_listener"

        const val FINISH_DOWNLOAD_LISTENER: String = "rxbus_finish_download_listener"

        const val PAUSE_DOWNLOAD: String = "rxbus_pause_download"

        const val START_DOWNLOAD: String = "rxbus_start_download"

        const val CANCEL_DOWNLOAD: String = "rxbus_cancel_download"

        const val ADD_DOWNLOAD_TASK: String = "rxbus_add_download_task"

        const val START_DOWNLOAD_SERVICE: String = "rxbus_start_download_service"

        const val MODIFY_PROFILE_PICTURE: String = "rxbus_modify_profie_picture"

        const val SET_PROFILE_PICTURE_AND_NICKNAME: String = "rxbus_set_profie_picture_and_nickname"
    }
}

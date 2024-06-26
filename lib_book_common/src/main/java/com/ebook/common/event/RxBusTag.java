package com.ebook.common.event;

public interface RxBusTag {

    String HAD_ADD_BOOK = "rxbus_add_book";

    String HAD_REMOVE_BOOK = "rxbus_remove_book";

    String UPDATE_BOOK_PROGRESS = "rxbus_update_book_progress";

    String PAUSE_DOWNLOAD_LISTENER = "rxbus_pause_download_listener";

    String PROGRESS_DOWNLOAD_LISTENER = "rxbus_progress_download_listener";

    String FINISH_DOWNLOAD_LISTENER = "rxbus_finish_download_listener";

    String PAUSE_DOWNLOAD = "rxbus_pause_download";

    String START_DOWNLOAD = "rxbus_start_download";

    String CANCEL_DOWNLOAD = "rxbus_cancel_download";

    String ADD_DOWNLOAD_TASK = "rxbus_add_download_task";

    String START_DOWNLOAD_SERVICE = "rxbus_start_download_service";

    String MODIFY_PROFILE_PICTURE = "rxbus_modify_profie_picture";

    String SET_PROFILE_PICTURE_AND_NICKNAME = "rxbus_set_profie_picture_and_nickname";

}

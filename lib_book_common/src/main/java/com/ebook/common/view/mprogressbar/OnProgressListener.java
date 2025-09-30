package com.ebook.common.view.mprogressbar;

public interface OnProgressListener {
    void moveStartProgress(float dur);

    void durProgressChange(float dur);

    void moveStopProgress(float dur);

    void setDurProgress(float dur);
}

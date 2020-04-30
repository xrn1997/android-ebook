package com.ebook.basebook.view.mprogressbar;

public interface OnProgressListener {
    public void moveStartProgress(float dur);

    public void durProgressChange(float dur);

    public void moveStopProgress(float dur);

    public void setDurProgress(float dur);
}

package com.ebook.basebook.view.ImmerseView;

public class MeasureHeightResult {
    private boolean success = false;
    private int height = -1;

    public MeasureHeightResult() {
    }

    public MeasureHeightResult(boolean success, int height) {
        this.success = success;
        this.height = height;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}

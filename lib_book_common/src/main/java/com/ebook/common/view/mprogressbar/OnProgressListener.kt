package com.ebook.common.view.mprogressbar

interface OnProgressListener {
    fun moveStartProgress(dur: Float)

    fun durProgressChange(dur: Float)

    fun moveStopProgress(dur: Float)

    fun setDurProgress(dur: Float)
}

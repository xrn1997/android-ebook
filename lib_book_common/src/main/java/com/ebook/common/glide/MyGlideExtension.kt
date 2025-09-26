package com.ebook.common.glide

import com.bumptech.glide.annotation.GlideExtension
import com.bumptech.glide.annotation.GlideOption
import com.bumptech.glide.request.BaseRequestOptions


@GlideExtension
object MyGlideExtension {
    // Size of mini thumb in pixels.
    private const val MINI_THUMB_SIZE = 100

    @GlideOption
    fun miniThumb(options: BaseRequestOptions<*>): BaseRequestOptions<*> {
        return options
            .fitCenter()
            .override(MINI_THUMB_SIZE)
    }
}

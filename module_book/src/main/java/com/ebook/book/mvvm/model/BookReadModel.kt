package com.ebook.book.mvvm.model

import android.app.Application
import com.xrn1997.common.mvvm.model.BaseModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookReadModel @Inject constructor(application: Application) : BaseModel(application)
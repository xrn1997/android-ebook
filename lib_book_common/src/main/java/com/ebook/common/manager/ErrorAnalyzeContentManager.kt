package com.ebook.common.manager

import android.content.Context
import android.util.Log
import com.xrn1997.common.event.SimpleObserver
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object ErrorAnalyzeContentManager {
    private const val TAG = "ErrorAnalyzeContentManager"

    fun writeNewErrorUrl(context: Context, url: String) {
        Observable.create { emitter ->
            try {
                val dir = getExternalFilesDir(context)
                if (dir == null) {
                    Log.e(TAG, "getExternalFilesDir is null")
                    emitter.onError(IOException("External files directory is null"))
                    return@create
                }

                val errorDetailFile = File(dir, "ErrorAnalyzeUrlsDetail.txt")
                appendToFile(errorDetailFile, "$url    \r\n")

                val errorFile = File(dir, "ErrorAnalyzeUrls.txt")
                val content = readFileContent(errorFile)
                val baseUrl = url.take(url.indexOf('/', 8))

                if (!content.contains(baseUrl)) {
                    appendToFile(errorFile, "$baseUrl    \r\n")
                }

                emitter.onNext(true)
                emitter.onComplete()
            } catch (ex: Exception) {
                Log.e(TAG, "Error in writeNewErrorUrl", ex)
                emitter.onError(ex)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<Boolean>() {
                override fun onNext(value: Boolean) {
                    // Handle success
                }

                override fun onError(e: Throwable) {
                    // Handle error
                }
            })
    }

    fun writeMayByNetError(context: Context, url: String) {
        Observable.create { emitter ->
            try {
                val dir = getExternalFilesDir(context)
                if (dir == null) {
                    Log.e(TAG, "getExternalFilesDir is null")
                    emitter.onError(IOException("External files directory is null"))
                    return@create
                }

                val errorNetFile = File(dir, "ErrorNetUrl.txt")
                appendToFile(errorNetFile, "$url    \r\n")

                emitter.onNext(true)
                emitter.onComplete()
            } catch (ex: Exception) {
                Log.e(TAG, "Error in writeMayByNetError", ex)
                emitter.onError(ex)
            }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : SimpleObserver<Boolean>() {
                override fun onNext(value: Boolean) {
                    // Handle success
                }

                override fun onError(e: Throwable) {
                    // Handle error
                }
            })
    }

    private fun getExternalFilesDir(context: Context): File? {
        val dir = context.getExternalFilesDir(null)
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: ${dir.path}")
                return null
            }
        }
        return dir
    }

    @Throws(IOException::class)
    private fun appendToFile(file: File, content: String) {
        FileOutputStream(file, true).use { fos ->
            fos.write(content.toByteArray())
            fos.flush()
        }
    }

    @Throws(IOException::class)
    private fun readFileContent(file: File): String {
        if (!file.exists()) {
            return ""
        }
        FileInputStream(file).use { fis ->
            ByteArrayOutputStream().use { outputStream ->
                val buffer = ByteArray(1024)
                var length: Int
                while (fis.read(buffer).also { length = it } != -1) {
                    outputStream.write(buffer, 0, length)
                }
                return outputStream.toString()
            }
        }
    }
}
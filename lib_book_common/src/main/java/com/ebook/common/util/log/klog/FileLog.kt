package com.ebook.common.util.log.klog

import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.util.Random

object FileLog {
    private const val FILE_PREFIX = "KLog_"
    private const val FILE_FORMAT = ".log"

    fun printFile(
        tag: String,
        headString: String,
        msg: String = "",
        targetDirectory: File,
        fileName: String = FileLog.fileName,
    ) {
        if (save(targetDirectory, fileName, msg)) {
            Log.d(
                tag,
                "$headString save log success ! location is >>> ${targetDirectory.absolutePath}/$fileName"
            )
        } else {
            Log.e(tag, "$headString save log fails !")
        }
    }

    private fun save(dic: File, fileName: String, msg: String = ""): Boolean {
        val file = File(dic, fileName)

        try {
            val outputStream: OutputStream = FileOutputStream(file)
            val outputStreamWriter = OutputStreamWriter(outputStream, "UTF-8")
            outputStreamWriter.write(msg)
            outputStreamWriter.flush()
            outputStream.close()
            return true
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return false
        } catch (e: UnsupportedEncodingException) {
            e.printStackTrace()
            return false
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private val fileName: String
        get() {
            val random = Random()
            return FILE_PREFIX + (System.currentTimeMillis() + random.nextInt(
                10000
            )).toString().substring(4) + FILE_FORMAT
        }
}

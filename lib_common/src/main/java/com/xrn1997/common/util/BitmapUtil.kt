package com.xrn1997.common.util


import android.graphics.Bitmap
import android.os.Environment
import com.xrn1997.common.BaseApplication.Companion.context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Bitmap存储与读取工具类
 * 使用该类请务必继承[com.xrn1997.common.BaseApplication]
 *@author xrn1997
 *@date 2021/3/16
 */
object BitmapUtil {
    /**
     * 将Bitmap以指定格式保存到指定路径(应用私有目录)
     * 注意,如果路径重复,就会覆盖
     * @param bitmap Bitmap
     * @param name String 文件名（需要后缀）
     * @param dir String 文件夹名称
     * @param compressFormat CompressFormat
     */
    fun addBitmapToDir(
        bitmap: Bitmap,
        name: String,
        dir: String,
        compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    ) {
        val path = context.filesDir.path + File.separator + dir + File.separator
        FileUtil.checkDirPath(path)
        // 创建一个位于SD卡上的文件
        val file = File(path, name)
        val out: FileOutputStream
        try {
            // 打开指定文件输出流
            out = FileOutputStream(file)
            // 将位图输出到指定文件
            bitmap.compress(
                compressFormat, 100,
                out
            )
            out.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 添加Bitmap到系统相册
     * @param bitmap Bitmap
     * @param displayName String 文件名（无须后缀）
     * @param compressFormat CompressFormat 图片压缩格式
     * @param mimeType String mine类型
     */
    fun addBitmapToAlbum(
        bitmap: Bitmap,
        displayName: String,
        compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        mimeType: String = FileUtil.IMAGE_TYPE + "jpeg"
    ) {
        val uri =
            FileUtil.getImageFileUri(
                context,
                displayName,
                null,
                mimeType,
                Environment.DIRECTORY_DCIM
            )
        if (uri != null) {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream != null) {
                bitmap.compress(compressFormat, 100, outputStream)
                outputStream.close()
            }
        }
    }

}

package com.xrn1997.common.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 文件工具类
 * @author xrn1997
 * @date 2022/3/5 10:47
 */
@Suppress("unused")
object FileUtil {
    private const val TAG = "FileUtil"

    /**
     * 需要附加类型,如[IMAGE_TYPE]+"jpeg"
     */
    const val IMAGE_TYPE = "image/"

    /**
     * 需要附加类型,如[VIDEO_TYPE]+"mp4"
     */
    const val VIDEO_TYPE = "video/"

    /**
     * 需要附加类型,如[AUDIO_TYPE]+"mpeg"
     */
    const val AUDIO_TYPE = "audio/"

    /**
     * 根据文件路径获得文件数据
     * @param path String
     * @return ByteArray?
     */
    fun getFileByte(path: String): ByteArray? {
        val f = File(path)
        if (!f.exists()) {
            return null
        }
        val bos = ByteArrayOutputStream(
            f.length().toInt()
        )
        val bufferedInputStream: BufferedInputStream?
        try {
            bufferedInputStream = BufferedInputStream(FileInputStream(f))
            val bufSize = 1024
            val buffer = ByteArray(bufSize)
            var len: Int
            while (-1 != bufferedInputStream.read(buffer, 0, bufSize).also { len = it }) {
                bos.write(buffer, 0, len)
            }
            bufferedInputStream.close()
            return bos.toByteArray()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                bos.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return null
    }

    /**
     * 根据Uri返回文件绝对路径
     * 兼容了file:///开头的 和 content://开头的情况
     * @param context Context
     * @param uri Uri?
     * @return String?
     */
    fun getRealFilePathFromUri(context: Context, uri: Uri?): String? {
        if (null == uri) return null
        val scheme = uri.scheme
        var data: String? = null
        if (scheme == null) {
            data = uri.path
        } else if (ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true)) {
            data = uri.path
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )
            if (null != cursor) {
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (index > -1) {
                        data = cursor.getString(index)
                    }
                }
                cursor.close()
            }
        }
        return data
    }

    /**
     * 检查文件夹是否存在
     * @param dirPath String
     * @return String
     */
    fun checkDirPath(dirPath: String): String {
        val dir = File(dirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dirPath
    }

    /**
     * 删除单个文件
     * @param filePath 被删除文件的文件名
     * @return 文件删除成功返回true,否则返回false
     */
    fun deleteFile(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.isFile && file.exists()) {
            file.delete()
        } else false
    }

    /**
     * 删除文件夹以及目录下的文件
     * @param filePath 被删除目录的文件路径
     * @return 目录删除成功返回true,否则返回false
     */
    fun deleteDirectory(filePath: String): Boolean {

        var flag: Boolean
        //如果filePath不以文件分隔符结尾,自动添加文件分隔符

        val dirFile = File(
            if (!filePath.endsWith(File.separator)) {
                filePath + File.separator
            } else filePath
        )
        if (!dirFile.exists() || !dirFile.isDirectory) {
            return false
        }
        flag = true
        val files = dirFile.listFiles()
        //遍历删除文件夹下的所有文件(包括子目录)
        for (i in files!!.indices) {
            if (files[i].isFile) {
                //删除子文件
                flag = deleteFile(files[i].absolutePath)
                if (!flag) break
            } else {
                //删除子目录
                flag = deleteDirectory(files[i].absolutePath)
                if (!flag) break
            }
        }
        return if (!flag) false else dirFile.delete()
        //删除当前空目录
    }

    /**
     * 根据时间戳生成文件名
     * @param type String 文件后缀名(不带点)
     * @return String 文件名
     */
    fun getFileName(type: String): String {
        return SimpleDateFormat.getDateTimeInstance().format(Date()) + "." + type
    }

    /**
     * 设置ContentValues
     * @param mimeType String 文件类型[IMAGE_TYPE] [VIDEO_TYPE] [AUDIO_TYPE]
     * @param directory 子文件夹
     * @param systemDirectory 如[Environment.DIRECTORY_DCIM]
     * 如 Environment.MUSIC+"/xxx"
     * @param displayName String 文件名
     * @return ContentValues
     */
    fun getFileContentValues(
        mimeType: String,
        directory: String? = "",
        systemDirectory: String,
        displayName: String,
    ): ContentValues {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        val path =
            if (directory != "") systemDirectory + File.separator + directory else systemDirectory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        } else {
            @Suppress("deprecation")
            values.put(
                MediaStore.MediaColumns.DATA,
                "${Environment.getExternalStorageDirectory().path}/${path}/$displayName"
            )
        }
        return values
    }

    /**
     * 遍历文件夹下的所有图片
     * @param context Context
     * @param uri Uri
     * @return List<Uri>?
     */
    fun queryImageFromUri(
        context: Context,
        uri: Uri
    ): List<Uri>? {
        val cursor = context.contentResolver.query(
            uri,
            null,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} desc"
        )
        if (cursor != null) {
            val uriList: MutableList<Uri> = ArrayList(cursor.columnCount)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val tempUri =
                    ContentUris.withAppendedId(uri, id)
                uriList.add(tempUri)
                Log.d(TAG, "image uri is $tempUri")
            }
            cursor.close()
        }
        return null
    }

    /**
     * 获得图片文件的Uri
     * @param context Context
     * @param displayName String
     * @param directory String?
     * @param mimeType String
     * @param systemDirectory String
     * @return Uri?
     */
    fun getImageFileUri(
        context: Context,
        displayName: String,
        directory: String? = "",
        mimeType: String = IMAGE_TYPE + "jpeg",
        systemDirectory: String = Environment.DIRECTORY_PICTURES,
    ): Uri? {
        return context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            getFileContentValues(mimeType, directory, systemDirectory, displayName)
        )
    }

    /**
     * 获得视频文件的Uri
     * @param context Context
     * @param displayName String
     * @param directory String?
     * @param mimeType String
     * @param systemDirectory String
     * @return Uri?
     */
    fun getVideoFileUri(
        context: Context,
        displayName: String,
        directory: String? = "",
        mimeType: String = VIDEO_TYPE + "mp4",
        systemDirectory: String = Environment.DIRECTORY_MOVIES,
    ): Uri? {
        return context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            getFileContentValues(mimeType, directory, systemDirectory, displayName)
        )
    }

    /**
     * 获得音频文件的Uri
     * @param context Context
     * @param displayName String
     * @param directory String?
     * @param mimeType String
     * @param systemDirectory String
     * @return Uri?
     */
    fun getAudioFileUri(
        context: Context,
        displayName: String,
        directory: String? = "",
        mimeType: String = AUDIO_TYPE + "mp3",
        systemDirectory: String = Environment.DIRECTORY_MUSIC,
    ): Uri? {
        return context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            getFileContentValues(mimeType, directory, systemDirectory, displayName)
        )
    }

}
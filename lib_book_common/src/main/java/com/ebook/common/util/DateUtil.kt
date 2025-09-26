package com.ebook.common.util

import android.util.Log
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@Suppress("unused")
object DateUtil {
    private val TAG = this::class.java.simpleName

    @JvmStatic
    fun formatDate(time: String, formatStr: String): String {
        val setDate = parseTime(time)
        if (setDate != null) {
            val sdf = SimpleDateFormat(formatStr, Locale.CHINA)
            return sdf.format(setDate)
        }
        return ""
    }

    @JvmStatic
    fun formatDate(time: String, type: FormatType): String {
        val date = parseTime(time)
        return formatDate(date, type)
    }

    @JvmStatic
    fun formatDate(time: String, fromType: FormatType, toType: FormatType): String {
        val date = parseTime(time, fromType)
        return formatDate(date, toType)
    }

    @JvmStatic
    fun formatDate(time: Date?, type: FormatType): String {
        if (time == null) return ""
        val sdf = getSimpleDateFormat(type)
        return sdf.format(time)
    }

    private fun getSimpleDateFormat(type: FormatType): SimpleDateFormat {
        return when (type) {
            FormatType.yyyy -> SimpleDateFormat("yyyy", Locale.CHINA)
            FormatType.yyyyMM -> SimpleDateFormat("yyyy-MM", Locale.CHINA)
            FormatType.yyyyMMdd -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            FormatType.yyyyMMddHHmm -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            FormatType.yyyyMMddHHmmss -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            FormatType.MMdd -> SimpleDateFormat("MM-dd", Locale.CHINA)
            FormatType.HHmm -> SimpleDateFormat("HH:mm", Locale.CHINA)
            FormatType.MM -> SimpleDateFormat("MM", Locale.CHINA)
            FormatType.dd -> SimpleDateFormat("dd", Locale.CHINA)
            FormatType.MMddHHmm -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        }
    }

    @JvmStatic
    fun parseTime(dateStr: String, formatStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat(formatStr, Locale.CHINA)
            sdf.parse(dateStr)
        } catch (e: ParseException) {
            Log.e(TAG, "parseTime: ", e)
            null
        }
    }

    @JvmStatic
    fun parseTime(time: String): Date? {
        return try {
            val sdf = getSimpleDateFormat(FormatType.yyyyMMddHHmmss)
            sdf.parse(time)
        } catch (e: Exception) {
            Log.e(TAG, "parseTime: ", e)
            null
        }
    }

    @JvmStatic
    fun parseTime(time: String, type: FormatType): Date? {
        return try {
            val sdf = getSimpleDateFormat(type)
            sdf.parse(time)
        } catch (e: Exception) {
            Log.e(TAG, "parseTime: ", e)
            null
        }
    }

    @JvmStatic
    fun addAndSubtractDate(offset: Int, date: Date, unit: Int): Date {
        val calendar = Calendar.getInstance().apply { time = date }
        if (unit == Calendar.MONTH) {
            calendar.set(Calendar.DATE, 1)
        }
        calendar.add(unit, offset)
        return calendar.time
    }

    @JvmStatic
    fun daysBetween(a: Date, b: Date): Int {
        val calendarA = Calendar.getInstance().apply {
            time = a
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val calendarB = Calendar.getInstance().apply {
            time = b
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = calendarB.timeInMillis - calendarA.timeInMillis
        return (diff / (1000 * 3600 * 24)).toInt()
    }

    @JvmStatic
    fun compareDate(a: Date, b: Date): Int {
        val calendarA = Calendar.getInstance().apply {
            time = a
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val calendarB = Calendar.getInstance().apply {
            time = b
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendarB.time.compareTo(calendarA.time)
    }

    @JvmStatic
    fun whatDay(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val strDays = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return strDays[dayOfWeek]
    }

    @JvmStatic
    fun formatSecondToHourMinuteSecond(second: Int): String {
        val h = second / 3600
        val temp = second % 3600
        val d = temp / 60
        val s = temp % 60
        return "${h}时${d}分${s}秒"
    }

    @JvmStatic
    fun formatSecondToHourMinute(duration: Int): String {
        return when {
            duration < 60 -> "${duration}秒"
            duration < 60 * 60 -> "${Math.round(duration / 60f)}分钟"
            else -> {
                val second = duration % (60 * 60)
                val round = Math.round(second / 60f)
                if (second == 0 || round == 0) {
                    "${duration / (60 * 60)}小时"
                } else {
                    "${duration / (60 * 60)}小时${if (round == 60) 1 else round}分钟"
                }
            }
        }
    }

    @JvmStatic
    fun formatTimeToDay(dateStr: String): String {
        val sdf = getSimpleDateFormat(FormatType.yyyyMMddHHmmss)
        val sdf2 = getSimpleDateFormat(FormatType.MMdd)
        val sdf3 = getSimpleDateFormat(FormatType.yyyyMMdd)
        return try {
            val date = sdf.parse(dateStr) ?: return ""
            val minute = ((System.currentTimeMillis() - date.time) / 1000 / 60).toInt()
            when {
                minute <= 0 -> "刚刚"
                minute / 60 == 0 -> "${minute}分钟前"
                minute / (60 * 24) == 0 -> "${minute / 60}小时前"
                minute / (60 * 24 * 30) == 0 -> "${minute / (60 * 24)}天前"
                minute / (60 * 24 * 30 * 12) == 0 -> sdf2.format(date)
                else -> sdf3.format(date)
            }
        } catch (e: Exception) {
            Log.e(TAG, "formatTimeToDay: ", e)
            dateStr
        }
    }

    @JvmStatic
    fun getLaterTimeByHour(hour: Int): String {
        val now = Calendar.getInstance().apply {
            time = Date()
            add(Calendar.HOUR, hour)
        }
        val sdf = getSimpleDateFormat(FormatType.yyyyMMddHHmmss)
        return sdf.format(now.time)
    }

    @JvmStatic
    fun getLaterTimeByDay(day: Int): String {
        return getLaterTimeByHour(day * 24)
    }

    @JvmStatic
    fun getLaterTimeByDay(date: String, days: Int): String {
        val parsedDate = parseTime(date, FormatType.yyyyMMdd) ?: return ""
        return Calendar.getInstance().apply {
            time = parsedDate
            add(Calendar.DAY_OF_YEAR, days)
        }.let { calendar ->
            getSimpleDateFormat(FormatType.yyyyMMdd).format(calendar.time)
        }
    }

    @JvmStatic
    fun getCurrTimePosition(): Int {
        val calendar = Calendar.getInstance().apply { time = Date() }
        return ceil((calendar[Calendar.HOUR_OF_DAY] * 60 + calendar[Calendar.MINUTE]).toDouble() / 30).toInt()
    }

    enum class FormatType {
        yyyy, yyyyMM, yyyyMMdd, yyyyMMddHHmm, yyyyMMddHHmmss, MMdd, HHmm, MM, dd, MMddHHmm
    }
}

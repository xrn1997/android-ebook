package com.ebook.common.util;

import java.math.BigDecimal;

/**
 * 数字格式化工具类
 */
public class NumberUtils {
    /**
     * 格式化为指定位小数的数字,返回未使用科学计数法表示的具有指定位数的字符串。
     * 该方法舍入模式：向“最接近的”数字舍入，如果与两个相邻数字的距离相等，则为向上舍入的舍入模式。
     * <pre>
     * 	"3.1415926", 1			--> 3.1
     * 	"3.1415926", 3			--> 3.142
     * 	"3.1415926", 4			--> 3.1416
     * 	"3.1415926", 6			--> 3.141593
     * 	"1234567891234567.1415926", 3	--> 1234567891234567.142
     * </pre>
     * @param number 类型的数字对象
     * @param precision  小数精确度总位数,如2表示两位小数
     * @return 返回数字格式化后的字符串表示形式(注意返回的字符串未使用科学计数法)
     */
    public static String keepPrecision(String number, int precision) {
        BigDecimal bg = new BigDecimal(number);
        return bg.setScale(precision, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    /**
     * 格式化为指定位小数的数字,返回未使用科学计数法表示的具有指定位数的字符串。<br>
     * 该方法舍入模式：向“最接近的”数字舍入，如果与两个相邻数字的距离相等，则为向上舍入的舍入模式。<br>
     * 如果给定的数字没有小数，则转换之后将以0填充；例如：int 123  1 --> 123.0<br>
     * <b>注意：</b>如果精度要求比较精确请使用 keepPrecision(String number, int precision)方法
     * @param number 类型的数字对象
     * @param precision  小数精确度总位数,如2表示两位小数
     * @return 返回数字格式化后的字符串表示形式(注意返回的字符串未使用科学计数法)
     */
    public static String keepPrecision(Number number, int precision) {
        return keepPrecision(String.valueOf(number), precision);
    }

    public static double keepPrecision(double number, int precision) {
        BigDecimal bg = new BigDecimal(number);
        return bg.setScale(precision, BigDecimal.ROUND_HALF_UP).doubleValue();
    }


    public static float keepPrecision(float number, int precision) {
        BigDecimal bg = new BigDecimal(number);
        return bg.setScale(precision, BigDecimal.ROUND_HALF_UP).floatValue();
    }
}

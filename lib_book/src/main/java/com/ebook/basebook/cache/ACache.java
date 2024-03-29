package com.ebook.basebook.cache;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地缓存   因本缓存只缓存书库主页 所以使用SP有条件可以替换成别的
 */
public class ACache {
    private final SharedPreferences preference;

    private ACache(Context ctx) {
        preference = ctx.getSharedPreferences("ACache", 0);
    }

    public static ACache get(Context ctx) {
        return new ACache(ctx);
    }

    public void put(String key, String value) {
        try {
            SharedPreferences.Editor editor = preference.edit();
            editor.putString(key, value);
            editor.apply();
        } catch (Exception ignored) {

        }
    }

    /**
     * 读取 String数据
     *
     * @return String 数据
     */
    public String getAsString(String key) {
        try {
            return preference.getString(key, null);
        } catch (Exception e) {
            return null;
        }
    }
}

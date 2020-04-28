package com.ebook.common.view.popupwindow;

import android.app.Application;
import android.content.Context;

import com.blankj.utilcode.util.SPUtils;

import org.jsoup.internal.StringUtil;

import java.util.regex.Pattern;

public class ProxyManager {
    public static final String SP_KEY_PROXY_HTTP = "proxy_http";
    public static final String SP_KEY_PROXY_STATE = "proxy_state";
    public static final String PROXY_HTTP_DEFAULT = "";
    public static final boolean PROXY_STATE_DEFAULT = false;
    public static Context mContext;
    public static boolean proxyState;
    public static String proxyHttp;
    private static final String PROXY_HTTP_MATCH = "(http|ftp|https):\\/\\/[\\w\\-_]+(\\.[\\w\\-_]+)+([\\w\\-\\.,@?^=%&amp;:/~\\+#]*[\\w\\-\\@?^=%&amp;/~\\+#])?";//http正则表达式
    public static final String PROXY_PACKAGENAME_ENCODE = "代理包名加密key";   //代理包名加密key
    public static String packageName; //加密后的包名

    public static void saveProxyState(boolean state) {
        proxyState = state;
        SPUtils.getInstance("CONFIG").put(SP_KEY_PROXY_STATE, proxyState);
    }

    public static void init(Application application) {
        mContext = application;
    }

    private static void initProxyState() {
        try {
            packageName = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).packageName;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("=================包名获取失败，可能会影响代理请求功能=================");
        }
        proxyState = SPUtils.getInstance("CONFIG").getBoolean(SP_KEY_PROXY_STATE, PROXY_STATE_DEFAULT);
    }

    public static void saveProxyHttp(String http) {
        proxyHttp = http;
        SPUtils.getInstance("CONFIG").put(SP_KEY_PROXY_HTTP, proxyHttp);
    }

    private static void initProxyHttp() {
        proxyHttp = SPUtils.getInstance("CONFIG").getString(SP_KEY_PROXY_HTTP, PROXY_HTTP_DEFAULT);
    }

    public static void initProxy() {
        initProxyHttp();
        initProxyState();
        hasProxy();
    }

    public static boolean hasProxy() {
        if (!proxyState) {
            return false;
        }
        Pattern pattern = Pattern.compile(PROXY_HTTP_MATCH);
        if (!StringUtil.isBlank(proxyHttp) && pattern.matcher(proxyHttp).matches()) {
            return true;
        } else {
            saveProxyState(false);
            return false;
        }
    }
}

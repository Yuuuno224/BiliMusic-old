package com.yuuuno224.bilimusic.net;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录 Cookie 的仓库。
 *
 * <p>B 站的登录态就是几个 Cookie：{@code SESSDATA}（身份）、{@code bili_jct}（CSRF token）、
 * {@code DedeUserID}（用户 uid）等。扫码登录成功后由 {@link net.api.LoginApi} 从响应头里捞出，
 * 后续每个请求都由 {@link BiliHttp} 自动带上，所以业务代码基本不需要直接碰它。
 *
 * <p>存哪儿：{@code SharedPreferences}（文件 {@code bili_cookies}，键 {@code cookies_json}）。
 * 选它而不是 JSON 文件，是因为 Cookie 需要在 App 启动最早期就能读到，而且读写频繁、
 * 数据量极小，用 SharedPreferences 的异步 apply() 更合适。
 */
public final class CookieStore {

    private static final String PREF_NAME = "bili_cookies";
    private static final String KEY_COOKIES = "cookies_json";

    /** 登录态的四个关键 Cookie，退出登录时需要一起清掉。 */
    private static final String[] SESSION_KEYS = {
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"
    };

    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static Map<String, String> cookies = new HashMap<>();

    private CookieStore() {
    }

    /** 在 {@code Application.onCreate()} 里调用，把上次登录的 Cookie 读进内存。 */
    public static synchronized void init(Context context) {
        SharedPreferences prefs = prefs(context);
        Map<String, String> loaded =
                Json.fromJsonOrNull(prefs.getString(KEY_COOKIES, null), MAP_TYPE);
        if (loaded != null) {
            cookies = loaded;
        }
    }

    public static synchronized void putAll(Map<String, String> incoming) {
        if (incoming != null && !incoming.isEmpty()) {
            cookies.putAll(incoming);
        }
    }

    public static synchronized void put(String name, String value) {
        if (name != null && value != null) {
            cookies.put(name, value);
        }
    }

    public static synchronized String get(String name) {
        return cookies.get(name);
    }

    /** 是否存在且非空。 */
    public static synchronized boolean has(String name) {
        String value = cookies.get(name);
        return value != null && !value.isEmpty();
    }

    /** 清掉登录态相关的 Cookie 但保留 buvid3 等风控指纹（下次还得用）。 */
    public static synchronized void clearSession() {
        for (String key : SESSION_KEYS) {
            cookies.remove(key);
        }
    }

    /** 拼成 HTTP 的 {@code Cookie:} 请求头；没有任何 Cookie 时返回 null。 */
    public static synchronized String cookieHeader() {
        if (cookies.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 把内存里的 Cookie 写回磁盘。登录成功、退出登录后各调一次即可。 */
    public static synchronized void persist(Context context) {
        prefs(context).edit().putString(KEY_COOKIES, Json.toJson(cookies)).apply();
    }

    /**
     * 解析 {@code Set-Cookie} 响应头。
     *
     * <p>一条典型的 Set-Cookie 长这样：
     * <pre>SESSDATA=abc123; Path=/; Domain=.bilibili.com; Max-Age=2592000; HttpOnly</pre>
     * 我们只关心第一个分号之前的那一段 {@code name=value}，后面的属性（Path / Domain / 过期时间）忽略。
     *
     * @param setCookieLines {@code Set-Cookie} 头的所有值，可能为 null
     */
    public static Map<String, String> parseSetCookie(Iterable<String> setCookieLines) {
        Map<String, String> result = new HashMap<>();
        if (setCookieLines == null) {
            return result;
        }
        for (String line : setCookieLines) {
            if (line == null) {
                continue;
            }
            String pair = line.split(";", 2)[0];
            int equals = pair.indexOf('=');
            if (equals > 0) {
                result.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}

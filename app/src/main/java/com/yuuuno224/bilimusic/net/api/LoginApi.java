package com.yuuuno224.bilimusic.net.api;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.model.UserProfile;
import com.yuuuno224.bilimusic.net.BiliException;
import com.yuuuno224.bilimusic.net.BiliHttp;
import com.yuuuno224.bilimusic.net.BiliSession;
import com.yuuuno224.bilimusic.net.CookieStore;
import com.yuuuno224.bilimusic.net.Json;
import com.yuuuno224.bilimusic.net.dto.ApiResponse;
import com.yuuuno224.bilimusic.net.dto.NavDto;
import com.yuuuno224.bilimusic.net.dto.QrLoginDto;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * 登录相关接口：生成授权链接、轮询登录状态、读用户信息、退出登录。
 *
 * <h3>为什么要"轮询"</h3>
 * 扫码登录是两个设备的协作：手机上的 B 站 App 负责确认，本 App 负责等待。
 * B 站没有给推送通道，只能每隔约 2 秒问一次"用户确认了吗"，
 * 这就是 {@link #poll} 存在的原因。{@code QrLoginActivity} 负责计时调用它。
 *
 * <h3>登录态到底是什么</h3>
 * 不是某个布尔值，而是 Cookie 里的 {@code SESSDATA} + {@code bili_jct}。
 * 登录成功的标志是 B 站在 {@code poll} 的响应头里用 {@code Set-Cookie} 下发它们，
 * {@link #poll} 会顺手存进 {@link CookieStore}，之后所有请求自动携带。
 */
public final class LoginApi {

    /** 等待用户扫码。 */
    public static final int ST_WAITING = 86101;
    /** 已扫码，等待用户在手机上点"确认"。 */
    public static final int ST_SCANNED = 86090;
    /** 二维码 / 链接已过期，需要重新生成。 */
    public static final int ST_EXPIRED = 86038;
    /** 登录成功。此时响应头里带着登录 Cookie。 */
    public static final int ST_SUCCESS = 0;

    private static final String URL_QR_GENERATE =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String URL_QR_POLL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";
    private static final String URL_NAV =
            "https://api.bilibili.com/x/web-interface/nav";
    private static final String URL_COOKIE_REVOKE =
            "https://passport.bilibili.com/x/passport-login/web/cookie/revoke";

    private static final Type TYPE_QR_GENERATE =
            new TypeToken<ApiResponse<QrLoginDto.Generate>>() { }.getType();
    private static final Type TYPE_QR_POLL =
            new TypeToken<ApiResponse<QrLoginDto.Poll>>() { }.getType();
    private static final Type TYPE_NAV =
            new TypeToken<ApiResponse<NavDto>>() { }.getType();

    private LoginApi() {
    }

    /**
     * 第一步：生成授权链接。
     *
     * @return 含 {@code url}（用户在手机上点开确认）和 {@code qrcodeKey}（后续轮询凭证）
     */
    public static QrLoginDto.Generate generate() throws IOException {
        QrLoginDto.Generate data = BiliSession.get(URL_QR_GENERATE, TYPE_QR_GENERATE, "生成授权链接");
        if (data == null || data.url == null || data.qrcodeKey == null) {
            throw new BiliException("授权链接生成失败：响应缺少 url 或 qrcode_key");
        }
        return data;
    }

    /**
     * 第二步：查一次登录状态。
     *
     * <p>返回登录成功后，B 站下发的 Cookie 已经被写进 {@link CookieStore}，
     * 但<b>还没有落盘</b>——落盘由 {@code auth.AuthManager} 在确认成功后统一调用
     * {@link CookieStore#persist(android.content.Context)}，避免把中间态写进磁盘。
     *
     * @return {@link #ST_SUCCESS} / {@link #ST_SCANNED} / {@link #ST_EXPIRED} / {@link #ST_WAITING}
     */
    public static int poll(String qrcodeKey) throws IOException {
        BiliHttp.Response response =
                BiliHttp.get(URL_QR_POLL + "?qrcode_key=" + qrcodeKey);
        ApiResponse<QrLoginDto.Poll> parsed = Json.parse(response.body, TYPE_QR_POLL);
        if (parsed.data == null) {
            return ST_WAITING;
        }
        if (parsed.data.code == ST_SUCCESS) {
            // 关键一步：登录 Cookie 不在响应体里，而在 Set-Cookie 响应头里
            CookieStore.putAll(
                    CookieStore.parseSetCookie(response.headerValues("Set-Cookie")));
        }
        return parsed.data.code;
    }

    /**
     * 读取当前登录用户信息。
     *
     * <p>未登录时 B 站返回 {@code code:-101}，这属于正常情况而不是错误，
     * 所以这里把它翻译成 {@code isLogin=false} 的空对象，而不是抛异常——
     * 调用方（"我的"页）据此显示"未登录"即可。
     */
    public static UserProfile profile() throws IOException {
        BiliHttp.Response response = BiliHttp.get(URL_NAV);
        ApiResponse<NavDto> parsed = Json.parse(response.body, TYPE_NAV);
        if (parsed.code == BiliException.CODE_NOT_LOGGED_IN) {
            return new UserProfile();
        }
        if (!parsed.ok()) {
            throw new BiliException(parsed.code, "获取用户信息失败：" + parsed.message);
        }
        return UserProfile.from(parsed.data);
    }

    /**
     * 退出登录：通知 B 站作废本次登录凭证。
     *
     * <p>失败也不要紧（比如网络不通），本地 Cookie 照样会被清掉，
     * 所以调用方应该把它当作"尽力而为"的操作。CSRF token 就是 {@code bili_jct}。
     */
    public static void revokeSession() throws IOException {
        String csrf = CookieStore.get("bili_jct");
        if (csrf == null || csrf.isEmpty()) {
            return;
        }
        HttpURLConnection conn = BiliHttp.open(URL_COOKIE_REVOKE, null);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = ("csrf=" + csrf).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            conn.getInputStream().close();
        } finally {
            conn.disconnect();
        }
    }
}

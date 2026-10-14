package com.yuuuno224.bilimusic.net;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.net.dto.ApiResponse;
import com.yuuuno224.bilimusic.net.dto.FingerprintDto;
import com.yuuuno224.bilimusic.net.dto.NavDto;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站"请求前置准备"与带签名请求的统一入口。
 *
 * <p>想稳定地调通 B 站的 web 接口，除了 URL 正确，还得过三道关：
 * <ol>
 *   <li><b>风控指纹 buvid3</b>：第一次请求前要先向 finger/spi 接口申请，
 *       之后放进 Cookie 一起发。没有它，一部分接口会直接拒绝。</li>
 *   <li><b>WBI 签名 w_rid</b>：见 {@link WbiSigner}。密钥来自 nav 接口，
 *       官方给的缓存期约 2 小时，本项目照此设置 {@link #WBI_TTL_MS}。</li>
 *   <li><b>登录 Cookie</b>：由 {@link BiliHttp} 自动附带，不用在这里处理。</li>
 * </ol>
 * 把这三件事收在一个类里，好处是业务代码（{@code net.api} 下的那些类）只需要写
 * "我要调哪个接口、参数是什么"，不用每次重复"先要指纹、再取密钥、再签名"的流程。
 *
 * <h3>签名失败自动重试</h3>
 * 密钥可能在服务端轮换而我们还在用旧的。这时 B 站会返回业务码 {@link BiliException#CODE_SIGN_INVALID}。
 * {@link #getSigned} 遇到它会把密钥缓存清空、重新拉一次密钥、再试一遍，最多重试一次。
 * 这条逻辑能挡掉绝大多数"昨天还好好的，今天搜索就报错"的情况。
 */
public final class BiliSession {

    /** WBI 密钥缓存时长，与官方前端保持一致（2 小时）。 */
    private static final long WBI_TTL_MS = 2 * 3600_000L;

    private static final String URL_FINGERPRINT =
            "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String URL_NAV =
            "https://api.bilibili.com/x/web-interface/nav";

    private static final Type TYPE_FINGERPRINT =
            new TypeToken<ApiResponse<FingerprintDto>>() { }.getType();
    private static final Type TYPE_NAV =
            new TypeToken<ApiResponse<NavDto>>() { }.getType();

    /** 缓存下来的 mixinKey；为 null 表示还没取过或已失效。 */
    private static String mixinKey;
    private static long mixinKeyFetchedAt;

    private BiliSession() {
    }

    /**
     * 确保拿到 buvid3 风控 Cookie（已有则直接返回）。
     *
     * <p>这个方法是 {@code synchronized} 的：多个线程同时第一次请求时，
     * 只会有一个真正发请求，其余等它完成后发现 Cookie 已存在就直接返回，
     * 避免并发申请指纹把自己送进风控。
     */
    public static synchronized void ensureFingerprint() throws IOException {
        if (CookieStore.has("buvid3")) {
            return;
        }
        BiliHttp.Response response = BiliHttp.get(URL_FINGERPRINT);
        ApiResponse<FingerprintDto> parsed = Json.parse(response.body, TYPE_FINGERPRINT);
        FingerprintDto data = parsed.data;
        if (parsed.ok() && data != null && data.buvid3 != null) {
            CookieStore.put("buvid3", data.buvid3);
            CookieStore.put("buvid4", data.buvid4);
        }
    }

    /**
     * 取当前有效的 mixinKey（超过 2 小时会重新拉取）。
     *
     * @throws BiliException nav 接口没返回 wbi_img 时抛出
     */
    public static synchronized String wbiMixinKey() throws IOException {
        if (mixinKey != null && System.currentTimeMillis() - mixinKeyFetchedAt < WBI_TTL_MS) {
            return mixinKey;
        }
        BiliHttp.Response response = BiliHttp.get(URL_NAV);
        ApiResponse<NavDto> parsed = Json.parse(response.body, TYPE_NAV);
        NavDto.WbiImg wbiImg = parsed.data == null ? null : parsed.data.wbiImg;
        if (wbiImg == null || wbiImg.imgUrl == null || wbiImg.subUrl == null) {
            throw new BiliException("无法获取 WBI 密钥（nav 接口未返回 wbi_img）");
        }
        mixinKey = WbiSigner.mixinKey(fileNameOf(wbiImg.imgUrl), fileNameOf(wbiImg.subUrl));
        mixinKeyFetchedAt = System.currentTimeMillis();
        return mixinKey;
    }

    /** 丢弃缓存的 WBI 密钥，下次 {@link #wbiMixinKey()} 会重新拉取。 */
    public static synchronized void invalidateWbiKeys() {
        mixinKey = null;
        mixinKeyFetchedAt = 0L;
    }

    /**
     * 调用<b>不需要签名</b>的接口，返回 {@code data} 字段。
     *
     * @param url        完整 URL（参数自行拼好）
     * @param type       {@code ApiResponse<T>} 的 Type
     * @param errorLabel 出错时拼进提示的业务名，如"获取排行榜"
     */
    public static <T> T get(String url, Type type, String errorLabel) throws IOException {
        ApiResponse<T> parsed = Json.parse(BiliHttp.get(url).body, type);
        if (!parsed.ok()) {
            throw new BiliException(parsed.code, errorLabel + "失败：" + parsed.message);
        }
        return parsed.data;
    }

    /**
     * 调用<b>需要拼参数、但不需要签名</b>的接口，返回 {@code data} 字段。
     *
     * <p>参数会按键的字典序排列并做 URL 编码，避免自己手动拼字符串时漏掉编码。
     */
    public static <T> T getWithParams(String baseUrl, Map<String, String> params, Type type,
                                      String errorLabel) throws IOException {
        StringBuilder url = new StringBuilder(baseUrl);
        char separator = baseUrl.indexOf('?') < 0 ? '?' : '&';
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            url.append(separator)
                    .append(UrlCodec.encode(entry.getKey()))
                    .append('=')
                    .append(UrlCodec.encode(entry.getValue() == null ? "" : entry.getValue()));
            separator = '&';
        }
        return get(url.toString(), type, errorLabel);
    }

    /**
     * 调用<b>需要 WBI 签名</b>的接口，返回 {@code data} 字段。
     *
     * <p>会自动完成：申请指纹 → 取密钥 → 签名 → 请求 → 校验业务码；
     * 若因密钥过期被拒（-403），刷新密钥后重试一次。
     *
     * @param baseUrl    不带参数的接口地址
     * @param params     业务参数，方法内部会排序并追加 wts / w_rid
     * @param type       {@code ApiResponse<T>} 的 Type
     * @param errorLabel 出错时拼进提示的业务名，如"搜索"
     */
    public static <T> T getSigned(String baseUrl, Map<String, String> params, Type type,
                                  String errorLabel) throws IOException {
        ensureFingerprint();
        for (int attempt = 1; ; attempt++) {
            String url = baseUrl + "?" + WbiSigner.sign(params, wbiMixinKey());
            ApiResponse<T> parsed = Json.parse(BiliHttp.get(url).body, type);
            if (parsed.ok()) {
                return parsed.data;
            }
            if (attempt == 1 && parsed.code == BiliException.CODE_SIGN_INVALID) {
                // 密钥轮换了：清掉缓存重来一次
                invalidateWbiKeys();
                continue;
            }
            throw new BiliException(parsed.code, errorLabel + "失败：" + parsed.message);
        }
    }

    /**
     * 从 {@code https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png}
     * 里取出 {@code 7cd084941338484aae1ad9425b84077c}。
     */
    private static String fileNameOf(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return url;
        }
        return url.substring(slash + 1, dot);
    }
}

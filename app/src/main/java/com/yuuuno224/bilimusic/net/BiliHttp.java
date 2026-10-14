package com.yuuuno224.bilimusic.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * HTTP 传输层：整个 App 只有这一个地方直接碰网络连接。
 *
 * <p>本项目刻意不引入 OkHttp / Retrofit，用 JDK 自带的 {@link HttpURLConnection}。
 * 代价是没有连接池和拦截器，好处是零依赖、体积小、逻辑一眼可见。
 *
 * <p>这一层负责三件事，而且只负责这三件事：
 * <ol>
 *   <li>统一请求头：冒充桌面 Chrome（{@link #UA}）并带上 {@link #REFERER}，
 *       否则 B 站部分接口会直接返回 412；</li>
 *   <li>自动附带 Cookie（来自 {@link CookieStore}），登录后才有的接口才能用；</li>
 *   <li>把 HTTP 层的失败（4xx / 5xx）翻译成带状态码的 {@link BiliException}。</li>
 * </ol>
 * <b>不负责</b>：JSON 解析（见 {@link Json}）、业务码判断（见 {@link BiliSession}）、
 * 风控指纹与签名（见 {@link BiliSession}）。
 */
public final class BiliHttp {

    /** 桌面 Chrome 的 User-Agent。B 站会按 UA 区分接口行为，用默认的 okhttp/Java UA 容易被拒。 */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    /** 防盗链。B 站的图片 CDN 和部分接口会校验 Referer。 */
    public static final String REFERER = "https://www.bilibili.com/";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /** 请求失败时，最多把响应体的前 N 个字符放进异常信息里，方便排查。 */
    private static final int ERROR_BODY_LIMIT = 300;

    /** 一次请求的完整结果：状态码 + 正文 + 响应头。 */
    public static final class Response {
        public final int status;
        public final String body;
        private final Map<String, List<String>> headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        /**
         * 按名字取响应头的第一个值（大小写不敏感）。
         * <p>扫码登录成功时，登录 Cookie 就在 {@code Set-Cookie} 响应头里，需要这样取出来。
         */
        public String header(String name) {
            List<String> values = headerValues(name);
            return values.isEmpty() ? null : values.get(0);
        }

        /** 取某个响应头的全部值（{@code Set-Cookie} 可能有多条）。 */
        public List<String> headerValues(String name) {
            if (headers == null || name == null) {
                return java.util.Collections.emptyList();
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
            return java.util.Collections.emptyList();
        }
    }

    private BiliHttp() {
    }

    /** 发起 GET 请求（自动带上 UA / Referer / Cookie）。 */
    public static Response get(String url) throws IOException {
        return get(url, null);
    }

    /**
     * 发起 GET 请求，可额外指定请求头。
     *
     * @param extraHeaders 额外请求头，可为 null
     * @throws BiliException HTTP 状态码 &gt;= 400 时抛出，异常里带有状态码与响应体片段
     */
    public static Response get(String url, Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection conn = open(url, extraHeaders);
        try {
            int status = conn.getResponseCode();
            // 4xx/5xx 时 getInputStream() 会直接抛异常且丢失响应体，所以要改用 getErrorStream()
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            if (status >= 400) {
                String detail = body.isEmpty() ? "" : "：" + Json.snippet(abbreviate(body));
                throw new BiliException(status, "HTTP " + status + detail);
            }
            return new Response(status, body, conn.getHeaderFields());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 打开一个已配置好请求头的连接。
     * <p>暴露出来是给 ExoPlayer（音频流）和 {@link com.yuuuno224.bilimusic.util.ImageLoader}
     * （图片下载）复用的——它们需要自己控制读取过程，不适合走 {@link #get}。
     * <p><b>调用方必须自己调 {@code disconnect()}。</b>
     */
    public static HttpURLConnection open(String url, Map<String, String> extraHeaders)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);

        String cookie = CookieStore.cookieHeader();
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1 << 16);
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String body) {
        return body.length() <= ERROR_BODY_LIMIT ? body : body.substring(0, ERROR_BODY_LIMIT);
    }
}

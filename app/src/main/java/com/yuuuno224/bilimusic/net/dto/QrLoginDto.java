package com.yuuuno224.bilimusic.net.dto;

import com.google.gson.annotations.SerializedName;

/**
 * 扫码登录两个接口的数据结构。
 *
 * <pre>
 * 生成：/x/passport-login/web/qrcode/generate
 *   { "code":0, "data": { "url":"https://passport.bilibili.com/...", "qrcode_key":"abc" } }
 *
 * 轮询：/x/passport-login/web/qrcode/poll?qrcode_key=abc
 *   { "code":0, "data": { "url":"...", "refresh_token":"...", "timestamp":1700000000, "code":86101 } }
 * </pre>
 *
 * <p><b>注意有两层 code：</b>外层的 {@code ApiResponse.code} 表示"接口调用成功没有"；
 * 内层 {@link Poll#code} 才表示"用户扫码到哪一步了"。别搞混。
 */
public class QrLoginDto {

    /** 生成二维码的 {@code data}。 */
    public static class Generate {

        /**
         * 授权链接。用户点它会被唤起 B 站 App 的确认页。
         * 本项目只做"点链接确认"，不自己画二维码图片。
         */
        public String url;

        /** 轮询凭证，生成时拿一次，之后每次轮询都要带上。 */
        @SerializedName("qrcode_key")
        public String qrcodeKey;
    }

    /** 轮询的 {@code data}。 */
    public static class Poll {

        public String url;

        @SerializedName("refresh_token")
        public String refreshToken;

        public long timestamp;

        /**
         * 登录进度，取值见 {@code net.api.LoginApi} 的 ST_* 常量。
         * <p>0 表示成功；此时 B 站会在响应头里用 {@code Set-Cookie} 下发登录 Cookie。
         */
        public int code;
    }
}

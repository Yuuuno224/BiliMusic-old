package com.yuuuno224.bilimusic.net;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * URL 编码工具。
 *
 * <p>存在的唯一理由：{@link URLEncoder} 默认按表单规则编码，会把空格变成 {@code +}，
 * 而 WBI 签名要求跟 JavaScript 的 {@code encodeURIComponent} 一致（空格编成 {@code %20}）。
 * 另外 {@code encodeURIComponent} 不编码 {@code ~}，{@link URLEncoder} 会编成 {@code %7E}，
 * 也要改回来。
 */
final class UrlCodec {

    private UrlCodec() {
    }

    /** 等价于 JavaScript 的 {@code encodeURIComponent}。 */
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JDK 必须支持的编码
            throw new IllegalStateException("当前运行环境不支持 UTF-8", e);
        }
    }
}

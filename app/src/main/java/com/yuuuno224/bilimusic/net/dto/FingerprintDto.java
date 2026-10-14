package com.yuuuno224.bilimusic.net.dto;

import com.google.gson.annotations.SerializedName;

/**
 * 风控指纹（{@code /x/frontend/finger/spi}）。
 *
 * <p>返回两个值，都要塞进 Cookie：
 * <pre>
 * { "code":0, "data": { "b_3":"...", "b_4":"..." } }
 * </pre>
 * {@code b_3} 就是 Cookie 里的 {@code buvid3}。没有它，B 站会把请求当成可疑客户端。
 */
public class FingerprintDto {

    @SerializedName("b_3")
    public String buvid3;

    @SerializedName("b_4")
    public String buvid4;
}

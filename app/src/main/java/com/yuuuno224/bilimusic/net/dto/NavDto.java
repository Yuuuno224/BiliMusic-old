package com.yuuuno224.bilimusic.net.dto;

import com.google.gson.annotations.SerializedName;

/**
 * 用户信息（{@code /x/web-interface/nav}）。
 *
 * <p>这个接口一件两用，所以它比一般 DTO 稍特殊：
 * <ul>
 *   <li>登录后：读 {@code uname / face / mid / level_info} 显示用户卡片；</li>
 *   <li>无论是否登录：读 {@code wbi_img}，因为 WBI 签名密钥就藏在这里
 *       （见 {@link com.yuuuno224.bilimusic.net.BiliSession#wbiMixinKey()}）。</li>
 * </ul>
 * 因此 {@code wbi_img} 即使没人登录也必须能解析出来。
 *
 * <p>未登录时 B 站返回 {@code code:-101}，而 {@code data} 依然是合法对象、
 * {@code isLogin} 为 false —— 所以获取用户信息时不能只靠 code 判断登录态。
 */
public class NavDto {

    /** 是否已登录。未登录时为 false。 */
    public boolean isLogin;
    public long mid;
    public String uname;
    public String face;

    public LevelInfo levelInfo;

    @SerializedName("wbi_img")
    public WbiImg wbiImg;

    public static class LevelInfo {
        @SerializedName("current_level")
        public int currentLevel;
    }

    /** WBI 密钥图片地址，取文件名即为 imgKey / subKey。 */
    public static class WbiImg {
        @SerializedName("img_url")
        public String imgUrl;
        @SerializedName("sub_url")
        public String subUrl;
    }
}

package com.yuuuno224.bilimusic.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * WBI 签名。
 *
 * <h3>为什么需要签名</h3>
 * B 站在 2023 年给一批 web 接口加了 WBI 校验：请求必须额外带上 {@code wts}（时间戳）
 * 和 {@code w_rid}（签名）。服务端会用同样的算法算一遍 {@code w_rid}，对不上就返回 -403。
 * 这能挡住不模拟浏览器的爬虫。
 *
 * <h3>算法（三步）</h3>
 * <ol>
 *   <li><b>取密钥</b>：调用 nav 接口拿到 {@code wbi_img.img_url} 和 {@code sub_url}，
 *       各自取文件名（去掉目录和 {@code .png} 后缀），得到 {@code imgKey} 和 {@code subKey}，
 *       两者都是 32 个字符。</li>
 *   <li><b>混淆出 mixinKey</b>：把两个 key 拼成 64 个字符，再按固定的 {@link #MIXIN_TABLE}
 *       顺序重新排列，取前 32 个字符。这一步见 {@link #mixinKey(String, String)}。</li>
 *   <li><b>算 w_rid</b>：把所有参数按 <b>键的字典序</b> 排序 → 去掉值里的
 *       {@code ! ' ( ) *} 五个字符 → URL 编码 → 拼成 {@code a=1&b=2} 形式 →
 *       在末尾直接接上 mixinKey → 对整个字符串做 MD5。见 {@link #sign}。</li>
 * </ol>
 *
 * <p>第 3 步的顺序很关键：先排序、再去字符、再编码，最后才拼 mixinKey。
 * 顺序错了签名一定算不对，而且服务端只会回一句"签名校验失败"，不会告诉你错在哪。
 */
public final class WbiSigner {

    /**
     * 混淆索引表：官方前端 JS 里硬编码的 64 个下标，是 0~63 的一个排列。
     *
     * <p>曾经有人以为它存在"64 位"和"84 位"两个版本，实测 {@code imgKey + subKey} 恒为 64 个字符，
     * 这张表足够索引全部字符，所以本项目只保留这一张表，并只用它的前 32 个下标
     * （官方实现也是取前 32 个字符作为 mixinKey）。
     */
    private static final int[] MIXIN_TABLE = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52};

    /** mixinKey 的长度，固定 32。 */
    private static final int MIXIN_KEY_LENGTH = 32;

    /** 参与签名的参数值里必须剔除的字符（官方规则）。 */
    private static final String ILLEGAL_CHARS = "[!'()*]";

    private WbiSigner() {
    }

    /**
     * 由 imgKey 和 subKey 混淆出 32 位 mixinKey。
     *
     * @param imgKey nav.wbi_img.img_url 的文件名部分
     * @param subKey nav.wbi_img.sub_url 的文件名部分
     */
    public static String mixinKey(String imgKey, String subKey) {
        String joined = (imgKey == null ? "" : imgKey) + (subKey == null ? "" : subKey);
        StringBuilder sb = new StringBuilder(MIXIN_KEY_LENGTH);
        for (int index : MIXIN_TABLE) {
            if (sb.length() == MIXIN_KEY_LENGTH) {
                break;
            }
            // 兜底：密钥异常短时不越界（正常情况下 joined 恒为 64 个字符）
            if (index >= joined.length()) {
                break;
            }
            sb.append(joined.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 生成带 {@code wts} 和 {@code w_rid} 的查询串。
     *
     * <p>返回值已经是编码过的 query，直接拼在 {@code "?"} 后面即可（不要再编码一次）。
     *
     * @param params   业务参数（不含 wts / w_rid）
     * @param mixinKey 由 {@link #mixinKey} 得到，或由 {@link BiliSession} 缓存的密钥
     * @return 形如 {@code keyword=xxx&page=1&wts=1700000000&w_rid=abcdef...}
     */
    public static String sign(Map<String, String> params, String mixinKey) {
        // TreeMap 保证按 key 的字典序迭代 —— 这正是服务端校验时用的顺序
        Map<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            query.append(UrlCodec.encode(entry.getKey()))
                    .append('=')
                    .append(UrlCodec.encode(value.replaceAll(ILLEGAL_CHARS, "")));
        }

        String payload = query.toString();
        return payload + "&w_rid=" + md5Hex(payload + mixinKey);
    }

    /** 计算字符串的 MD5 十六进制小写表示。 */
    public static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必须实现的算法，正常永远不会走到这里
            throw new IllegalStateException("当前运行环境不支持 MD5", e);
        }
    }
}

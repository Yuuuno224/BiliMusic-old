package com.yuuuno224.bilimusic.util;

/**
 * B 站返回文本的清洗与解析。
 *
 * <p>为什么单独一个类：这些规则都是"B 站接口的怪癖"，散落在各处会很难找。
 * 集中在这里，一旦 B 站改了返回格式，只需要改这一个文件。
 */
public final class BiliText {

    /** 搜索关键词高亮的标签，例如 {@code <em class="keyword">周杰伦</em>}。 */
    private static final String HIGHLIGHT_TAG = "</?em[^>]*>";

    private BiliText() {
    }

    /**
     * 清洗搜索结果标题。
     *
     * <p>B 站会在命中的关键词外面套一层高亮标签，直接显示会出现
     * {@code <em class="keyword">周杰伦</em>晴天} 这种字样，必须先去掉。
     */
    public static String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return unescape(raw.replaceAll(HIGHLIGHT_TAG, "").replace('\n', ' ')).trim();
    }

    /**
     * 解析时长为秒数。
     *
     * <p>要同时兼容两种格式，因为不同接口给的不一样：
     * <ul>
     *   <li>搜索结果：{@code "3:45"} 或 {@code "1:02:33"} —— 冒号分隔的文本</li>
     *   <li>排行榜 / 收藏夹：{@code "245"} 或数字 {@code 245} —— 直接就是秒数</li>
     * </ul>
     * 解析不出来时返回 0，界面会显示 {@code --:--}，不会崩。
     *
     * @param text 可能是 null、空串、带空格、或上述任意一种格式
     */
    public static long parseDurationSeconds(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String trimmed = text.trim();
        try {
            if (trimmed.indexOf(':') < 0) {
                // 纯数字：直接当秒数
                return Long.parseLong(trimmed);
            }
            long seconds = 0;
            for (String part : trimmed.split(":")) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            return seconds;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 还原几个常见的 HTML 实体。 */
    private static String unescape(String text) {
        return text.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}

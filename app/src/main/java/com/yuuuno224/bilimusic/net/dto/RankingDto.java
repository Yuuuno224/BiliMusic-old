package com.yuuuno224.bilimusic.net.dto;

/**
 * 音乐区排行榜（{@code /x/web-interface/ranking/index?rid=3&ps=20}）。
 *
 * <p>注意这个接口的 {@code data} <b>直接就是数组</b>，没有外层对象，
 * 和其他接口长得不一样：
 * <pre>
 * { "code":0, "data": [ { "bvid":"...", "title":"...", "author":"...", "pic":"...", "duration":245 } ] }
 * </pre>
 *
 * <p>{@code duration} 在这里是<b>秒数</b>（不像搜索结果那样是 "3:45" 文本），
 * 所以不能套用同一个解析函数。Gson 会把 JSON 数字读进 String 字段，值是 "245"。
 */
public class RankingDto {

    public static class Item {

        public String bvid;
        public String title;
        public String author;
        public String pic;
        /** 秒数字符串，例如 "245"。 */
        public String duration;
    }
}

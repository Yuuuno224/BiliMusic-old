package com.yuuuno224.bilimusic.net.dto;

import java.util.List;

/**
 * 搜索结果（{@code /x/web-interface/search/type?search_type=video}）。
 *
 * <p>对应 JSON 形状（只列出本项目用到的字段）：
 * <pre>
 * { "code":0, "data": { "seid":"...", "result":[ { "bvid":"BV1xx", "title":"...", ... } ] } }
 * </pre>
 *
 * <p>没列出来的字段（description、play、danmaku、pubdate …）Gson 会直接忽略，
 * 需要时按 JSON 里的名字加一个 public 字段即可，不用改任何解析代码。
 */
public class SearchDto {

    public String seid;
    public List<Item> result;

    /** 一条视频搜索结果。 */
    public static class Item {

        public String bvid;
        /** 标题里带关键词高亮标签，如 {@code <em class="keyword">周杰伦</em>}，展示前必须清洗。 */
        public String title;
        public String author;
        /** 时长，形如 {@code "3:45"} 或 {@code "1:02:33"}，不是秒数。 */
        public String duration;
        public String pic;
    }
}

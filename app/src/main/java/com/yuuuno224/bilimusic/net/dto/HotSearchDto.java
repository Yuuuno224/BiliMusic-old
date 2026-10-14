package com.yuuuno224.bilimusic.net.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 热门搜索词（{@code /x/web-interface/search/square?limit=10}）。
 *
 * <pre>
 * { "code":0, "data": { "trending": { "list": [ { "keyword":"...", "show_name":"..." } ] } } }
 * </pre>
 */
public class HotSearchDto {

    public Trending trending;

    public static class Trending {
        public List<Item> list;
    }

    public static class Item {
        public String keyword;
        /**
         * 展示用的名字，通常比 keyword 更"人话"，优先用它。
         * <p>JSON 里的键是下划线风格的 {@code show_name}，加注解才能自动映射到驼峰字段。
         */
        @SerializedName("show_name")
        public String showName;
    }
}

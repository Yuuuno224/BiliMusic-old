package com.yuuuno224.bilimusic.net.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 收藏夹相关接口的数据结构（<b>纯 JSON 镜像，不含任何逻辑</b>）。
 *
 * <p>两个接口各有一个响应外壳，所以这里放了两个静态嵌套类：
 * <ul>
 *   <li>{@link FolderList} —— 收藏夹列表（{@code /x/v3/fav/folder/created/list}）</li>
 *   <li>{@link ResourceList} —— 某个收藏夹里的内容（{@code /x/v3/fav/resource/list}）</li>
 * </ul>
 *
 * <p>把"翻译成 App 自己的模型"这件事留给 {@code net.api.FavoriteApi} 做，
 * 这样本文件永远只是 JSON 的形状说明，改起来不会牵连业务逻辑。
 */
public class FavoriteDto {

    /** 收藏夹列表的 {@code data}。 */
    public static class FolderList {
        public List<Folder> list;
    }

    /** 一个收藏夹。 */
    public static class Folder {

        /** 收藏夹 id，不是用户 uid；请求夹内内容时要传这个。 */
        public long id;
        public String title;

        @SerializedName("media_count")
        public long mediaCount;

        public String cover;
    }

    /** 收藏夹内容的 {@code data}（分页返回）。 */
    public static class ResourceList {

        public List<Resource> medias;

        /** 是否还有下一页，用来决定要不要继续翻页。 */
        @SerializedName("has_more")
        public boolean hasMore;
    }

    /** 收藏夹里的一条内容。请求时加了 {@code type=2}，所以这里只会是视频。 */
    public static class Resource {

        public String bvid;
        public String title;
        public String cover;

        /** 时长，单位秒（收藏夹接口返回数字，不是 "3:45" 文本）。 */
        public long duration;

        /** 直接带回来了 cid，所以收藏夹里的歌可以跳过"取视频详情"这一步。 */
        public long cid;

        public Upper upper;
    }

    public static class Upper {
        public String name;
    }
}

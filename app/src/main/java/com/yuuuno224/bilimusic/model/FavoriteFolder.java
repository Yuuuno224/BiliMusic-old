package com.yuuuno224.bilimusic.model;

/**
 * B 站账号里的一个收藏夹。
 *
 * <p>乐库页的"收藏夹"标签先列出它，点进去再列出夹内的歌。
 * 它由 {@code net.api.FavoriteApi} 从接口数据翻译而来。
 */
public class FavoriteFolder {

    /** 收藏夹 id，点进去要用它请求夹内内容。 */
    public long id;

    public String title;

    /** 夹内条目数量。 */
    public long mediaCount;

    public String cover;

    /** 数量文本，例如 "128 个内容"。 */
    public String countText() {
        return mediaCount + " 个内容";
    }
}

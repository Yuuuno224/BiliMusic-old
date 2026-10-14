package com.yuuuno224.bilimusic.net.api;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.model.FavoriteFolder;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.BiliSession;
import com.yuuuno224.bilimusic.net.BiliText;
import com.yuuuno224.bilimusic.net.dto.ApiResponse;
import com.yuuuno224.bilimusic.net.dto.FavoriteDto;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 收藏夹接口（需要登录 + WBI 签名）。
 *
 * <h3>两级结构</h3>
 * B 站账号下有多个收藏夹（"默认收藏夹""音乐""稍后再看"…），每个夹里又有若干视频，
 * 所以是两个接口两级列表。乐库页的"收藏夹"标签就是照着这个结构做的导航：
 * 先列夹，点进去列视频。
 *
 * <h3>为什么要自动翻页</h3>
 * 收藏夹内容接口单页最多 20 条，且每页都要单独请求。
 * {@link #allResources} 会在后台一直翻到没有下一页为止（有 {@link #MAX_PAGES} 上限兜底），
 * 让上层一次就能拿到整个夹的内容——代价是收藏夹很大时首次进入会慢一些。
 */
public final class FavoriteApi {

    private static final String URL_FOLDER_LIST =
            "https://api.bilibili.com/x/v3/fav/folder/created/list";
    private static final String URL_RESOURCE_LIST =
            "https://api.bilibili.com/x/v3/fav/resource/list";

    /** 每页条数。B 站这两个接口的单页上限就是 20，写大了也无效。 */
    private static final int PAGE_SIZE = 20;

    /**
     * 自动翻页的上限。
     * <p>不能无限翻：一个几千条的收藏夹会产生上百次请求，既慢又容易触发风控。
     * 200 条（10 页）是刻意留的保守值。
     */
    private static final int MAX_PAGES = 10;

    private static final Type TYPE_FOLDER_LIST =
            new TypeToken<ApiResponse<FavoriteDto.FolderList>>() { }.getType();
    private static final Type TYPE_RESOURCE_LIST =
            new TypeToken<ApiResponse<FavoriteDto.ResourceList>>() { }.getType();

    private FavoriteApi() {
    }

    /**
     * 取某个用户的全部收藏夹。
     *
     * @param mid 用户 uid，来自 {@code model.UserProfile#mid}；不能传 0
     */
    public static List<FavoriteFolder> folders(long mid) throws IOException {
        Map<String, String> params = new TreeMap<>();
        params.put("up_mid", String.valueOf(mid));
        params.put("pn", "1");
        params.put("ps", String.valueOf(PAGE_SIZE));

        FavoriteDto.FolderList data =
                BiliSession.getSigned(URL_FOLDER_LIST, params, TYPE_FOLDER_LIST, "获取收藏夹");

        List<FavoriteFolder> folders = new ArrayList<>();
        if (data == null || data.list == null) {
            return folders;
        }
        for (FavoriteDto.Folder folder : data.list) {
            if (folder == null) {
                continue;
            }
            FavoriteFolder model = new FavoriteFolder();
            model.id = folder.id;
            model.title = folder.title;
            model.mediaCount = folder.mediaCount;
            model.cover = folder.cover;
            folders.add(model);
        }
        return folders;
    }

    /**
     * 取一个收藏夹里的全部视频（自动翻页）。
     *
     * @param mediaId 收藏夹 id（{@code FavoriteFolder#id}），不是用户 uid
     * @return 歌曲列表；夹内内容为空时返回空列表
     */
    public static List<Song> allResources(long mediaId) throws IOException {
        List<Song> songs = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            FavoriteDto.ResourceList data = resources(mediaId, page);
            if (data == null || data.medias == null || data.medias.isEmpty()) {
                break;
            }
            for (FavoriteDto.Resource resource : data.medias) {
                if (resource == null || resource.bvid == null || resource.bvid.isEmpty()) {
                    // 已失效/已删除的视频条目 bvid 为空，跳过
                    continue;
                }
                Song song = new Song(
                        resource.bvid,
                        BiliText.cleanTitle(resource.title),
                        resource.upper == null || resource.upper.name == null
                                ? "" : resource.upper.name,
                        resource.cover,
                        resource.duration);
                // 收藏夹接口直接给了 cid，播放时就不用再请求一次视频详情
                song.cid = resource.cid;
                songs.add(song);
            }
            if (!data.hasMore) {
                break;
            }
        }
        return songs;
    }

    /** 取收藏夹的某一页。 */
    private static FavoriteDto.ResourceList resources(long mediaId, int page) throws IOException {
        Map<String, String> params = new TreeMap<>();
        params.put("media_id", String.valueOf(mediaId));
        params.put("pn", String.valueOf(page));
        params.put("ps", String.valueOf(PAGE_SIZE));
        // order=mtime：按收藏时间倒序，最近收藏的排前面
        params.put("order", "mtime");
        // type=2：只看视频，过滤掉音频/专栏等其它类型
        params.put("type", "2");
        params.put("platform", "web");

        return BiliSession.getSigned(URL_RESOURCE_LIST, params, TYPE_RESOURCE_LIST, "获取收藏内容");
    }
}

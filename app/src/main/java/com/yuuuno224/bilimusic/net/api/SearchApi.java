package com.yuuuno224.bilimusic.net.api;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.BiliSession;
import com.yuuuno224.bilimusic.net.BiliText;
import com.yuuuno224.bilimusic.net.dto.ApiResponse;
import com.yuuuno224.bilimusic.net.dto.HotSearchDto;
import com.yuuuno224.bilimusic.net.dto.RankingDto;
import com.yuuuno224.bilimusic.net.dto.SearchDto;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 搜索相关的 B 站接口。
 *
 * <h3>三个方法</h3>
 * <ul>
 *   <li>{@link #search} —— 关键词搜视频，<b>需要 WBI 签名</b></li>
 *   <li>{@link #hotWords} —— 热门搜索词，不需要签名</li>
 *   <li>{@link #musicRanking} —— 音乐区排行榜，不需要签名</li>
 * </ul>
 *
 * <h3>本类的职责边界</h3>
 * 只做两件事：拼参数调接口、把接口数据（dto）翻译成 App 的模型（{@link Song}）。
 * <b>不切线程、不做缓存、不回调</b> —— 那是 {@code repo.MusicRepository} 的活。
 * 所有方法都是同步阻塞的，<b>必须在子线程调用</b>。
 */
public final class SearchApi {

    private static final String URL_SEARCH =
            "https://api.bilibili.com/x/web-interface/search/type";
    private static final String URL_HOT_SEARCH =
            "https://api.bilibili.com/x/web-interface/search/square?limit=10";
    private static final String URL_MUSIC_RANKING =
            "https://api.bilibili.com/x/web-interface/ranking/index?rid=3&ps=20";

    /** 每页条数。B 站搜索接口单页上限 20。 */
    private static final int PAGE_SIZE = 20;

    private static final Type TYPE_SEARCH =
            new TypeToken<ApiResponse<SearchDto>>() { }.getType();
    private static final Type TYPE_HOT_SEARCH =
            new TypeToken<ApiResponse<HotSearchDto>>() { }.getType();
    private static final Type TYPE_RANKING =
            new TypeToken<ApiResponse<List<RankingDto.Item>>>() { }.getType();

    private SearchApi() {
    }

    /**
     * 按关键词搜索视频。
     *
     * @param keyword 关键词，调用方需保证非空
     * @param page    页码，从 1 开始
     * @return 歌曲列表；没有结果时返回空列表（不是 null）
     */
    public static List<Song> search(String keyword, int page) throws IOException {
        // TreeMap：WBI 签名要求参数按键的字典序排列，用 TreeMap 天然满足
        Map<String, String> params = new TreeMap<>();
        params.put("keyword", keyword);
        params.put("page", String.valueOf(page));
        params.put("page_size", String.valueOf(PAGE_SIZE));
        params.put("platform", "pc");
        params.put("search_type", "video");

        SearchDto data = BiliSession.getSigned(URL_SEARCH, params, TYPE_SEARCH, "搜索");
        List<Song> songs = new ArrayList<>();
        if (data == null || data.result == null) {
            return songs;
        }
        for (SearchDto.Item item : data.result) {
            if (item == null || item.bvid == null || item.bvid.isEmpty()) {
                continue;
            }
            // 注意：搜索结果不带 cid，Song.cid 保持为 0，播放时再补
            songs.add(new Song(
                    item.bvid,
                    BiliText.cleanTitle(item.title),
                    item.author == null ? "" : item.author,
                    item.pic,
                    BiliText.parseDurationSeconds(item.duration)));
        }
        return songs;
    }

    /**
     * 热门搜索词，用于搜索首页的 chips。
     *
     * @return 关键词列表；接口异常时返回空列表，由界面决定是否用兜底词
     */
    public static List<String> hotWords() throws IOException {
        BiliSession.ensureFingerprint();
        HotSearchDto data = BiliSession.get(URL_HOT_SEARCH, TYPE_HOT_SEARCH, "获取热门搜索");
        List<String> words = new ArrayList<>();
        if (data == null || data.trending == null || data.trending.list == null) {
            return words;
        }
        for (HotSearchDto.Item item : data.trending.list) {
            if (item == null) {
                continue;
            }
            // show_name 更像人话，优先用它；没有才退回 keyword
            String word = item.showName != null && !item.showName.isEmpty()
                    ? item.showName : item.keyword;
            if (word != null && !word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * 音乐区排行榜（rid=3）。
     *
     * @return 歌曲列表；接口异常时返回空列表
     */
    public static List<Song> musicRanking() throws IOException {
        BiliSession.ensureFingerprint();
        List<RankingDto.Item> items =
                BiliSession.get(URL_MUSIC_RANKING, TYPE_RANKING, "获取排行榜");
        List<Song> songs = new ArrayList<>();
        if (items == null) {
            return songs;
        }
        for (RankingDto.Item item : items) {
            if (item == null || item.bvid == null || item.bvid.isEmpty()) {
                continue;
            }
            songs.add(new Song(
                    item.bvid,
                    BiliText.cleanTitle(item.title),
                    item.author == null ? "" : item.author,
                    item.pic,
                    BiliText.parseDurationSeconds(item.duration)));
        }
        return songs;
    }
}

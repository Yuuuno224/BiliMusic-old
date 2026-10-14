package com.yuuuno224.bilimusic.store;

import android.content.Context;
import android.util.Log;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 本地数据仓库：收藏、播放历史、搜索历史。
 *
 * <h3>为什么不用数据库</h3>
 * 只有三个列表、总量最多几百条，用 SQLite/Room 属于杀鸡用牛刀。
 * 整个仓库就是「内存里三个 List + 一个 JSON 文件」，读一次、写多次，
 * 对初学者来说比学一套 ORM 更容易理解。
 *
 * <h3>存储文件</h3>
 * {@code {应用私有目录}/music_store.json}：
 * <pre>
 * { "favorites":[Song...], "history":[Song...], "searchHistory":["关键词", ...] }
 * </pre>
 * 应用私有目录不需要任何权限，卸载 App 时会被系统一起清掉。
 *
 * <h3>两个关键设计</h3>
 * <ol>
 *   <li><b>写文件在后台线程、且是原子写</b>：先把内容写进 {@code music_store.json.tmp}，
 *       再用 {@link Files#move} 覆盖正式文件。这样即使写到一半被杀进程，
 *       正式文件也仍然是完整的旧版本，不会变成半截 JSON 导致收藏全丢。</li>
 *   <li><b>内存为主、落盘为辅</b>：{@link #addHistory} 等方法是同步的（改内存），
 *       落盘是异步的。所以调用方不需要关心 IO，也不会被 IO 卡住界面。</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * 所有公开方法都是 {@code synchronized} 的。它们在主线程被调用（改内存很快），
 * 真正耗时的文件写入被丢给 {@link AppExecutors#store()} 单线程队列，因此不会阻塞界面，
 * 也不会出现两次写文件互相踩踏。
 */
public final class MusicStore {

    private static final String TAG = "MusicStore";
    private static final String FILE_NAME = "music_store.json";
    private static final String TEMP_SUFFIX = ".tmp";

    /** 播放历史上限。超过就丢掉最旧的，避免文件无限增长。 */
    private static final int MAX_HISTORY = 200;

    /** 搜索历史上限。 */
    private static final int MAX_SEARCH_HISTORY = 10;

    private static final Type STORE_TYPE = new TypeToken<StoreData>() { }.getType();

    private static List<Song> favorites = new ArrayList<>();
    private static List<Song> history = new ArrayList<>();
    private static Deque<String> searchHistory = new ArrayDeque<>();

    private static File storeFile;

    private MusicStore() {
    }

    /**
     * 在 {@code Application.onCreate()} 里调用。
     *
     * <p>这里是<b>同步</b>读取的：文件很小（最多几百条），读一次只要几毫秒，
     * 而界面一打开就要显示收藏和最近播放，异步读反而要处理"数据还没到"的空状态。
     */
    public static synchronized void init(Context context) {
        storeFile = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    /** 判断某首歌是否已收藏。 */
    public static synchronized boolean isFavorite(String bvid) {
        if (bvid == null) {
            return false;
        }
        for (Song song : favorites) {
            if (bvid.equals(song.bvid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收藏 / 取消收藏。
     *
     * @return 操作<b>之后</b>是否处于已收藏状态；调用方据此显示"已收藏 / 已取消收藏"
     */
    public static synchronized boolean toggleFavorite(Song song) {
        if (song == null || song.bvid == null) {
            return false;
        }
        // Song.equals 只比较 bvid，所以这里能正确移除"同一首歌"的旧对象
        favorites.remove(song);
        boolean nowFavorite = !isFavorite(song.bvid);
        if (nowFavorite) {
            song.addedAt = System.currentTimeMillis();
            favorites.add(0, song);   // 新收藏的排最前面
        }
        persist();
        return nowFavorite;
    }

    /** 收藏列表（返回副本，外部修改不会影响仓库）。 */
    public static synchronized List<Song> favorites() {
        return new ArrayList<>(favorites);
    }

    // ------------------------------------------------------------------
    // 播放历史
    // ------------------------------------------------------------------

    /**
     * 记录一次播放。由播放服务在切歌时自动调用，用户不需要手动操作。
     *
     * <p>去重规则：同一首歌再次播放时先移除旧记录再插到最前面，
     * 所以"最近播放"里不会有重复项。
     */
    public static synchronized void addHistory(Song song) {
        if (song == null || song.bvid == null) {
            return;
        }
        history.remove(song);
        song.addedAt = System.currentTimeMillis();
        history.add(0, song);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        persist();
    }

    public static synchronized List<Song> history() {
        return new ArrayList<>(history);
    }

    public static synchronized void clearHistory() {
        history.clear();
        persist();
    }

    // ------------------------------------------------------------------
    // 搜索历史
    // ------------------------------------------------------------------

    /** 记录一次搜索关键词（去重、置顶、最多 {@value #MAX_SEARCH_HISTORY} 条）。 */
    public static synchronized void addSearchHistory(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        searchHistory.remove(keyword);
        searchHistory.addFirst(keyword);
        while (searchHistory.size() > MAX_SEARCH_HISTORY) {
            searchHistory.removeLast();
        }
        persist();
    }

    public static synchronized List<String> searchHistory() {
        return new ArrayList<>(searchHistory);
    }

    public static synchronized void clearSearchHistory() {
        searchHistory.clear();
        persist();
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private static void load() {
        if (storeFile == null || !storeFile.exists()) {
            return;
        }
        // 先把已有内容转成 JSON 字符串再解析：解析失败时（文件被写坏）
        // 会走到 catch 分支保持空数据，而不是留下一个半读的状态
        String json;
        try (Reader reader = new InputStreamReader(
                new FileInputStream(storeFile), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder(4096);
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                sb.append(buffer, 0, read);
            }
            json = sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "读取本地数据失败，将以空数据启动", e);
            return;
        }

        StoreData data = Json.fromJsonOrNull(json, STORE_TYPE);
        if (data == null) {
            Log.w(TAG, "本地数据格式已损坏，已忽略（收藏与历史会从空开始）");
            return;
        }
        favorites = data.favorites != null ? data.favorites : new ArrayList<>();
        history = data.history != null ? data.history : new ArrayList<>();
        searchHistory = new ArrayDeque<>(
                data.searchHistory != null ? data.searchHistory : new ArrayList<>());
    }

    /**
     * 把当前内存状态排进后台写盘队列。
     *
     * <p>注意这里是"每次修改都全量写"。三个列表最多几百条，
     * 序列化一次不到几毫秒，比维护"哪条脏了"的增量逻辑简单得多。
     */
    private static void persist() {
        if (storeFile == null) {
            return;
        }
        // 在锁内做快照，保证后台线程看到的是自洽的一份数据
        final String json = Json.toJson(
                new StoreData(favorites, history, new ArrayList<>(searchHistory)));
        AppExecutors.store().execute(() -> writeAtomically(json));
    }

    /** 原子写：先写临时文件，再整体替换，避免半截文件。 */
    private static void writeAtomically(String json) {
        File temp = new File(storeFile.getParentFile(), storeFile.getName() + TEMP_SUFFIX);
        try (Writer writer = new OutputStreamWriter(
                new java.io.FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write(json);
            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入本地数据失败", e);
            return;
        }
        try {
            // REPLACE_EXISTING：目标已存在时直接覆盖（File.renameTo 在 Windows 上会失败）
            Files.move(temp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.w(TAG, "替换本地数据文件失败", e);
            temp.delete();
        }
    }

    /** 磁盘上的 JSON 结构。字段名就是 JSON 的键名，改动它等于改动存储格式。 */
    static class StoreData {

        List<Song> favorites;
        List<Song> history;
        List<String> searchHistory;

        /** Gson 反序列化需要无参构造器。 */
        StoreData() {
        }

        StoreData(List<Song> favorites, List<Song> history, List<String> searchHistory) {
            this.favorites = favorites;
            this.history = history;
            this.searchHistory = searchHistory;
        }
    }
}

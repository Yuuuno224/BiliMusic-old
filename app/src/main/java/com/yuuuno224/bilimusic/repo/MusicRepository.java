package com.yuuuno224.bilimusic.repo;

import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.FavoriteFolder;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.api.FavoriteApi;
import com.yuuuno224.bilimusic.net.api.SearchApi;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 界面与网络之间的中间层。
 *
 * <h3>它到底解决什么问题</h3>
 * 界面代码有两个不能违反的约束：不能在主线程发网络请求，也不能在子线程改界面。
 * 如果让每个 Fragment 自己处理这两件事，很快就会到处是
 * {@code new Thread} + {@code runOnUiThread}，且每个页面的错误处理都不一样。
 *
 * <p>所以本类把"切线程 + 回调"这件事收敛成一个模式：
 * <pre>
 * AppExecutors.io().execute(() -&gt; {          // 1. 切到后台线程
 *     T result = 调接口();                    //    同步阻塞的网络请求放这里
 *     AppExecutors.runOnMain(() -&gt; ...);     // 2. 切回主线程回调界面
 * });
 * </pre>
 * 每个公开方法都只是这个模板的填空，写法完全一致。
 *
 * <h3>为什么所有请求共用一个单线程池</h3>
 * B 站有风控：短时间内并发太多请求会被判为异常，返回 412。
 * 串行执行虽然慢一点，但稳定得多。真正的并行只发生在播放队列解析上，
 * 那是 {@code player} 层自己的事（见 {@code core.AppExecutors#resolve()}）。
 *
 * <h3>方法约定</h3>
 * 所有方法都是"发射后不管"：立刻返回，结果通过 {@link Callback} 回主线程。
 * 回调可能在任何一次页面切换之后才到达，所以界面里必须判断
 * {@code isAdded()} / {@code isFinishing()} 再用结果。
 */
public final class MusicRepository {

    /** 统一的异步结果回调，<b>两个方法都在主线程被调用</b>。 */
    public interface Callback<T> {

        void onSuccess(T result);

        /** @param message 已经是可以直接展示给用户的文案 */
        void onError(String message);
    }

    private MusicRepository() {
    }

    /** 关键词搜索（第 1 页，每页 20 条）。 */
    public static void search(String keyword, Callback<List<Song>> callback) {
        run("搜索", () -> SearchApi.search(keyword, 1), callback);
    }

    /** 搜索首页的热门搜索词。 */
    public static void hotWords(Callback<List<String>> callback) {
        run("获取热门搜索", SearchApi::hotWords, callback);
    }

    /** 搜索首页的音乐区排行榜。 */
    public static void musicRanking(Callback<List<Song>> callback) {
        run("获取排行榜", SearchApi::musicRanking, callback);
    }

    /**
     * 某用户的收藏夹列表。
     *
     * @param mid 用户 uid；为 0 时会直接报错，调用方应先确保已登录并拿到用户信息
     */
    public static void favoriteFolders(long mid, Callback<List<FavoriteFolder>> callback) {
        if (mid <= 0) {
            callback.onError("未获取到用户信息，请重新登录");
            return;
        }
        run("获取收藏夹", () -> FavoriteApi.folders(mid), callback);
    }

    /** 某个收藏夹里的全部视频（内部会自动翻页，见 {@code FavoriteApi#allResources}）。 */
    public static void favoriteResources(long mediaId, Callback<List<Song>> callback) {
        run("获取收藏内容", () -> FavoriteApi.allResources(mediaId), callback);
    }

    /**
     * 所有方法的公共骨架：后台执行 → 主线程回调。
     *
     * <p>用 {@link Callable} 而不是 {@code Runnable}，是为了能带回结果；
     * 用 {@code Exception} 统一兜住 IO 异常和解析异常，界面永远只需要处理一句错误文案。
     *
     * @param errorLabel 出错且异常没带文案时的兜底提示，例如"搜索"
     */
    private static <T> void run(String errorLabel, Callable<T> task, Callback<T> callback) {
        AppExecutors.io().execute(() -> {
            try {
                T result = task.call();
                AppExecutors.runOnMain(() -> callback.onSuccess(result));
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.isEmpty()) {
                    message = errorLabel + "失败，请检查网络后重试";
                }
                final String text = message;
                AppExecutors.runOnMain(() -> callback.onError(text));
            }
        });
    }
}

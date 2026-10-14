package com.yuuuno224.bilimusic.player;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;

import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * 界面侧的播放控制入口（单例）。
 *
 * <h3>它和 PlaybackService 的关系</h3>
 * 界面不直接碰 {@link PlaybackService}，而是持有一个 {@link MediaController}。
 * 这个 Controller 是 Media3 提供的"遥控器"：调用 {@code controller.play()} 时，
 * 命令会跨进程（同进程时是跨线程）送到 Service 里的 ExoPlayer 上执行。
 *
 * <p>好处是界面与播放器彻底解耦。代价是 Controller 的建立是<b>异步</b>的
 * （要等服务起来并握手），所以本类有一个"连接中"状态：连接没完成时发起的调用
 * 会先排队（{@link #PENDING_CALLBACKS}），连上之后统一补发。
 *
 * <h3>三步用法</h3>
 * <pre>
 * // 1. Activity/Fragment 创建时：建立连接并注册监听
 * PlayerConnection.acquire(this);
 * PlayerConnection.addListener(this);
 *
 * // 2. 播放器状态变化时：onPlayerChanged() 会被回调（保证在主线程）
 * &#64;Override public void onPlayerChanged() { ... 刷新界面 ... }
 *
 * // 3. Activity/Fragment 销毁时：一定要移除监听，否则会内存泄漏
 * PlayerConnection.removeListener(this);
 * </pre>
 *
 * <h3>关于线程（这里是曾经真实踩过的坑）</h3>
 * MediaController 的连接回调默认在<b>建立连接的那个线程</b>上执行，
 * 而那不一定是主线程。如果直接在那里通知界面，就会在子线程里改 View 而崩溃。
 * 因此 {@link #connect} 强制把回调投递到主线程，{@link #notifyListeners} 也再兜一层，
 * 并且用 {@link #notifyScheduled} 把一帧内的多次变化合并成一次刷新。
 */
public final class PlayerConnection {

    /** 播放器状态变化监听。回调保证在主线程。 */
    public interface Listener {
        void onPlayerChanged();
    }

    private static final Object LOCK = new Object();

    private static MediaController controller;
    private static boolean connecting;
    private static boolean notifyScheduled;

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MediaController>> PENDING_CALLBACKS =
            new CopyOnWriteArrayList<>();

    /**
     * 转发播放器事件。
     * <p>只监听界面真正关心的几类，且统一走 {@link #scheduleNotify()} 合并刷新。
     */
    private static final Player.Listener NOTIFY = new Player.Listener() {

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            scheduleNotify();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            scheduleNotify();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            scheduleNotify();
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            scheduleNotify();
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            // 队列被增删（例如播放队列弹窗里删了一首）也会走到这里
            scheduleNotify();
        }
    };

    private PlayerConnection() {
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /**
     * 建立连接（幂等，可重复调用）。
     *
     * @param onReady 连接就绪后回调，<b>保证在主线程</b>；连接失败时回调 null。
     *                不需要回调时传 null，例如只想确保连接已建立。
     */
    public static void connect(Context context, @Nullable Consumer<MediaController> onReady) {
        MediaController ready;
        synchronized (LOCK) {
            ready = controller;
            if (ready == null) {
                if (onReady != null) {
                    PENDING_CALLBACKS.add(onReady);
                }
                if (connecting) {
                    // 已经有人在连了，排进队列等结果即可
                    return;
                }
                connecting = true;
            }
        }

        if (ready != null) {
            if (onReady != null) {
                onReady.accept(ready);
            }
            return;
        }

        SessionToken token = new SessionToken(
                context, new ComponentName(context, PlaybackService.class));
        final ListenableFuture<MediaController> future =
                new MediaController.Builder(context, token).buildAsync();

        // 第三个参数是 Executor：用主线程 Handler 执行，从根上避免"回调跑在子线程"
        future.addListener(() -> onControllerCreated(future), AppExecutors.main()::post);
    }

    /**
     * 界面进来时调用一次（配对的 {@link #acquire} 没有硬性计数要求，
     * 当前实现只是语义化的"我来了"，真正的释放由 {@link #release()} 显式触发）。
     */
    public static void acquire(Context context) {
        connect(context, null);
    }

    private static void onControllerCreated(ListenableFuture<MediaController> future) {
        MediaController created = null;
        try {
            created = future.get();
        } catch (Exception ignored) {
            // 服务没起来或握手失败，下面按失败处理
        }

        List<Consumer<MediaController>> callbacks;
        MediaController result;
        synchronized (LOCK) {
            connecting = false;
            callbacks = new ArrayList<>(PENDING_CALLBACKS);
            PENDING_CALLBACKS.clear();

            if (created == null) {
                result = null;
            } else if (controller == null) {
                controller = created;
                controller.addListener(NOTIFY);
                result = controller;
            } else {
                // 并发情况下已经有一个可用的 Controller 了，多余的这个要释放掉
                if (controller != created) {
                    created.release();
                }
                result = controller;
            }
        }

        for (Consumer<MediaController> callback : callbacks) {
            callback.accept(result);
        }
        if (result != null) {
            scheduleNotify();
        }
    }

    /** 当前的遥控器，未连接时为 null。调用方必须做 null 判断。 */
    @Nullable
    public static MediaController get() {
        synchronized (LOCK) {
            return controller;
        }
    }

    /**
     * 断开与播放服务的连接。
     *
     * <p><b>一般不需要调用</b>：让 Controller 与进程同生命周期是刻意的设计，
     * 这样界面来回切换时不用反复握手。只在确定要彻底退出播放时（例如"退出 App"）才用，
     * 调用后 {@link #get()} 会返回 null，下次 {@link #connect} 会重新建立。
     */
    public static void release() {
        MediaController toRelease;
        synchronized (LOCK) {
            toRelease = controller;
            controller = null;
            connecting = false;
            PENDING_CALLBACKS.clear();
        }
        if (toRelease != null) {
            toRelease.release();
        }
    }

    // ------------------------------------------------------------------
    // 监听器
    // ------------------------------------------------------------------

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** 必须在 Activity/Fragment 销毁时调用，否则监听器会一直持有已销毁的界面。 */
    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * 合并刷新：一帧内播放器可能连续回调好几次（状态变 → 播放中变 → 切歌），
     * 每次都刷界面是浪费，这里保证同一帧只通知一次。
     */
    private static void scheduleNotify() {
        synchronized (LOCK) {
            if (notifyScheduled) {
                return;
            }
            notifyScheduled = true;
        }
        AppExecutors.postOnMain(() -> {
            synchronized (LOCK) {
                notifyScheduled = false;
            }
            // CopyOnWriteArrayList：遍历过程中有新监听器注册也不会抛并发修改异常
            for (Listener listener : LISTENERS) {
                listener.onPlayerChanged();
            }
        });
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    /**
     * 播放一整条队列，从第 {@code index} 首开始。
     *
     * <p>这是列表里点歌走的路：把所有歌一次性交给 Service，
     * 由 Service 解析地址后填进播放器，界面立刻返回不阻塞。
     */
    public static void playQueue(Context context, List<Song> songs, int index) {
        sendCommand(context, PlaybackService.CMD_PLAY_QUEUE,
                PlaybackService.queueBundle(songs, index));
    }

    /** 把一首歌插到当前播放的下一首。 */
    public static void playNext(Context context, Song song) {
        sendCommand(context, PlaybackService.CMD_PLAY_NEXT,
                PlaybackService.queueBundle(Collections.singletonList(song), -1));
    }

    /** 把一首歌追加到队列末尾。 */
    public static void addToQueue(Context context, Song song) {
        sendCommand(context, PlaybackService.CMD_ADD_QUEUE,
                PlaybackService.queueBundle(Collections.singletonList(song), -1));
    }

    /**
     * 发送自定义命令。连接未就绪时会先建连，建好之后再发，
     * 所以用户在冷启动瞬间点歌也不会丢命令。
     */
    private static void sendCommand(Context context, String action, Bundle args) {
        connect(context, controller -> {
            if (controller != null) {
                controller.sendCustomCommand(
                        new SessionCommand(action, Bundle.EMPTY), args);
            }
        });
    }

    /** 播放 / 暂停切换。 */
    public static void toggle() {
        Player player = get();
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    /**
     * 下一首。
     * <p>已经是最后一首时，如果开了循环就回到第一首，否则什么也不做
     * （保持与用户直觉一致：顺序播放到末尾就停下）。
     */
    public static void next() {
        Player player = get();
        if (player == null) {
            return;
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
        } else if (player.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            player.seekTo(0, 0);
        }
    }

    /** 上一首。 */
    public static void prev() {
        Player player = get();
        if (player == null) {
            return;
        }
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem();
        } else if (player.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            player.seekTo(player.getMediaItemCount() - 1, 0);
        }
    }

    /** 当前正在播放的歌，没有时返回 null。 */
    @Nullable
    public static Song currentSong() {
        MediaController player = get();
        if (player == null) {
            return null;
        }
        return MediaItemFactory.songOf(player.getCurrentMediaItem());
    }

    /**
     * 当前正在播放的 bvid。
     * <p>列表做"哪一行正在播放"的高亮时只需要 bvid，不必反序列化出整个 Song，省一次 JSON 解析。
     */
    @Nullable
    public static String currentBvid() {
        MediaController player = get();
        if (player == null) {
            return null;
        }
        return MediaItemFactory.bvidOf(player.getCurrentMediaItem());
    }

    public static boolean isPlaying() {
        MediaController player = get();
        return player != null && player.isPlaying();
    }
}

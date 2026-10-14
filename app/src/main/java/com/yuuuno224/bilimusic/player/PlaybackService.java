package com.yuuuno224.bilimusic.player;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.BiliHttp;
import com.yuuuno224.bilimusic.net.Json;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.ui.player.NowPlayingActivity;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 播放服务：App 里唯一持有 ExoPlayer 的地方。
 *
 * <h3>为什么用 Service 而不是在 Activity 里放播放器</h3>
 * 音乐必须能在界面关掉后继续播、并由通知栏控制。Activity 随时会被系统销毁，
 * 而前台 Service 可以一直活着，所以播放器必须放在 Service 里。
 *
 * <h3>Media3 的两个角色</h3>
 * <ul>
 *   <li><b>服务端（本类）</b>：持有 {@link ExoPlayer} 和 {@link MediaSession}，
 *       对外暴露"可被远程控制"的播放能力，并自动生成通知栏。</li>
 *   <li><b>客户端（{@link PlayerConnection}）</b>：界面通过 MediaController 远程操纵本服务。</li>
 * </ul>
 * 两者之间不是直接调用，而是通过 MediaSession 通信。这样界面和播放器彻底解耦——
 * 界面死了播放器照样放，播放器也不必知道界面长什么样。
 *
 * <h3>为什么要有自定义命令</h3>
 * 标准 MediaController 只能塞"已经能播的 MediaItem"，而本 App 拿到的是 Song（只有 bvid），
 * 还得先异步解析音频地址。所以自定义了三条 {@code SessionCommand}：
 * <ul>
 *   <li>{@link #CMD_PLAY_QUEUE} —— 设置整条播放队列并从第 N 首开始播</li>
 *   <li>{@link #CMD_PLAY_NEXT} —— 把一首歌插到当前播放的下一首</li>
 *   <li>{@link #CMD_ADD_QUEUE} —— 把一首歌追加到队列末尾</li>
 * </ul>
 * 界面把 Song 列表序列化成 JSON 塞进 Bundle 发过来，本服务在后台解析完再填进播放器。
 *
 * <h3>队列解析策略</h3>
 * {@link #resolveQueueAndLoad} 会并行解析整条队列（{@code bili-resolve} 池，6 线程），
 * 全部就绪后一次载入。为什么要等全部就绪：只有这样通知栏的"上一首/下一首"、
 * 播放器的"列表循环"才能立刻知道队列里到底有哪些歌。
 * 代价是网络差时会等久一点，所以有 {@link #RESOLVE_TIMEOUT_SECONDS} 超时兜底——
 * 超时后只载入已经解析成功的那些，不会卡死。
 */
public class PlaybackService extends MediaSessionService {

    /** 设置播放队列：Bundle 里带 songs（JSON）与 index（从第几首开始）。 */
    public static final String CMD_PLAY_QUEUE = "bilimusic.PLAY_QUEUE";
    /** 下一首播放：插到当前曲目后面，不打断当前播放。 */
    public static final String CMD_PLAY_NEXT = "bilimusic.PLAY_NEXT";
    /** 加入队列：追加到末尾。 */
    public static final String CMD_ADD_QUEUE = "bilimusic.ADD_QUEUE";

    private static final String KEY_SONGS = "songs";
    private static final String KEY_INDEX = "index";

    private static final Type SONG_LIST = new TypeToken<List<Song>>() { }.getType();

    /** 整条队列解析的最长等待时间。超时后只载入已解析好的部分。 */
    private static final long RESOLVE_TIMEOUT_SECONDS = 20L;

    /** 同一首歌因地址失效而重新解析的最大次数，避免死循环重试。 */
    private static final int MAX_ERROR_RETRIES = 2;

    private MediaSession session;
    private ExoPlayer player;

    /** 每首歌已经重试过几次，播放成功后会被清除。 */
    private final Map<String, Integer> retryCounts = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(createHttpFactory()))
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                        // handleAudioFocus=true：来电话 / 别的 App 抢音频焦点时自动暂停
                        true)
                // 拔耳机自动暂停
                .setHandleAudioBecomingNoisy(true)
                // 播放中持有网络唤醒锁，防止息屏后断流
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();

        player.addListener(new Player.Listener() {

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                // 三种原因都算"开始放这首歌"：
                //  AUTO            自动连播到下一首
                //  SEEK            用户手动切歌
                //  PLAYLIST_CHANGED 整条队列刚被替换（点列表里某一首开始播放就走这条）
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    Song song = MediaItemFactory.songOf(mediaItem);
                    if (song != null) {
                        MusicStore.addHistory(song);
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    // 播起来了，说明这次地址是好的，清掉重试计数
                    Song song = MediaItemFactory.songOf(player.getCurrentMediaItem());
                    if (song != null) {
                        retryCounts.remove(song.bvid);
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlaybackError();
            }
        });

        PendingIntent sessionActivity = PendingIntent.getActivity(this, 0,
                new Intent(this, NowPlayingActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(new MediaSession.Callback() {

                    @Override
                    public MediaSession.ConnectionResult onConnect(
                            MediaSession session, MediaSession.ControllerInfo controller) {
                        // 声明本服务支持的自定义命令，不声明的话客户端调用会被拒绝
                        SessionCommands commands = new SessionCommands.Builder()
                                .add(new SessionCommand(CMD_PLAY_QUEUE, Bundle.EMPTY))
                                .add(new SessionCommand(CMD_PLAY_NEXT, Bundle.EMPTY))
                                .add(new SessionCommand(CMD_ADD_QUEUE, Bundle.EMPTY))
                                .build();
                        return MediaSession.ConnectionResult.accept(
                                commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
                    }

                    @Override
                    public ListenableFuture<SessionResult> onCustomCommand(
                            MediaSession mediaSession, MediaSession.ControllerInfo controller,
                            SessionCommand customCommand, Bundle args) {
                        switch (customCommand.customAction) {
                            case CMD_PLAY_QUEUE:
                                handlePlayQueue(args);
                                break;
                            case CMD_PLAY_NEXT:
                                handleInsert(args, true);
                                break;
                            case CMD_ADD_QUEUE:
                                handleInsert(args, false);
                                break;
                            default:
                                break;
                        }
                        return Futures.immediateFuture(
                                new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                })
                .build();
    }

    /**
     * 音频流的 HTTP 配置。
     *
     * <p>音频 CDN 会校验 Referer，不带头会拿到 403，所以这里要单独配一份，
     * 不能直接复用 {@link BiliHttp}（那个是给 JSON 接口用的）。
     */
    private static DefaultHttpDataSource.Factory createHttpFactory() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", BiliHttp.REFERER);
        return new DefaultHttpDataSource.Factory()
                .setUserAgent(BiliHttp.UA)
                .setDefaultRequestProperties(headers)
                // 音频 CDN 会 302 跳到另一个域名，跨协议重定向必须允许
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000);
    }

    // ------------------------------------------------------------------
    // 自定义命令的处理
    // ------------------------------------------------------------------

    private void handlePlayQueue(Bundle args) {
        List<Song> songs = Json.fromJsonOrNull(args.getString(KEY_SONGS), SONG_LIST);
        if (songs == null || songs.isEmpty()) {
            return;
        }
        resolveQueueAndLoad(songs, args.getInt(KEY_INDEX, 0));
    }

    /**
     * 处理"下一首播放" / "加入队列"。
     *
     * <p>与设置队列不同，这里不打断当前播放：先解析出 MediaItem，再插入到指定位置。
     * 如果播放器当前是空闲的（比如刚打开 App 就直接点了"下一首播放"），
     * 插入后会自动开始播放。
     */
    private void handleInsert(Bundle args, boolean asNext) {
        List<Song> songs = Json.fromJsonOrNull(args.getString(KEY_SONGS), SONG_LIST);
        if (songs == null || songs.isEmpty() || player == null) {
            return;
        }
        for (final Song song : songs) {
            AppExecutors.resolve().execute(() -> {
                try {
                    final MediaItem item = MediaItemFactory.create(song);
                    AppExecutors.runOnMain(() -> {
                        if (player == null) {
                            return;
                        }
                        boolean idle = player.getPlaybackState() == Player.STATE_IDLE
                                || player.getCurrentMediaItemIndex() < 0;
                        if (asNext && player.getCurrentMediaItemIndex() >= 0) {
                            player.addMediaItem(player.getCurrentMediaItemIndex() + 1, item);
                        } else {
                            player.addMediaItem(item);
                        }
                        if (idle) {
                            player.prepare();
                            player.play();
                        }
                    });
                } catch (Exception ignored) {
                    // 单曲解析失败就丢掉，不影响已经在放的内容
                }
            });
        }
    }

    /**
     * 解析整条队列并开始播放。
     *
     * <p>流程：停掉当前播放 → 并行解析每首歌 → 按原顺序拼成列表（失败的跳过）
     * → 回到主线程一次性交给播放器。
     *
     * <p>注意开始播放的位置要做一次映射：如果第 3 首解析失败被跳过了，
     * 原本的 index=4 就要落到新的第 3 个位置上，否则会点 A 播 B。
     */
    private void resolveQueueAndLoad(List<Song> songs, final int startIndex) {
        if (player == null) {
            return;
        }
        player.stop();
        player.clearMediaItems();

        final List<Song> queue = new ArrayList<>(songs);
        AppExecutors.resolve().execute(() -> {
            // 每首歌一个解析任务。用 invokeAll 而不是自己数 CountDownLatch：
            // 它保证返回的 Future 顺序与任务顺序一致，省掉一层下标映射的心智负担。
            List<Callable<MediaItem>> tasks = new ArrayList<>(queue.size());
            for (final Song song : queue) {
                tasks.add(() -> MediaItemFactory.create(song));
            }

            List<Future<MediaItem>> futures;
            try {
                futures = AppExecutors.resolve().invokeAll(
                        tasks, RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            List<MediaItem> items = new ArrayList<>();
            int mappedStart = 0;
            for (int i = 0; i < futures.size(); i++) {
                MediaItem item = null;
                try {
                    // invokeAll 返回时所有任务都已结束（成功/失败/被取消），这里不会阻塞
                    item = futures.get(i).get();
                } catch (Exception ignored) {
                    // 失败或超时被取消的曲目直接跳过
                }
                if (item != null) {
                    if (i < startIndex) {
                        // 这一首排在目标之前且解析成功，起始位置要往后挪一格
                        mappedStart++;
                    }
                    items.add(item);
                }
            }

            if (items.isEmpty()) {
                return;
            }
            final List<MediaItem> finalItems = items;
            final int finalStart = Math.min(mappedStart, items.size() - 1);
            AppExecutors.runOnMain(() -> {
                if (player == null) {
                    return;
                }
                player.setMediaItems(finalItems, finalStart, 0);
                player.prepare();
                player.play();
            });
        });
    }

    /**
     * 播放出错时的自愈：重新解析当前这首歌的地址再试一次。
     *
     * <p>最常见的错误是"音频地址过期"（B 站 CDN 链接有时效，长时间挂后台再回来就会 403）。
     * 这时用同一个 bvid 重新请求 playurl 就能拿到新地址，用户几乎无感。
     *
     * <p>重试有次数上限（{@link #MAX_ERROR_RETRIES}）：如果视频本身已下架，
     * 重试多少次都没用，必须停下来，否则会陷入死循环。
     */
    private void handlePlaybackError() {
        if (player == null) {
            return;
        }
        final Song song = MediaItemFactory.songOf(player.getCurrentMediaItem());
        if (song == null || song.bvid == null) {
            return;
        }
        Integer attempts = retryCounts.get(song.bvid);
        int used = attempts == null ? 0 : attempts;
        if (used >= MAX_ERROR_RETRIES) {
            retryCounts.remove(song.bvid);
            return;
        }
        retryCounts.put(song.bvid, used + 1);

        final int index = player.getCurrentMediaItemIndex();
        final long positionMs = Math.max(0L, player.getCurrentPosition());

        AppExecutors.resolve().execute(() -> {
            try {
                final MediaItem fresh = MediaItemFactory.create(song);
                AppExecutors.runOnMain(() -> {
                    if (player == null || index < 0 || index >= player.getMediaItemCount()) {
                        return;
                    }
                    player.replaceMediaItem(index, fresh);
                    player.seekTo(index, positionMs);
                    player.prepare();
                    player.play();
                });
            } catch (Exception ignored) {
                // 重新解析也失败：放弃这一首，让播放器自己处理（通常是跳到下一首或停止）
            }
        });
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /**
     * 用户从最近任务里划掉 App 时的处理。
     *
     * <p>没在播放就顺手关掉服务，避免留一个空的前台服务占内存；
     * 正在播放则继续（音乐 App 的常规行为）。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Player current = session == null ? null : session.getPlayer();
        if (current == null || !current.getPlayWhenReady() || current.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        // 释放顺序：先断开会话再销毁播放器，避免通知栏回调打到已释放的播放器上
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /**
     * 把一批歌曲打包成自定义命令的参数。
     * <p>Song 列表不能直接放进 Bundle（要么实现 Parcelable，要么序列化），
     * 这里统一用 JSON 字符串传递，代价是一次序列化，换来两边都不用维护 Parcelable。
     */
    public static Bundle queueBundle(List<Song> songs, int index) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_SONGS,
                Json.toJson(songs == null ? new ArrayList<Song>() : songs));
        bundle.putInt(KEY_INDEX, index);
        return bundle;
    }
}

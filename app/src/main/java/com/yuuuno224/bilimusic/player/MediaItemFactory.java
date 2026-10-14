package com.yuuuno224.bilimusic.player;

import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.yuuuno224.bilimusic.model.AudioStream;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.net.BiliException;
import com.yuuuno224.bilimusic.net.Json;
import com.yuuuno224.bilimusic.net.api.PlaybackApi;

import java.io.IOException;

/**
 * 把 App 的 {@link Song} 变成 ExoPlayer 能吃的 {@link MediaItem}。
 *
 * <h3>为什么需要中间这一层</h3>
 * {@link MediaItem} 只认一个"能直接播的 URI"，而 {@link Song} 里只有 bvid——
 * 中间隔着两次网络请求（取 cid、取音频地址）。这个类就负责补上这段转换，
 * 让 {@code PlaybackService} 只关心"拿到 MediaItem 就塞给播放器"。
 *
 * <h3>业务数据怎么跟着 MediaItem 走</h3>
 * ExoPlayer 的队列里只有 MediaItem，界面想显示"当前播放的是哪首歌"就必须能反向还原出 Song。
 * 做法是把 Song 序列化成 JSON，塞进 {@link MediaMetadata} 的 extras（一个 Bundle）里，
 * 取的时候用 {@link #songOf(MediaItem)} 反序列化。这是 Media3 下携带业务数据的惯用做法。
 *
 * <p>本类所有方法都是<b>同步阻塞</b>的，必须在后台线程调用（见 {@code AppExecutors#resolve()}）。
 */
public final class MediaItemFactory {

    /** 存进 extras 的键名。 */
    private static final String EXTRA_SONG = "song";

    private MediaItemFactory() {
    }

    /**
     * 解析出可播放的 {@link MediaItem}。
     *
     * <p>顺带会补全传入 Song 上缺的字段（cid、时长），因为这两个值解析时反正要拿到，
     * 补上之后同一首歌再播放就不用重复请求了。
     *
     * @throws BiliException 视频不存在 / 已下架 / 拿不到音频地址
     */
    public static MediaItem create(Song song) throws IOException {
        if (song == null || song.bvid == null || song.bvid.isEmpty()) {
            throw new BiliException("歌曲信息不完整，无法播放");
        }

        // 搜索来的 Song 没有 cid，需要先查一次视频详情
        if (song.cid <= 0) {
            PlaybackApi.VideoMeta meta = PlaybackApi.videoMeta(song.bvid);
            song.cid = meta.cid;
            if (song.durationSec <= 0) {
                song.durationSec = meta.durationSeconds;
            }
        }

        AudioStream stream = PlaybackApi.bestAudio(song.bvid, song.cid);
        if (stream.url == null || stream.url.isEmpty()) {
            throw new BiliException("未取到音频地址：" + song.title);
        }

        Bundle extras = new Bundle();
        extras.putString(EXTRA_SONG, Json.toJson(song));

        String cover = song.coverLarge();
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.up)
                // 没有封面时传 null，而不是空字符串——空字符串会让通知栏去请求一个非法地址
                .setArtworkUri(cover.isEmpty() ? null : Uri.parse(cover))
                .setExtras(extras)
                .build();

        return new MediaItem.Builder()
                // mediaId 用 bvid，方便按"哪首歌"去重与查找
                .setMediaId(song.bvid)
                .setUri(stream.url)
                .setMimeType(stream.mimeType())
                .setMediaMetadata(metadata)
                .build();
    }

    /**
     * 从播放器的 MediaItem 还原出 Song。
     *
     * @return 不是本 App 构建的 MediaItem 时返回 null
     */
    public static Song songOf(MediaItem item) {
        if (item == null || item.mediaMetadata == null || item.mediaMetadata.extras == null) {
            return null;
        }
        String json = item.mediaMetadata.extras.getString(EXTRA_SONG);
        return Json.fromJsonOrNull(json, Song.class);
    }

    /** 取 MediaItem 对应的 bvid，取不到时返回 null。 */
    public static String bvidOf(MediaItem item) {
        Song song = songOf(item);
        return song == null ? null : song.bvid;
    }
}

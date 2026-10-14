package com.yuuuno224.bilimusic.net.api;

import com.google.gson.reflect.TypeToken;
import com.yuuuno224.bilimusic.model.AudioStream;
import com.yuuuno224.bilimusic.net.BiliException;
import com.yuuuno224.bilimusic.net.BiliSession;
import com.yuuuno224.bilimusic.net.dto.ApiResponse;
import com.yuuuno224.bilimusic.net.dto.PlayUrlDto;
import com.yuuuno224.bilimusic.net.dto.VideoDetailDto;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把"视频"变成"能播的音频地址"。
 *
 * <h3>为什么需要两步</h3>
 * <ol>
 *   <li>搜索接口只给 bvid，但取音频必须带 cid（分 P 编号）→ 先调
 *       {@link #videoMeta} 取 cid；</li>
 *   <li>再调 playurl 取音频流 → {@link #bestAudio}。</li>
 * </ol>
 * 如果 Song 里已经有 cid（例如来自收藏夹），第 1 步可以跳过，
 * 这就是 {@code player.MediaItemFactory} 里那个 {@code if (song.cid <= 0)} 判断的意义。
 *
 * <h3>关于播放地址会过期</h3>
 * playurl 返回的地址带签名和时效，几小时后失效。所以本类的方法可以被反复调用，
 * 播放出错时重新调用一次即可拿到新地址。
 */
public final class PlaybackApi {

    private static final String URL_VIDEO_DETAIL =
            "https://api.bilibili.com/x/web-interface/view";
    private static final String URL_PLAY_URL =
            "https://api.bilibili.com/x/player/playurl";

    /**
     * {@code fnval=16} 索要 DASH 格式（音视频分离）。
     * 我们只要音频，DASH 正好能直接拿到纯音频流。
     */
    private static final String FNVAL_DASH = "16";

    private static final Type TYPE_VIDEO_DETAIL =
            new TypeToken<ApiResponse<VideoDetailDto>>() { }.getType();
    private static final Type TYPE_PLAY_URL =
            new TypeToken<ApiResponse<PlayUrlDto>>() { }.getType();

    private PlaybackApi() {
    }

    /** 视频详情里我们真正需要的两个值。 */
    public static final class VideoMeta {
        public final long cid;
        public final long durationSeconds;

        VideoMeta(long cid, long durationSeconds) {
            this.cid = cid;
            this.durationSeconds = durationSeconds;
        }
    }

    /**
     * 取视频的 cid 与时长。搜索来的 Song 没有 cid，播放前必须调一次。
     *
     * @throws BiliException 视频不存在、已下架、或需要登录才能观看时抛出
     */
    public static VideoMeta videoMeta(String bvid) throws IOException {
        VideoDetailDto data = BiliSession.get(
                URL_VIDEO_DETAIL + "?bvid=" + bvid, TYPE_VIDEO_DETAIL, "获取视频信息");
        if (data == null) {
            throw new BiliException("视频信息为空：" + bvid);
        }
        return new VideoMeta(data.cid, data.duration);
    }

    /**
     * 取该视频音质最好的一条音频流。
     *
     * <p>选择顺序（与官方网页播放器一致）：
     * <ol>
     *   <li>{@code dash.audio} 中 id 最大者 —— id 越大码率越高；</li>
     *   <li>{@code dash.flac.audio} —— 无损，通常需要大会员；</li>
     *   <li>{@code durl[0].url} —— 老式整段流，音质最差，仅兜底。</li>
     * </ol>
     *
     * <p><b>关于是否要 WBI 签名</b>：本接口目前<b>不签名</b>也能正常返回，
     * 这是经过验证的现状，因此这里刻意保持不签名——盲目加上签名一旦服务端
     * 校验的参数集合与我们对不上，会直接把播放功能打死。search 接口则是必须签名的，
     * 两种情况对比着看能明白"签名与否取决于接口，不是越全越好"。
     *
     * @throws BiliException 三种来源都没有可用地址时抛出
     */
    public static AudioStream bestAudio(String bvid, long cid) throws IOException {
        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));
        params.put("fnval", FNVAL_DASH);
        params.put("fourk", "1");
        params.put("platform", "pc");
        // qn=0 表示"由服务端按当前账号权限给最好的"，登录后能拿到更高码率
        params.put("qn", "0");

        PlayUrlDto data = BiliSession.getWithParams(
                URL_PLAY_URL, params, TYPE_PLAY_URL, "获取播放地址");
        return pickBest(data);
    }

    /** 从 playurl 的响应里挑出最优音频流。抽成独立方法便于单独测试。 */
    static AudioStream pickBest(PlayUrlDto play) throws BiliException {
        if (play == null) {
            throw new BiliException("播放地址响应为空");
        }

        // 第 1 优先：DASH 音频里 id 最大、且确实带地址的那一条
        PlayUrlDto.AudioStreamDto best = null;
        String bestUrl = null;
        if (play.dash != null && play.dash.audio != null) {
            for (PlayUrlDto.AudioStreamDto candidate : play.dash.audio) {
                String url = firstUsableUrl(candidate);
                if (url == null) {
                    continue;
                }
                if (best == null || candidate.id > best.id) {
                    best = candidate;
                    bestUrl = url;
                }
            }
        }

        // 第 2 优先：FLAC 无损
        if (best == null && play.dash != null && play.dash.flac != null) {
            String url = firstUsableUrl(play.dash.flac.audio);
            if (url != null) {
                best = play.dash.flac.audio;
                bestUrl = url;
            }
        }

        if (best != null) {
            return toModel(best, bestUrl);
        }

        // 第 3 优先：老接口的整段流，仅兜底
        if (play.durl != null && !play.durl.isEmpty()
                && play.durl.get(0) != null && play.durl.get(0).url != null) {
            AudioStream stream = new AudioStream();
            stream.qualityId = 0;
            stream.url = play.durl.get(0).url;
            stream.flac = false;
            return stream;
        }

        throw new BiliException("该视频没有可用的音频流（可能是付费课程或已下架）");
    }

    /**
     * 取一条流的可用地址：主地址优先，主地址缺失时退回第一个备用地址。
     * <p>B 站偶尔只下发 {@code backup_url} 而不给 {@code base_url}，这时备用地址就是唯一出路。
     */
    private static String firstUsableUrl(PlayUrlDto.AudioStreamDto dto) {
        if (dto == null) {
            return null;
        }
        String url = dto.primaryUrl();
        if (url != null && !url.isEmpty()) {
            return url;
        }
        for (String backup : dto.backupUrls()) {
            if (backup != null && !backup.isEmpty()) {
                return backup;
            }
        }
        return null;
    }

    private static AudioStream toModel(PlayUrlDto.AudioStreamDto dto, String url) {
        AudioStream stream = new AudioStream();
        stream.qualityId = dto.id;
        stream.url = url;
        stream.bandwidth = dto.bandwidth;
        stream.flac = dto.codecs != null && dto.codecs.toLowerCase().contains("flac");
        return stream;
    }
}

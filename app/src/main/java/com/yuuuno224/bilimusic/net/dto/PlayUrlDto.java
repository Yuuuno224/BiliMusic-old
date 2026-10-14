package com.yuuuno224.bilimusic.net.dto;

import java.util.List;

/**
 * 播放地址（{@code /x/player/playurl?fnval=16}）。
 *
 * <p>{@code fnval=16} 表示要 DASH 格式。DASH 会把音视频拆成两条独立的流，
 * 音频流在 {@code dash.audio} 数组里，正好符合"只当音乐听"的定位。
 *
 * <p>优先级（见 {@link com.yuuuno224.bilimusic.net.api.PlaybackApi#bestAudio}）：
 * <ol>
 *   <li>{@code dash.audio} 里 id 最大的一条 —— id 越大音质越高（30216/30232/30280…）；</li>
 *   <li>{@code dash.flac.audio} —— Hi-Res 无损，需要大会员；</li>
 *   <li>{@code durl[0].url} —— 老接口的整段流，音质最差，仅作兜底。</li>
 * </ol>
 */
public class PlayUrlDto {

    public int quality;
    public Dash dash;
    /** 兜底用的整段流（非 DASH 时才有）。 */
    public List<DurlItem> durl;

    public static class Dash {
        public long duration;
        public List<AudioStreamDto> audio;
        public Flac flac;
    }

    public static class Flac {
        public AudioStreamDto audio;
    }

    /**
     * 一条音频流。
     *
     * <p>B 站不同版本接口返回的字段命名不统一，同一个含义可能叫 {@code baseUrl} 也可能叫
     * {@code base_url}，所以两套都留着，取的时候用 {@link #primaryUrl()} 兜底。
     */
    public static class AudioStreamDto {

        /** 音质编号，越大越好。 */
        public int id;
        public String baseUrl;
        public String base_url;
        public List<String> backupUrl;
        public List<String> backup_url;
        /** 码率，单位 bps。 */
        public long bandwidth;
        /** 编码格式，含 "flac" 说明是无损。 */
        public String codecs;

        /** 主播放地址，两套命名都为空时返回 null。 */
        public String primaryUrl() {
            if (baseUrl != null && !baseUrl.isEmpty()) {
                return baseUrl;
            }
            return base_url != null && !base_url.isEmpty() ? base_url : null;
        }

        /** 备用地址列表（主地址 403 时可以用）。 */
        public List<String> backupUrls() {
            if (backupUrl != null && !backupUrl.isEmpty()) {
                return backupUrl;
            }
            return backup_url == null ? java.util.Collections.emptyList() : backup_url;
        }
    }

    /** 非 DASH 时的整段流条目。 */
    public static class DurlItem {
        public String url;
        public long length;
        public long size;
    }
}

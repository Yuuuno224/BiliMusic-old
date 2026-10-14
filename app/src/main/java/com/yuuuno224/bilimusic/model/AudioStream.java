package com.yuuuno224.bilimusic.model;

/**
 * 一条可直接播放的音频流。
 *
 * <p>它是"bvid + cid → 真实可播放地址"这一步的产物。
 *
 * <p><b>这个地址是带时效的</b>：B 站 CDN 的链接通常几小时后就会 403。
 * 所以它只适合当场用掉，绝不能存进本地文件（存了第二天也放不了）。
 * 播放中途报错时，正确的做法是拿 bvid 重新解析一次拿新地址——
 * 见 {@code player.PlaybackService#handlePlaybackError}。
 */
public class AudioStream {

    /** 音质编号，越大越好（例如 30280 是 192K）。 */
    public int qualityId;

    /** 真实播放地址。构造时已保证非空。 */
    public String url;

    /** 码率，单位 bps。 */
    public long bandwidth;

    /** 是否为无损（FLAC）。ExoPlayer 需要据此选择解码方式。 */
    public boolean flac;

    /** 供 ExoPlayer 使用的 MIME 类型。 */
    public String mimeType() {
        return flac ? "audio/flac" : "audio/mp4";
    }
}

package com.yuuuno224.bilimusic.model;

/**
 * 一首"歌"，也就是一个 B 站视频。
 *
 * <p>这是全 App 最核心的数据模型：搜索结果、排行榜、收藏、播放历史、播放队列里流动的
 * 全都是它。所以它放在 {@code model} 包，不依赖任何其他层。
 *
 * <p>{@link #bvid} 是 B 站视频的唯一编号（形如 {@code BV1xx411c7mD}），
 * 也是本类的"主键"：{@link #equals} / {@link #hashCode} 都只看它，
 * 因此"同一首歌"在收藏和历史里去重时不会重复。
 *
 * <p>{@link #cid} 比较特殊：搜索接口<b>不返回</b> cid，而取音频地址又必须用它，
 * 所以 cid 为 0 表示"还没解析过"，播放前需要再请求一次视频详情补上。
 * 收藏夹接口会直接带 cid，因此从收藏夹点播可以少一次网络请求。
 */
public class Song {

    /** B 站视频编号，形如 BV1xx411c7mD。同时充当这首歌的唯一标识。 */
    public String bvid;

    /** 视频分 P 内部编号。0 表示尚未解析。 */
    public long cid;

    public String title;

    /** UP 主名字，界面上作为"歌手"显示。 */
    public String up;

    /** 封面地址，可能是协议相对形式（以 // 开头）。 */
    public String cover;

    /** 时长，单位秒。0 表示未知。 */
    public long durationSec;

    /** 加入收藏/历史的时间戳，用于排序展示。 */
    public long addedAt;

    /** Gson 反序列化需要无参构造器。 */
    public Song() {
    }

    public Song(String bvid, String title, String up, String cover, long durationSec) {
        this.bvid = bvid;
        this.title = title;
        this.up = up;
        this.cover = cover;
        this.durationSec = durationSec;
    }

    /** 把协议相对地址补全成 https 绝对地址。 */
    public String coverUrl() {
        if (cover == null || cover.isEmpty()) {
            return "";
        }
        if (cover.startsWith("//")) {
            return "https:" + cover;
        }
        return cover;
    }

    /**
     * 列表用的小图。
     *
     * <p>B 站图片 CDN 支持在地址后面拼 {@code @宽w_高h.ext} 让它按需裁剪，
     * 列表里 44dp 的缩略图没必要下载原图，能省很多流量与内存。
     */
    public String coverSmall() {
        return sizedCover("@320w_200h.webp");
    }

    /** 播放页用的大图。 */
    public String coverLarge() {
        return sizedCover("@672w_378h.webp");
    }

    /** 时长文本，未知时显示 {@code --:--}。 */
    public String durationText() {
        return durationSec <= 0 ? "--:--" : durationText(durationSec);
    }

    /** 秒数转 {@code m:ss} 文本，超过一小时会变成 {@code h:mm:ss} 形式的前缀。 */
    public static String durationText(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private String sizedCover(String suffix) {
        String url = coverUrl();
        if (url.isEmpty()) {
            // 没有封面时返回空串，调用方（ImageLoader）会走"没有图片"的分支，
            // 而不是去请求一个形如 "@320w_200h.webp" 的非法地址
            return "";
        }
        // 地址里已经有 "@" 说明调用方自己带了裁剪参数，不要重复拼
        return url.contains("@") ? url : url + suffix;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Song)) {
            return false;
        }
        Song that = (Song) other;
        return bvid != null && bvid.equals(that.bvid);
    }

    @Override
    public int hashCode() {
        return bvid == null ? 0 : bvid.hashCode();
    }
}

package com.yuuuno224.bilimusic.net.dto;

/**
 * 视频详情（{@code /x/web-interface/view?bvid=BVxxx}）。
 *
 * <p>播放音频需要 {@code cid}（视频分 P 的内部编号），而搜索接口不返回 cid，
 * 所以拿到 bvid 后往往还要再请求一次这里。
 *
 * <pre>
 * { "code":0, "data": { "bvid":"BV1xx", "cid":123456, "duration":245, "title":"...", "pic":"..." } }
 * </pre>
 */
public class VideoDetailDto {

    public String bvid;
    public long aid;
    /** 音频流必需。一个视频可能有多个分 P，这里只是第一个/默认分 P 的 cid。 */
    public long cid;
    public String title;
    public String pic;
    /** 总时长，单位秒。 */
    public long duration;
}

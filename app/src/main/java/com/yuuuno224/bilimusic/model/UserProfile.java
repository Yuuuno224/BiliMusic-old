package com.yuuuno224.bilimusic.model;

/**
 * 当前登录的 B 站用户。
 *
 * <p>它有两个身份：
 * <ol>
 *   <li>界面模型 —— "我的"页拿它显示头像、昵称、等级；</li>
 *   <li>缓存对象 —— 会被 Gson 序列化成 JSON 存进 SharedPreferences
 *       （{@code bili_auth} 的 {@code me} 键），这样冷启动时不用等网络就能显示上次的信息。</li>
 * </ol>
 *
 * <p>注意它和 {@code net.dto.NavDto} 的区别：{@code NavDto} 严格对应接口返回的嵌套结构
 * （{@code level_info.current_level}），而这里被拍平成了 {@link #level}，界面用起来更直接。
 * 两者之间的转换由 {@code net.api.LoginApi} 负责——
 * <b>本类刻意不提供 {@code from(NavDto)} 这样的方法</b>，
 * 否则 {@code model} 就得反过来依赖 {@code net.dto}，
 * 而"model 不依赖任何层"是本项目最重要的一条分层规则。
 */
public class UserProfile {

    /** B 站是否认为已登录。Cookie 存在但这里为 false，说明登录已过期。 */
    public boolean isLogin;

    public long mid;
    public String uname;
    public String face;

    /** 等级，0~6。 */
    public int level;

    /** Gson 反序列化需要无参构造器。 */
    public UserProfile() {
    }

    /** 昵称，为空时给一个占位，避免界面上出现 "null"。 */
    public String displayName() {
        return uname == null || uname.isEmpty() ? "B站用户" : uname;
    }
}

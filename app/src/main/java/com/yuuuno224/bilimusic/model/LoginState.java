package com.yuuuno224.bilimusic.model;

/**
 * 登录流程的进度状态。
 *
 * <p>这些数值来自 B 站的轮询接口（{@code data.code} 字段），也就是<b>线上协议的一部分</b>。
 * 之所以放在 {@code model} 而不是 {@code net/api}，是为了守住一条分层规则：
 * <b>界面层（{@code ui}）不直接依赖网络层（{@code net}）</b>。
 *
 * <p>登录页需要根据这些状态显示不同文案，如果常量定义在网络层，
 * 界面就不得不 import 网络层的类。把它们放进所有人都会依赖的 {@code model}，
 * 网络层和界面层就都能用，而且谁也不依赖谁。
 *
 * <p>判断"登录成功"时注意：{@link #SUCCESS} 的值就是 {@code 0}，
 * 容易和"接口调用成功"的 {@code ApiResponse.code == 0} 混淆。
 * 它们是两层不同的 code，见 {@code net/api/LoginApi#poll} 的说明。
 */
public final class LoginState {

    /** 登录成功。此时响应头里会下发登录 Cookie。 */
    public static final int SUCCESS = 0;

    /** 已在 B 站 App 里扫码，等待用户点"确认"。 */
    public static final int SCANNED = 86090;

    /** 还在等待用户扫码。 */
    public static final int WAITING = 86101;

    /** 授权链接已过期，需要重新生成。 */
    public static final int EXPIRED = 86038;

    private LoginState() {
    }
}

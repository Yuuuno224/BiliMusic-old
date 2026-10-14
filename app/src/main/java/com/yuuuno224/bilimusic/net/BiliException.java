package com.yuuuno224.bilimusic.net;

import java.io.IOException;

/**
 * B 站相关的请求异常。
 *
 * <p>为什么不用普通的 {@link IOException}：B 站几乎所有接口都会返回 HTTP 200，
 * 但响应体里带着一个业务码 {@code {"code":-101,"message":"账号未登录"}}。
 * 只看 HTTP 状态码会以为"请求成功"，实际上业务失败了。所以这里把业务码一起带出来，
 * 让上层能区分"网络断了"和"B 站说不行"。
 *
 * <p>{@link #code()} 的取值有两种来源：
 * <ul>
 *   <li>业务码：0 表示成功（成功时不会抛异常），负数表示各种业务失败，例如 -101 未登录、-403 签名校验失败；</li>
 *   <li>HTTP 状态码：只有请求真的失败（4xx / 5xx）时才会用到，例如 412 就是 B 站风控拦截。</li>
 * </ul>
 */
public class BiliException extends IOException {

    /** B 站签名校验失败（密钥过期或参数被篡改），重新取一次 WBI 密钥即可重试。 */
    public static final int CODE_SIGN_INVALID = -403;

    /** B 站认为请求异常，触发风控。通常是短时间请求太频繁。 */
    public static final int CODE_RISK_CONTROL = -352;

    /** 未登录 / 登录已过期。 */
    public static final int CODE_NOT_LOGGED_IN = -101;

    /** 没有业务码时的占位值（纯网络错误、解析错误）。 */
    public static final int CODE_NONE = -1;

    private final int code;

    public BiliException(String message) {
        this(CODE_NONE, message);
    }

    public BiliException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean isSignInvalid() {
        return code == CODE_SIGN_INVALID;
    }

    public boolean isNotLoggedIn() {
        return code == CODE_NOT_LOGGED_IN;
    }

    /**
     * 是否属于风控拦截。
     * <p>412 是 HTTP 的 Precondition Failed，B 站用它表示"你这个 IP / 账号被限流了"。
     */
    public boolean isRiskControl() {
        return code == CODE_RISK_CONTROL || code == 412;
    }
}

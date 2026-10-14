package com.yuuuno224.bilimusic.net.dto;

/**
 * B 站所有接口统一的响应外壳。
 *
 * <pre>
 * { "code": 0, "message": "0", "data": { ...真正的数据... } }
 * </pre>
 *
 * <p>{@code code == 0} 表示成功。常见失败码：
 * <ul>
 *   <li>{@code -101} 账号未登录</li>
 *   <li>{@code -403} 访问权限不足（通常是 WBI 签名过期）</li>
 *   <li>{@code -352} 风控校验失败</li>
 * </ul>
 *
 * <p>注意：<b>失败也是 HTTP 200</b>，所以不能只看 HTTP 状态码，必须检查这里的 code。
 * 判断与抛异常的逻辑统一放在 {@link com.yuuuno224.bilimusic.net.BiliSession} 里。
 *
 * @param <T> {@code data} 字段的类型
 */
public class ApiResponse<T> {

    public int code;
    public String message;
    public T data;

    public boolean ok() {
        return code == 0;
    }
}

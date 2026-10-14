package com.yuuuno224.bilimusic.net;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * JSON 序列化的唯一入口。
 *
 * <p>为什么不让每个类自己 {@code new Gson()}：Gson 实例是线程安全的、创建也不贵，
 * 但它内部持有类型适配器缓存，全 App 共用一个既省内存又行为一致。
 * 更重要的是——所有"解析失败"都在这里被翻译成带上下文的 {@link BiliException}，
 * 而不是抛出一句看不出所以然的 {@code JsonSyntaxException}。
 */
public final class Json {

    private static final Gson GSON = new Gson();

    /** 出错时最多回显多少个字符，避免把整个 HTML 错误页塞进日志。 */
    private static final int SNIPPET_LIMIT = 200;

    private Json() {
    }

    /**
     * 把响应体解析成指定类型，失败时抛出可读异常。
     *
     * @param body 服务端返回的原始文本
     * @param type 目标类型，通常写成 {@code new TypeToken<ApiResponse<XxxDto>>(){}.getType()}
     */
    public static <T> T parse(String body, Type type) throws BiliException {
        if (body == null || body.isEmpty()) {
            throw new BiliException("服务端返回了空响应");
        }
        try {
            T value = GSON.fromJson(body, type);
            if (value == null) {
                throw new BiliException("响应解析结果为空：" + snippet(body));
            }
            return value;
        } catch (JsonParseException e) {
            throw new BiliException("响应不是合法 JSON：" + snippet(body));
        }
    }

    /** 对象转 JSON 字符串（写本地存储、塞进 Bundle 时用）。 */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /** JSON 字符串转对象；内容为空或非法时返回 null，不抛异常（本地缓存损坏不应让 App 崩溃）。 */
    public static <T> T fromJsonOrNull(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, type);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** 同上，用于带泛型的类型（如 {@code List<Song>}）。 */
    public static <T> T fromJsonOrNull(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, type);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** 截取一段用于错误提示的文本。 */
    static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= SNIPPET_LIMIT ? flat : flat.substring(0, SNIPPET_LIMIT) + "…";
    }
}

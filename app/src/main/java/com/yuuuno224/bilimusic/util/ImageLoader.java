package com.yuuuno224.bilimusic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.net.BiliHttp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量图片加载器：内存 LRU + 磁盘缓存 + 请求合并。
 *
 * <h3>为什么不用 Glide / Coil</h3>
 * 本项目只需要"给一个 URL，回调一张 Bitmap"，功能范围极窄。
 * 引入图片库要付出体积、混淆规则和学习成本的代价，自己写一个反而更好懂。
 * 代价是没有生命周期感知、没有图形变换（圆角/裁剪）、没有 GIF 支持。
 *
 * <h3>三级结构</h3>
 * <pre>
 * load(url, cb)
 *   ├── 内存 LRU 命中 → 直接回调（最快，同一屏反复滚动时不重复解码）
 *   ├── 磁盘缓存命中 → 解码后回调（省流量）
 *   └── 网络下载 → 解码 → 同时写入磁盘与内存 → 回调
 * </pre>
 *
 * <h3>两个容易忽略但很重要的细节</h3>
 * <ol>
 *   <li><b>请求合并</b>：列表快速滚动时同一张封面可能被好几个 item 同时请求。
 *       不合并就会并发下载同一张图。这里用 {@link #IN_FLIGHT} 记下
 *       "这个地址正在下载"，后来的请求只登记等待、不再发起下载。</li>
 *   <li><b>可取消</b>：{@link #load} 返回一个 {@link Request}，
 *       ViewHolder 被复用或页面销毁时调用 {@link Request#cancel()}，
 *       迟到的结果就不会再贴到已经换了内容的 item 上（这是列表图片错位的经典原因）。</li>
 * </ol>
 *
 * <h3>线程</h3>
 * 下载与解码跑在 {@link AppExecutors#image()}（4 线程），回调统一回到主线程。
 */
public final class ImageLoader {

    /** 加载完成回调，<b>保证在主线程</b>；失败时为 null。 */
    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    /** 内存缓存上限。16MB 大约能放 30~60 张列表缩略图。 */
    private static final int MEMORY_CACHE_BYTES = 16 * 1024 * 1024;

    /** 磁盘缓存上限，超过就按最后修改时间清理掉最旧的一半。 */
    private static final long DISK_CACHE_BYTES = 64L * 1024 * 1024;

    private static final android.util.LruCache<String, Bitmap> MEMORY =
            new android.util.LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** 正在下载的地址 → 等待同一张图的请求列表。 */
    private static final Map<String, List<Request>> IN_FLIGHT =
            Collections.synchronizedMap(new HashMap<String, List<Request>>());

    private static File cacheDir;

    private ImageLoader() {
    }

    /** 在 {@code Application.onCreate()} 里调用，创建磁盘缓存目录。 */
    public static void init(android.content.Context context) {
        File dir = new File(context.getCacheDir(), "covers");
        if (dir.exists() || dir.mkdirs()) {
            cacheDir = dir;
        }
    }

    /** 一次加载请求的句柄，用来取消。 */
    public static final class Request {

        private final String url;
        private final Callback callback;
        private volatile boolean cancelled;

        private Request(String url, Callback callback) {
            this.url = url;
            this.callback = callback;
        }

        /** 取消：即使图片已经在路上，回调也不会再发生。 */
        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * 加载图片。
     *
     * @param url      图片地址，支持 {@code //} 开头的协议相对地址（自动补 https）；
     *                 为 null 或空时立刻回调 null
     * @param callback 结果回调，主线程
     * @return 请求句柄；不再需要时调用 {@link Request#cancel()}
     */
    public static Request load(String url, Callback callback) {
        Request request = new Request(url, callback);
        if (callback == null) {
            return request;
        }
        if (url == null || url.isEmpty()) {
            callback.onLoaded(null);
            return request;
        }

        final String key = normalize(url);

        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            callback.onLoaded(cached);
            return request;
        }

        // 已经在下载同一个地址：只登记等待，等第一个请求完成后统一分发
        synchronized (IN_FLIGHT) {
            List<Request> waiting = IN_FLIGHT.get(key);
            if (waiting != null) {
                waiting.add(request);
                return request;
            }
            List<Request> started = new ArrayList<>(1);
            started.add(request);
            IN_FLIGHT.put(key, started);
        }

        AppExecutors.image().execute(() -> {
            Bitmap bitmap = diskGet(key);
            if (bitmap == null) {
                bitmap = netGet(key);
                if (bitmap != null) {
                    diskPut(key, bitmap);
                }
            }
            if (bitmap != null) {
                MEMORY.put(key, bitmap);
            }
            dispatch(key, bitmap);
        });

        return request;
    }

    /** 把一个地址的所有等待者一起回调掉。 */
    private static void dispatch(String key, final Bitmap bitmap) {
        final List<Request> waiting;
        synchronized (IN_FLIGHT) {
            waiting = IN_FLIGHT.remove(key);
        }
        if (waiting == null) {
            return;
        }
        AppExecutors.postOnMain(() -> {
            for (Request request : waiting) {
                // 已取消的请求直接丢弃
                if (!request.cancelled) {
                    request.callback.onLoaded(bitmap);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 缓存实现
    // ------------------------------------------------------------------

    /**
     * 统一协议：Android 9 起默认禁止明文 HTTP，而 B 站有些封面给的是
     * {@code http://} 或 {@code //} 开头，必须升级到 https。
     */
    private static String normalize(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private static Bitmap diskGet(String key) {
        if (cacheDir == null) {
            return null;
        }
        File file = new File(cacheDir, hash(key));
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private static void diskPut(String key, Bitmap bitmap) {
        if (cacheDir == null) {
            return;
        }
        File file = new File(cacheDir, hash(key));
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(webpFormat(), 85, out);
        } catch (Exception ignored) {
            // 写盘失败不影响本次显示，下次再缓存
            return;
        }
        trimDiskCacheIfNeeded();
    }

    /**
     * WebP 压缩格式。
     * <p>API 30 起旧的 {@code WEBP} 常量被标记废弃（它表示有损 WebP），
     * 需要换成语义明确的 {@code WEBP_LOSSY}。
     */
    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat webpFormat() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
    }

    /** 磁盘缓存超限时删掉最旧的一半文件。 */
    private static void trimDiskCacheIfNeeded() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0L;
        for (File file : files) {
            total += file.length();
        }
        if (total <= DISK_CACHE_BYTES) {
            return;
        }
        // 按最后修改时间升序，先删最旧的
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int removeCount = files.length / 2;
        for (int i = 0; i < removeCount; i++) {
            files[i].delete();
        }
    }

    private static Bitmap netGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = BiliHttp.open(url, null);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                byte[] bytes = buffer.toByteArray();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception e) {
            // 单张图失败不值得打扰用户，界面会显示占位图标
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 用 MD5 做文件名：避免 URL 里的特殊字符，也保证长度固定。 */
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}

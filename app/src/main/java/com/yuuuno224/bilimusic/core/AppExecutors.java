package com.yuuuno224.bilimusic.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全 App 的"线程总控"：所有后台线程和主线程回调都从这里取。
 *
 * <h3>为什么要有这个类</h3>
 * Android 有两条铁律：
 * <ol>
 *   <li>网络请求、读写大文件不能在主线程做，否则界面卡住甚至 ANR；</li>
 *   <li>改界面（setText / notifyDataSetChanged …）必须在主线程做，否则直接崩溃。</li>
 * </ol>
 * 如果每个类都自己 {@code new Thread()} 或 {@code Executors.newFixedThreadPool()}，
 * 很快就会变成"到处是线程、没人知道一共有几条"。所以本项目把线程集中在这一个文件里，
 * 想知道"这个 App 一共开了几条线程、各干什么"，看下面 5 个常量就够了。
 *
 * <h3>五条线程（池）的分工</h3>
 * <table border="1">
 *   <tr><th>名字</th><th>类型</th><th>干什么</th></tr>
 *   <tr><td>bili-io</td><td>单线程</td><td>用户触发的网络请求（搜索 / 榜单 / 收藏夹）。串行执行，避免并发请求触发 B 站风控</td></tr>
 *   <tr><td>bili-auth</td><td>单线程</td><td>扫码登录：生成链接、每 2 秒轮询状态</td></tr>
 *   <tr><td>bili-resolve</td><td>固定 6 线程</td><td>播放队列解析：并行把 bvid 换成真实音频地址</td></tr>
 *   <tr><td>bili-image</td><td>固定 4 线程</td><td>封面图下载 + 解码</td></tr>
 *   <tr><td>bili-store</td><td>单线程</td><td>收藏 / 历史的 JSON 落盘（写文件不能占用 io 线程，否则会被网络请求排队堵住）</td></tr>
 * </table>
 *
 * 所有线程都是 <b>daemon</b>（守护线程）：进程结束时不会拖着 App 不让退。
 */
public final class AppExecutors {

    /** 主线程 Handler。主线程也叫 UI 线程，只有它能改界面。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService IO = newSingle("bili-io");
    private static final ExecutorService AUTH = newSingle("bili-auth");
    private static final ExecutorService RESOLVE = newFixed("bili-resolve", 6);
    private static final ExecutorService IMAGE = newFixed("bili-image", 4);
    private static final ExecutorService STORE = newSingle("bili-store");

    private AppExecutors() {
    }

    /** 主线程 Handler，给需要 postDelayed 的场景用（例如播放进度条每 500ms 刷新）。 */
    public static Handler main() {
        return MAIN;
    }

    /**
     * 切回主线程执行。
     * <p>如果当前已经在主线程，会<b>立刻同步执行</b>，少一次消息投递，也避免 UI 更新被延后一帧。
     */
    public static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    /**
     * 强制投递到主线程队列尾部，即使当前已经在主线程。
     * <p>用于"合并刷新"场景：短时间内多次变化只希望触发一轮界面更新。
     */
    public static void postOnMain(Runnable action) {
        MAIN.post(action);
    }

    /** bili-io：串行的网络请求线程。 */
    public static ExecutorService io() {
        return IO;
    }

    /** bili-auth：登录相关的网络线程。 */
    public static ExecutorService auth() {
        return AUTH;
    }

    /** bili-resolve：并行解析播放队列。 */
    public static ExecutorService resolve() {
        return RESOLVE;
    }

    /** bili-image：图片下载与解码。 */
    public static ExecutorService image() {
        return IMAGE;
    }

    /** bili-store：本地 JSON 落盘。 */
    public static ExecutorService store() {
        return STORE;
    }

    private static ExecutorService newSingle(String name) {
        return Executors.newSingleThreadExecutor(namedFactory(name));
    }

    private static ExecutorService newFixed(String name, int size) {
        return Executors.newFixedThreadPool(size, namedFactory(name));
    }

    /** 统一给线程起名字，方便在 Android Studio 的 Profiler / 日志里认出是谁在跑。 */
    private static ThreadFactory namedFactory(String name) {
        final AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}

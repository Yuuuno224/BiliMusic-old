package com.yuuuno224.bilimusic;

import android.app.Application;

import com.yuuuno224.bilimusic.auth.AuthManager;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.util.ImageLoader;

/**
 * Application 入口：App 进程启动时最先执行的地方。
 *
 * <h3>为什么初始化放在这里</h3>
 * 这三个组件都是"全局单例 + 需要 Context 才能初始化"：
 * <ul>
 *   <li>{@link AuthManager} —— 顺带完成 Cookie 的加载（它内部会调 {@code CookieStore.init}，
 *       所以这里不需要再单独初始化一次）</li>
 *   <li>{@link MusicStore} —— 从 JSON 文件读入收藏与播放历史</li>
 *   <li>{@link ImageLoader} —— 准备图片磁盘缓存目录</li>
 * </ul>
 * 放在这里可以保证任何一个 Activity 打开时数据都已就绪，
 * 界面不需要处理"数据还没加载完"的中间状态。
 *
 * <p>顺序不能乱：{@code AuthManager} 必须早于任何网络请求，
 * 因为 WBI 密钥和风控指纹 Cookie 都依赖它准备好的 Cookie 容器。
 */
public class BiliMusicApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AuthManager.init(this);
        MusicStore.init(this);
        ImageLoader.init(this);
    }
}

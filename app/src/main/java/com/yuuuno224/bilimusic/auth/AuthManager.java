package com.yuuuno224.bilimusic.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.LoginState;
import com.yuuuno224.bilimusic.model.UserProfile;
import com.yuuuno224.bilimusic.net.CookieStore;
import com.yuuuno224.bilimusic.net.Json;
import com.yuuuno224.bilimusic.net.api.LoginApi;

import java.io.IOException;

/**
 * 登录态管理：生成授权链接 → 轮询 → 保存 Cookie → 拉取用户信息。
 *
 * <h3>登录态由两部分组成</h3>
 * <ol>
 *   <li><b>Cookie</b>（{@code SESSDATA} + {@code bili_jct}）—— 真正的凭证，
 *       由 {@link CookieStore} 保管。{@link #isLoggedIn()} 判断的就是它。</li>
 *   <li><b>用户信息</b>（昵称、头像、等级）—— 只是展示用的缓存，
 *       存在 SharedPreferences 的 {@code bili_auth / me} 里，丢了也不影响登录。</li>
 * </ol>
 * 分开存的原因是：Cookie 每次请求都要用，必须最早可用；用户信息只是为了省一次网络请求。
 *
 * <h3>为什么要处理"Cookie 还在但已过期"</h3>
 * 用户可以随时在 B 站 App 里退出登录，或者凭证自然过期。这时本地 Cookie 仍然存在，
 * 但接口会返回 {@code -101}。{@link #refreshProfile} 会把这种情况识别出来并清理本地状态，
 * 否则界面会一直显示"已登录"却什么都拉不到。
 */
public final class AuthManager {

    /** 扫码登录流程的回调。方法都在主线程被调用。 */
    public interface LoginCallback {

        /** 授权链接已生成。 */
        void onGenerated(String authUrl, String qrcodeKey);

        /**
         * 登录进度变化。
         *
         * @param state 取值见 {@link LoginState} 的常量
         */
        void onStatus(int state);

        /** 登录成功并已拿到用户信息。 */
        void onSuccess(UserProfile profile);

        /** 出错（网络异常等）。 */
        void onError(String message);
    }

    /** 获取用户信息的回调。 */
    public interface ProfileCallback {
        /**
         * @param profile 成功时为最新用户信息；失败或未登录时为 null
         */
        void onResult(UserProfile profile);
    }

    private static final String PREF_NAME = "bili_auth";
    private static final String KEY_PROFILE = "me";

    /** 用户信息缓存的有效期：超过它再进入"我的"页就顺手刷新一次。 */
    private static final long PROFILE_TTL_MS = 10 * 60 * 1000L;

    private static Context appContext;
    private static UserProfile cachedProfile;
    private static long profileFetchedAt;

    private AuthManager() {
    }

    /**
     * 在 {@code Application.onCreate()} 里调用一次。
     * <p>它会顺带完成 {@link CookieStore#init(Context)}，
     * 所以 {@code BiliMusicApp} 不需要再单独初始化 Cookie。
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        CookieStore.init(appContext);
        cachedProfile = Json.fromJsonOrNull(
                prefs().getString(KEY_PROFILE, null), UserProfile.class);
    }

    /** 本地是否存在登录凭证。注意它不代表凭证一定还有效，见 {@link #refreshProfile}。 */
    public static boolean isLoggedIn() {
        return CookieStore.has("SESSDATA") && CookieStore.has("bili_jct");
    }

    /** 上次拿到的用户信息，可能为 null（没登录过、或刚被清理）。 */
    public static UserProfile cachedProfile() {
        return cachedProfile;
    }

    /** 缓存的用户信息是否已经过期，需要重新拉取。 */
    public static boolean isProfileStale() {
        return cachedProfile == null
                || cachedProfile.mid <= 0
                || System.currentTimeMillis() - profileFetchedAt > PROFILE_TTL_MS;
    }

    /**
     * 第一步：生成授权链接。
     *
     * <p>用户点开这个链接会被唤起 B 站 App 的确认页，点"确认"即完成授权。
     * 本项目不自己绘制二维码图片——链接方式已经能满足单机使用的场景。
     */
    public static void generate(final LoginCallback callback) {
        AppExecutors.auth().execute(() -> {
            try {
                final com.yuuuno224.bilimusic.net.dto.QrLoginDto.Generate data =
                        LoginApi.generate();
                AppExecutors.runOnMain(
                        () -> callback.onGenerated(data.url, data.qrcodeKey));
            } catch (IOException e) {
                AppExecutors.runOnMain(() -> callback.onError(messageOf(e)));
            }
        });
    }

    /**
     * 第二步：查询一次登录状态。
     *
     * <p>由界面每隔 {@code POLL_INTERVAL} 调用一次，直到成功、过期或超时。
     * 登录成功后会自动落盘 Cookie 并拉取用户信息，然后回调 {@link LoginCallback#onSuccess}。
     */
    public static void pollOnce(final String qrcodeKey, final LoginCallback callback) {
        AppExecutors.auth().execute(() -> {
            try {
                int state = LoginApi.poll(qrcodeKey);
                if (state != LoginState.SUCCESS) {
                    AppExecutors.runOnMain(() -> callback.onStatus(state));
                    return;
                }

                // 登录成功：先把凭证落盘（这时候才真正"登录"），再拉用户信息
                CookieStore.persist(appContext);
                UserProfile profile;
                try {
                    profile = LoginApi.profile();
                } catch (IOException e) {
                    // 用户信息拉不到不算登录失败，构造一个最小对象保证流程能继续
                    profile = new UserProfile();
                    profile.isLogin = true;
                }
                saveProfile(profile);
                final UserProfile result = profile;
                AppExecutors.runOnMain(() -> callback.onSuccess(result));
            } catch (IOException e) {
                AppExecutors.runOnMain(() -> callback.onError(messageOf(e)));
            }
        });
    }

    /**
     * 从网络刷新用户信息。
     *
     * <p>如果 B 站说"未登录"（凭证已过期），这里会顺手清掉本地 Cookie 与缓存，
     * 让界面回到未登录状态。
     */
    public static void refreshProfile(final ProfileCallback callback) {
        if (!isLoggedIn()) {
            // 本来就没登录，不必浪费一次网络请求
            callback.onResult(null);
            return;
        }
        AppExecutors.auth().execute(() -> {
            UserProfile profile = null;
            try {
                UserProfile fetched = LoginApi.profile();
                if (fetched != null && fetched.isLogin) {
                    saveProfile(fetched);
                    profile = fetched;
                } else {
                    clearLocalSession();
                }
            } catch (IOException e) {
                // 网络问题不应该把用户的登录态清掉，下一次再试
            }
            final UserProfile result = profile;
            AppExecutors.runOnMain(() -> callback.onResult(result));
        });
    }

    /**
     * 退出登录。
     *
     * <p>顺序很重要：先尽力通知 B 站作废凭证（失败也无所谓），
     * 再清本地 Cookie 与缓存，最后才回调——界面收到回调时状态已经是"未登录"了。
     *
     * @param onDone 完成回调（主线程），可为 null
     */
    public static void logout(final Runnable onDone) {
        AppExecutors.auth().execute(() -> {
            try {
                LoginApi.revokeSession();
            } catch (IOException ignored) {
                // 作废失败不影响本地退出
            }
            clearLocalSession();
            AppExecutors.runOnMain(() -> {
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    /** 清掉 Cookie、内存缓存与磁盘缓存。 */
    private static void clearLocalSession() {
        CookieStore.clearSession();
        CookieStore.persist(appContext);
        cachedProfile = null;
        profileFetchedAt = 0L;
        prefs().edit().remove(KEY_PROFILE).apply();
    }

    private static void saveProfile(UserProfile profile) {
        cachedProfile = profile;
        profileFetchedAt = System.currentTimeMillis();
        prefs().edit().putString(KEY_PROFILE, Json.toJson(profile)).apply();
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 把异常翻译成一句能给用户看的提示。 */
    private static String messageOf(IOException e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? "网络异常，请稍后重试" : message;
    }
}

package com.yuuuno224.bilimusic.ui.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.auth.AuthManager;
import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.UserProfile;
import com.yuuuno224.bilimusic.net.api.LoginApi;

/**
 * 登录页：生成授权链接 → 用户去 B 站 App 确认 → 本页轮询直到成功。
 *
 * <h3>完整交互流程</h3>
 * <pre>
 * 进入本页
 *   → AuthManager.generate()：拿到一个授权链接
 *   → 用户点「打开B站APP授权」→ 跳到 B 站 App 的确认页 → 点确认
 *   → 与此同时本页每 2 秒问一次 B 站「确认了吗」（AuthManager.pollOnce）
 *   → 拿到成功 → 保存 Cookie → 关页面
 * </pre>
 *
 * <h3>为什么不自己画二维码</h3>
 * B 站返回的 {@code url} 本身就是一个可以被 B 站 App 识别的授权链接。
 * 直接让用户点它，比"生成二维码图片 + 让用户拿另一台设备扫"少一个步骤，
 * 单机使用场景下体验更好。所以本项目叫"授权链接登录"而不是"扫码登录"。
 *
 * <h3>两个必须小心的地方</h3>
 * <ol>
 *   <li><b>轮询不能重复启动</b>。示例：链接过期后要重新生成，
 *       如果这时旧的定时器还在排队，就会出现两个轮询同时跑，请求量翻倍。
 *       这里用 {@link #generation}（世代号）解决：每次重新生成都 +1，
 *       回调发现自己的世代号已经不是最新的就直接忽略。
 *       同时下一次轮询只在<b>上一次有结果之后</b>才安排，天然不会重叠。</li>
 *   <li><b>轮询不能在页面可见时才跑</b>。用户确认登录时必须切到 B 站 App，
 *       本页会进入 {@code onPause}。如果那时停掉轮询，就永远等不到成功。
 *       所以只在 {@code onDestroy} 才停止轮询。</li>
 * </ol>
 */
public class QrLoginActivity extends AppCompatActivity {

    /** 轮询间隔。太短会被风控，太长用户觉得慢，2 秒是常用值。 */
    private static final long POLL_INTERVAL_MS = 2000L;

    /** 授权链接总有效期。超过就提示重新生成。 */
    private static final long TIMEOUT_MS = 180_000L;

    private TextView status;
    private Button openButton;
    private Button copyButton;

    private String authUrl;
    private String qrcodeKey;

    /** 当前有效的回调，轮询时复用它。 */
    private AuthManager.LoginCallback activeCallback;

    /**
     * 世代号：每发起一次"生成"就 +1。
     * <p>旧的定时器/回调发现自己的世代号过期了就直接返回，不再影响界面，
     * 这是解决"重复轮询"最省事的办法。
     */
    private int generation;

    private boolean polling;
    private long startedAt;

    /** 是否已经因为超时而停下（此时"复制链接"按钮变成"重新生成"）。 */
    private boolean timedOut;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!polling || qrcodeKey == null || activeCallback == null) {
                return;
            }
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                onTimeout();
                return;
            }
            // 下一次轮询在收到本次结果后才安排（见 scheduleNextPoll），不会重叠
            AuthManager.pollOnce(qrcodeKey, activeCallback);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_login);

        status = findViewById(R.id.login_status);
        openButton = findViewById(R.id.login_open);
        copyButton = findViewById(R.id.login_copy);

        openButton.setOnClickListener(v -> openAuthUrl());
        copyButton.setOnClickListener(v -> {
            if (timedOut) {
                // 超时后这个按钮已经被换成"重新生成"
                restartLogin();
                return;
            }
            copyAuthUrl();
        });

        startLogin();
    }

    /** 超时：停下轮询，把"复制链接"按钮换成"重新生成"。 */
    private void onTimeout() {
        polling = false;
        timedOut = true;
        status.setText("授权超时");
        openButton.setEnabled(false);
        copyButton.setText("重新生成");
    }

    private void restartLogin() {
        timedOut = false;
        openButton.setEnabled(true);
        copyButton.setText(R.string.copy_link);
        startLogin();
    }

    // ------------------------------------------------------------------
    // 登录流程
    // ------------------------------------------------------------------

    /** 开始（或重新开始）一次登录：生成新链接并启动轮询。 */
    private void startLogin() {
        final int myGeneration = ++generation;
        polling = false;
        authUrl = null;
        qrcodeKey = null;
        AppExecutors.main().removeCallbacks(pollTask);
        status.setText("正在生成授权链接…");

        activeCallback = new AuthManager.LoginCallback() {

            @Override
            public void onGenerated(String url, String key) {
                if (isStale(myGeneration)) {
                    return;
                }
                authUrl = url;
                qrcodeKey = key;
                startedAt = System.currentTimeMillis();
                polling = true;
                status.setText("已生成授权链接，请点击下方按钮在B站APP中确认");
                scheduleNextPoll();
            }

            @Override
            public void onStatus(int state) {
                if (isStale(myGeneration)) {
                    return;
                }
                if (state == LoginApi.ST_EXPIRED) {
                    // 链接过期：重新生成一条，旧的轮询由世代号作废
                    status.setText("授权已过期，正在重新生成…");
                    startLogin();
                    return;
                }
                status.setText(statusTextOf(state));
                scheduleNextPoll();
            }

            @Override
            public void onSuccess(UserProfile profile) {
                if (isStale(myGeneration)) {
                    return;
                }
                polling = false;
                AppExecutors.main().removeCallbacks(pollTask);
                status.setText("登录成功");
                String name = profile != null ? profile.displayName() : "";
                Toast.makeText(QrLoginActivity.this, "欢迎 " + name, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(String message) {
                if (isStale(myGeneration)) {
                    return;
                }
                // 网络抖一下不值得中断流程，提示一下继续轮询
                status.setText(message);
                scheduleNextPoll();
            }
        };

        AuthManager.generate(activeCallback);
    }

    /** 这个回调是否已经属于过期的世代。 */
    private boolean isStale(int callbackGeneration) {
        return callbackGeneration != generation || isFinishing() || isDestroyed();
    }

    private void scheduleNextPoll() {
        if (!polling) {
            return;
        }
        AppExecutors.main().removeCallbacks(pollTask);
        AppExecutors.main().postDelayed(pollTask, POLL_INTERVAL_MS);
    }

    private static String statusTextOf(int state) {
        if (state == LoginApi.ST_SCANNED) {
            return "已在B站APP中确认，正在完成登录…";
        }
        if (state == LoginApi.ST_EXPIRED) {
            return "授权已过期";
        }
        return "等待在B站APP中确认授权…";
    }

    // ------------------------------------------------------------------
    // 链接操作
    // ------------------------------------------------------------------

    private void openAuthUrl() {
        if (authUrl == null) {
            Toast.makeText(this, "授权链接尚未生成", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "未找到可打开该链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAuthUrl() {
        if (authUrl == null) {
            Toast.makeText(this, "授权链接尚未生成", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("bili_auth", authUrl));
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        // 只有页面真的销毁才停止轮询：用户去 B 站 App 确认时本页只是 onPause
        polling = false;
        generation++;
        AppExecutors.main().removeCallbacks(pollTask);
        super.onDestroy();
    }
}

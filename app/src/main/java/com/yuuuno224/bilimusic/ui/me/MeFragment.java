package com.yuuuno224.bilimusic.ui.me;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.auth.AuthManager;
import com.yuuuno224.bilimusic.model.UserProfile;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.ui.MainActivity;
import com.yuuuno224.bilimusic.ui.auth.QrLoginActivity;
import com.yuuuno224.bilimusic.util.ImageLoader;

/**
 * "我的"页：账号信息、横向快捷入口、GitHub 仓库、关于。
 *
 * <h3>登录状态从哪来</h3>
 * 界面不自己判断登录，而是问 {@link AuthManager}：
 * <ul>
 *   <li>{@link AuthManager#isLoggedIn()} —— 本地有没有凭证（Cookie）；</li>
 *   <li>{@link AuthManager#cachedProfile()} —— 上次拿到的昵称头像等级。</li>
 * </ul>
 * 两者要一起看：有 Cookie 但没有用户信息，说明登录过但缓存丢了，需要补拉一次。
 *
 * <h3>为什么退出登录要传回调</h3>
 * {@link AuthManager#logout} 是异步的（它要先通知 B 站作废凭证）。
 * 如果调用后立刻刷新界面，此时本地 Cookie 还没清掉，界面会显示"仍然已登录"。
 * 所以刷新必须放进回调里——这是曾经的 bug，改动时务必保留。
 */
public class MeFragment extends Fragment {

    private ImageView avatar;
    private TextView name;
    private TextView level;
    private TextView statFavorite;
    private TextView statHistory;
    private Button loginButton;
    private Button logoutButton;

    /** 头像加载请求，用于取消。 */
    private ImageLoader.Request avatarRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_me, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        avatar = view.findViewById(R.id.me_avatar);
        name = view.findViewById(R.id.me_name);
        level = view.findViewById(R.id.me_level);
        statFavorite = view.findViewById(R.id.me_stat_fav);
        statHistory = view.findViewById(R.id.me_stat_hist);
        loginButton = view.findViewById(R.id.me_login);
        logoutButton = view.findViewById(R.id.me_logout);

        loginButton.setOnClickListener(v ->
                startActivity(new Intent(getContext(), QrLoginActivity.class)));

        // 退出登录：刷新必须放在回调里，此时本地凭证才真的被清掉了
        logoutButton.setOnClickListener(v -> {
            logoutButton.setEnabled(false);
            AuthManager.logout(() -> {
                if (!isViewUsable()) {
                    return;
                }
                logoutButton.setEnabled(true);
                refreshUi();
                Toast.makeText(getContext(), "已退出登录", Toast.LENGTH_SHORT).show();
            });
        });

        view.findViewById(R.id.me_entry_fav).setOnClickListener(v -> openLibraryTab(0));
        view.findViewById(R.id.me_entry_hist).setOnClickListener(v -> openLibraryTab(1));
        view.findViewById(R.id.me_entry_folder).setOnClickListener(v -> {
            if (!AuthManager.isLoggedIn()) {
                Toast.makeText(getContext(), "请先登录B站账号", Toast.LENGTH_SHORT).show();
                return;
            }
            openLibraryTab(2);
        });
        view.findViewById(R.id.me_entry_about).setOnClickListener(v -> showAboutDialog());
        view.findViewById(R.id.me_entry_github).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Yuuuno224/BiliMusic"))));
    }

    /** 跳回主界面并切到乐库的指定标签。 */
    private void openLibraryTab(int tab) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openLibraryTab(tab);
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("关于 BiliMusic")
                .setMessage(getString(R.string.disclaimer)
                        + "\n\n音源：B站公开视频\n播放：Media3 ExoPlayer\n开源：GitHub @Yuuuno224")
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();

        // 缓存过期时顺手从网络补一次用户信息（例如等级变了、头像换了）
        if (AuthManager.isLoggedIn() && AuthManager.isProfileStale()) {
            AuthManager.refreshProfile(result -> {
                if (isViewUsable()) {
                    refreshUi();
                }
            });
        }
    }

    /** 按当前登录态刷新整个页面。 */
    private void refreshUi() {
        if (!isViewUsable()) {
            return;
        }
        UserProfile profile = AuthManager.cachedProfile();
        boolean loggedIn = AuthManager.isLoggedIn() && profile != null && profile.isLogin;

        if (loggedIn) {
            loginButton.setVisibility(View.GONE);
            logoutButton.setVisibility(View.VISIBLE);
            name.setText(profile.displayName());
            level.setText("LV" + profile.level + " · 已登录B站账号");
            loadAvatar(profile.face);
        } else {
            loginButton.setVisibility(View.VISIBLE);
            logoutButton.setVisibility(View.GONE);
            name.setText("未登录");
            level.setText("登录后可获得更高音质");
            if (avatarRequest != null) {
                avatarRequest.cancel();
                avatarRequest = null;
            }
            avatar.setImageResource(R.drawable.ic_person);
        }

        statFavorite.setText(String.valueOf(MusicStore.favorites().size()));
        statHistory.setText(String.valueOf(MusicStore.history().size()));
    }

    private void loadAvatar(String faceUrl) {
        if (faceUrl == null || faceUrl.isEmpty()) {
            avatar.setImageResource(R.drawable.ic_person);
            return;
        }
        if (avatarRequest != null) {
            avatarRequest.cancel();
        }
        avatarRequest = ImageLoader.load(faceUrl, bitmap -> {
            if (!isViewUsable()) {
                return;
            }
            if (bitmap != null) {
                avatar.setImageBitmap(bitmap);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (avatarRequest != null) {
            avatarRequest.cancel();
            avatarRequest = null;
        }
    }

    private boolean isViewUsable() {
        return isAdded() && getView() != null;
    }
}

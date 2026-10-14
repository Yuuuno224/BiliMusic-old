package com.yuuuno224.bilimusic.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.player.PlayerConnection;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.ui.library.LibraryFragment;
import com.yuuuno224.bilimusic.ui.me.MeFragment;
import com.yuuuno224.bilimusic.ui.player.NowPlayingActivity;
import com.yuuuno224.bilimusic.ui.player.PlaylistDialog;
import com.yuuuno224.bilimusic.ui.search.SearchFragment;
import com.yuuuno224.bilimusic.util.ImageLoader;

/**
 * 主界面：底部三栏（搜索 / 乐库 / 我的）+ 常驻迷你播放条。
 *
 * <h3>它是"壳"，不含业务</h3>
 * 本类只做四件事：切换 Fragment、显示迷你播放条、把播放器状态转发给界面、
 * 申请播放通知所需的通知权限。真正的功能都在各个 Fragment 里。
 *
 * <h3>迷你播放条为什么放在这里而不是每个页面各写一份</h3>
 * 它是三个页面共用的常驻控件，放在 Activity 层只需要维护一份，
 * 切换页面时也不需要重建。
 */
public class MainActivity extends AppCompatActivity implements PlayerConnection.Listener {

    /** 申请通知权限的请求码，onRequestPermissionsResult 里用它区分来源。 */
    private static final int REQUEST_NOTIFICATIONS = 1001;

    private View miniBar;
    private ImageView miniCover;
    private TextView miniTitle;
    private TextView miniUp;
    private ImageButton miniPlay;
    private ImageButton miniFav;

    /** 迷你条封面正在进行的加载，用于页面销毁时取消。 */
    private ImageLoader.Request miniCoverRequest;

    /** 由"我的"页快捷入口设置的、进入乐库时要选中的标签。 */
    private int pendingLibraryTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViews();
        setupBottomNav();
        setupMiniBar();

        if (savedInstanceState == null) {
            show(new SearchFragment(), "search");
        }

        // 建立与播放服务的连接，并注册播放状态监听
        PlayerConnection.acquire(this);
        PlayerConnection.addListener(this);

        requestNotificationPermissionIfNeeded();
    }

    private void findViews() {
        miniBar = findViewById(R.id.mini_bar);
        miniCover = findViewById(R.id.mini_cover);
        miniTitle = findViewById(R.id.mini_title);
        miniUp = findViewById(R.id.mini_up);
        miniPlay = findViewById(R.id.mini_play);
        miniFav = findViewById(R.id.mini_fav);
    }

    private void setupBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_search) {
                show(new SearchFragment(), "search");
                return true;
            }
            if (id == R.id.nav_library) {
                LibraryFragment fragment = new LibraryFragment();
                if (pendingLibraryTab >= 0) {
                    // 从"我的"页跳过来：直接把要显示的标签传进去，
                    // 避免先显示默认标签再切一次造成闪烁
                    Bundle args = new Bundle();
                    args.putInt(LibraryFragment.ARG_TAB, pendingLibraryTab);
                    fragment.setArguments(args);
                    pendingLibraryTab = -1;
                }
                show(fragment, "library");
                return true;
            }
            if (id == R.id.nav_me) {
                show(new MeFragment(), "me");
                return true;
            }
            return false;
        });
    }

    private void setupMiniBar() {
        // 点整条 → 打开沉浸式播放页
        miniBar.setOnClickListener(v ->
                startActivity(new Intent(this, NowPlayingActivity.class)));

        miniPlay.setOnClickListener(v -> PlayerConnection.toggle());

        miniFav.setOnClickListener(v -> {
            Song song = PlayerConnection.currentSong();
            if (song == null) {
                return;
            }
            boolean nowFavorite = MusicStore.toggleFavorite(song);
            applyMiniFavorite(nowFavorite);
            Toast.makeText(this,
                    nowFavorite ? "已收藏" : "已取消收藏",
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.mini_playlist).setOnClickListener(v -> PlaylistDialog.show(this));
    }

    /**
     * 切到指定标签，并把要选中的标签交给乐库页。
     * <p>被"我的"页的三个快捷入口调用。
     */
    public void openLibraryTab(int tab) {
        pendingLibraryTab = tab;
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav.getSelectedItemId() == R.id.nav_library) {
            // 已经停在乐库页，BottomNavigationView 不会再回调，手动切一次
            LibraryFragment fragment = new LibraryFragment();
            Bundle args = new Bundle();
            args.putInt(LibraryFragment.ARG_TAB, tab);
            fragment.setArguments(args);
            pendingLibraryTab = -1;
            show(fragment, "library");
        } else {
            // 触发 setOnItemSelectedListener，由它带着 pendingLibraryTab 去创建页面
            nav.setSelectedItemId(R.id.nav_library);
        }
    }

    private void show(Fragment fragment, String tag) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    // ------------------------------------------------------------------
    // 播放状态 → 迷你播放条
    // ------------------------------------------------------------------

    /**
     * 播放器状态变化时刷新迷你条。
     * <p>由 {@link PlayerConnection} 保证在主线程调用。
     */
    @Override
    public void onPlayerChanged() {
        // 页面正在销毁时不要再碰 View
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Song song = PlayerConnection.currentSong();
        if (song == null) {
            miniBar.setVisibility(View.GONE);
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        miniTitle.setText(song.title);
        miniUp.setText(song.up);

        if (miniCoverRequest != null) {
            miniCoverRequest.cancel();
        }
        miniCoverRequest = ImageLoader.load(song.coverSmall(), bitmap -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (bitmap != null) {
                miniCover.setImageBitmap(bitmap);
            } else {
                miniCover.setImageResource(R.drawable.ic_music_note);
            }
        });

        miniPlay.setImageResource(PlayerConnection.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        applyMiniFavorite(MusicStore.isFavorite(song.bvid));
    }

    private void applyMiniFavorite(boolean favorite) {
        miniFav.setImageResource(
                favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        miniFav.setColorFilter(ContextCompat.getColor(this,
                favorite ? R.color.heart_red : R.color.on_bg));
    }

    // ------------------------------------------------------------------
    // 通知权限
    // ------------------------------------------------------------------

    /**
     * 申请通知权限。
     *
     * <p>Android 13（API 33）起，"显示通知"变成了需要用户授权的运行时权限。
     * 而本项目的播放控制条就是一条通知——不申请的话，音乐能放，
     * 但通知栏里既看不到歌曲也点不了暂停。
     *
     * <p>这里只在没授权时才弹；用户拒绝也不阻塞使用（只是没有通知栏控制）。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下不需要这个权限
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override
    protected void onDestroy() {
        // 三处都必须清理：播放监听、迷你条封面请求、连接引用
        PlayerConnection.removeListener(this);
        if (miniCoverRequest != null) {
            miniCoverRequest.cancel();
            miniCoverRequest = null;
        }
        super.onDestroy();
    }
}

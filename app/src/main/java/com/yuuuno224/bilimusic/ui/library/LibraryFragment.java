package com.yuuuno224.bilimusic.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.auth.AuthManager;
import com.yuuuno224.bilimusic.model.FavoriteFolder;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.model.UserProfile;
import com.yuuuno224.bilimusic.player.PlayerConnection;
import com.yuuuno224.bilimusic.repo.MusicRepository;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.ui.widget.SongAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 乐库页：三个标签。
 * <ol>
 *   <li><b>我喜欢</b> —— 本地收藏（{@link MusicStore}）；</li>
 *   <li><b>最近播放</b> —— 本地播放历史，可一键清空；</li>
 *   <li><b>收藏夹</b> —— B 站账号里的收藏夹，需要登录。</li>
 * </ol>
 *
 * <h3>收藏夹标签为什么是"两级导航"</h3>
 * B 站账号下可以有多个收藏夹，所以这一标签内部还有一层：
 * 先列出收藏夹（{@link FavFolderAdapter}），点进去才是夹内的歌（{@link SongAdapter}）。
 * 这个"进去一层"的状态用 {@link #showingFolderContent} 表示。
 * 它带来一个必须处理的问题：<b>在夹内按返回键，用户期望回到收藏夹列表，而不是退出 App</b>。
 * 处理办法是注册 {@link OnBackPressedCallback}，只在夹内时拦截返回事件。
 *
 * <h3>为什么要在加载收藏夹前先确认用户信息</h3>
 * 收藏夹接口需要用户的 mid（uid）。mid 来自登录时缓存的用户信息，
 * 如果缓存丢了（例如刚清过数据），直接拿 0 去请求会被 B 站拒绝。
 * 所以这里先 {@link AuthManager#refreshProfile} 补一次，再发请求。
 */
public class LibraryFragment extends Fragment {

    /** 由外部（"我的"页快捷入口）指定进来时要选中的标签。 */
    public static final String ARG_TAB = "tab";

    private static final int TAB_FAVORITE = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_FOLDERS = 2;

    private TabLayout tabs;
    private TextView countText;
    private ImageButton actionButton;
    private RecyclerView list;

    private SongAdapter songAdapter;
    private FavFolderAdapter folderAdapter;

    private final List<Song> songs = new ArrayList<>();
    private final List<FavoriteFolder> folders = new ArrayList<>();

    private int currentTab = TAB_FAVORITE;

    /** true = 正在看收藏夹列表；false = 正在看某个收藏夹里的歌。 */
    private boolean showingFolderList = true;
    private FavoriteFolder openedFolder;

    /** 在收藏夹内容内拦截返回键。 */
    private OnBackPressedCallback backCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tabs = view.findViewById(R.id.library_tabs);
        countText = view.findViewById(R.id.library_count);
        actionButton = view.findViewById(R.id.library_clear);
        list = view.findViewById(R.id.library_list);

        songAdapter = new SongAdapter(songs, this::playFrom);
        folderAdapter = new FavFolderAdapter(folders, this::openFolder);

        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(songAdapter);

        tabs.addTab(tabs.newTab().setText("我喜欢"));
        tabs.addTab(tabs.newTab().setText("最近播放"));
        tabs.addTab(tabs.newTab().setText("收藏夹"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                // 切标签时回到"收藏夹列表"这一层
                showingFolderList = true;
                openedFolder = null;
                reload();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // 再次点击"收藏夹"标签：如果在夹内就退回列表，符合用户直觉
                if (tab.getPosition() == TAB_FOLDERS && !showingFolderList) {
                    backToFolders();
                }
            }
        });

        actionButton.setOnClickListener(v -> onActionButtonClicked());

        setupBackHandling();

        int initialTab = getArguments() != null ? getArguments().getInt(ARG_TAB, 0) : 0;
        if (initialTab > 0 && initialTab < tabs.getTabCount()) {
            // select() 会触发 onTabSelected → reload()
            tabs.getTabAt(initialTab).select();
        } else {
            reload();
        }
    }

    /**
     * 注册返回键拦截。
     * <p>用 {@code getViewLifecycleOwner()} 注册，Fragment 的视图销毁时会自动注销，
     * 不需要手动 remove。
     */
    private void setupBackHandling() {
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                backToFolders();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 在收藏夹内部浏览时不重载，否则会打断用户的两级导航
        if (currentTab == TAB_FOLDERS && !showingFolderList) {
            return;
        }
        reload();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (songAdapter != null) {
            songAdapter.release();
            songAdapter = null;
        }
        if (folderAdapter != null) {
            folderAdapter.release();
            folderAdapter = null;
        }
        backCallback = null;
    }

    private void playFrom(List<Song> queue, int position) {
        PlayerConnection.playQueue(requireContext(), queue, position);
    }

    // ------------------------------------------------------------------
    // 标签内容
    // ------------------------------------------------------------------

    private void reload() {
        if (currentTab == TAB_FOLDERS) {
            loadFolders();
            return;
        }
        // 我喜欢 / 最近播放：直接用本地数据，不需要网络
        list.setAdapter(songAdapter);
        actionButton.setEnabled(true);
        songs.clear();
        songs.addAll(currentTab == TAB_FAVORITE ? MusicStore.favorites() : MusicStore.history());
        songAdapter.notifyDataSetChanged();

        countText.setText(songs.size() + " 首");
        // 只有"最近播放"需要清空按钮
        boolean canClear = currentTab == TAB_HISTORY && !songs.isEmpty();
        actionButton.setImageResource(R.drawable.ic_close);
        actionButton.setContentDescription("清空播放历史");
        actionButton.setVisibility(canClear ? View.VISIBLE : View.GONE);
    }

    /** 按钮在两种标签下含义不同：清空历史 / 返回收藏夹列表。 */
    private void onActionButtonClicked() {
        if (currentTab == TAB_FOLDERS && !showingFolderList) {
            backToFolders();
            return;
        }
        if (currentTab == TAB_HISTORY) {
            MusicStore.clearHistory();
            reload();
        }
    }

    // ------------------------------------------------------------------
    // 收藏夹（两级）
    // ------------------------------------------------------------------

    private void loadFolders() {
        list.setAdapter(folderAdapter);
        showingFolderList = true;
        if (backCallback != null) {
            backCallback.setEnabled(false);
        }
        actionButton.setVisibility(View.GONE);

        if (!AuthManager.isLoggedIn()) {
            folders.clear();
            folderAdapter.notifyDataSetChanged();
            countText.setText("未登录，请先在【我的】页登录");
            return;
        }

        UserProfile profile = AuthManager.cachedProfile();
        if (profile == null || profile.mid <= 0) {
            // 缓存里没有 uid（例如刚清过数据），先补一次再请求
            countText.setText("正在获取账号信息…");
            AuthManager.refreshProfile(result -> {
                if (!isViewUsable() || currentTab != TAB_FOLDERS) {
                    return;
                }
                if (result != null && result.mid > 0) {
                    loadFoldersFor(result.mid);
                } else {
                    countText.setText("登录状态已失效，请重新登录");
                }
            });
            return;
        }
        loadFoldersFor(profile.mid);
    }

    private void loadFoldersFor(long mid) {
        countText.setText("加载中…");
        MusicRepository.favoriteFolders(mid,
                new MusicRepository.Callback<List<FavoriteFolder>>() {
                    @Override
                    public void onSuccess(List<FavoriteFolder> result) {
                        // 三重检查：页面还在、标签没切走、还在收藏夹列表这一层
                        if (!isViewUsable() || currentTab != TAB_FOLDERS || !showingFolderList) {
                            return;
                        }
                        folders.clear();
                        folders.addAll(result);
                        folderAdapter.notifyDataSetChanged();
                        countText.setText(folders.size() + " 个收藏夹");
                    }

                    @Override
                    public void onError(String message) {
                        if (!isViewUsable() || currentTab != TAB_FOLDERS || !showingFolderList) {
                            return;
                        }
                        folders.clear();
                        folderAdapter.notifyDataSetChanged();
                        countText.setText(message);
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void openFolder(FavoriteFolder folder) {
        openedFolder = folder;
        showingFolderList = false;
        if (backCallback != null) {
            // 进入夹内才拦截返回键
            backCallback.setEnabled(true);
        }

        list.setAdapter(songAdapter);
        actionButton.setImageResource(R.drawable.ic_arrow_back);
        actionButton.setContentDescription("返回收藏夹列表");
        actionButton.setVisibility(View.VISIBLE);
        countText.setText("加载中…");

        MusicRepository.favoriteResources(folder.id,
                new MusicRepository.Callback<List<Song>>() {
                    @Override
                    public void onSuccess(List<Song> result) {
                        if (!isViewUsable() || currentTab != TAB_FOLDERS || showingFolderList) {
                            return;
                        }
                        songs.clear();
                        songs.addAll(result);
                        songAdapter.notifyDataSetChanged();
                        countText.setText(openedFolder.title + " · " + songs.size() + " 首");
                    }

                    @Override
                    public void onError(String message) {
                        if (!isViewUsable() || currentTab != TAB_FOLDERS || showingFolderList) {
                            return;
                        }
                        songs.clear();
                        songAdapter.notifyDataSetChanged();
                        countText.setText(message);
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void backToFolders() {
        showingFolderList = true;
        openedFolder = null;
        songs.clear();
        if (songAdapter != null) {
            songAdapter.notifyDataSetChanged();
        }
        loadFolders();
    }

    private boolean isViewUsable() {
        return isAdded() && getView() != null;
    }
}

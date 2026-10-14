package com.yuuuno224.bilimusic.ui.search;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.player.PlayerConnection;
import com.yuuuno224.bilimusic.repo.MusicRepository;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.ui.widget.SongAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 搜索页。有两种形态：
 * <ol>
 *   <li><b>搜索首页</b>（没输入关键词时）：搜索历史 + 热门搜索词 + 音乐排行榜 + 最近播放；</li>
 *   <li><b>结果列表</b>（搜过之后）：关键词命中的视频列表。</li>
 * </ol>
 *
 * <h3>这个类最需要注意的三件事</h3>
 * <ol>
 *   <li><b>回调可能来得比页面晚</b>。网络请求是异步的，用户完全可能在结果回来前
 *       就切走了页面。所以每个回调的第一行都要检查页面是否还活着
 *       （{@link #isViewUsable()}），否则会在已销毁的 View 上操作而崩溃——
 *       这是初学 Android 最常踩的坑。</li>
 *   <li><b>热门词和排行榜不用每次进来都请求</b>。它们变化很慢，用静态缓存
 *       + 有效期（{@link #HOME_CACHE_TTL_MS}）就够，否则每次切标签都打一次网络。</li>
 *   <li><b>搜索历史是本地数据</b>，直接读 {@link MusicStore} 即可，不用走网络。</li>
 * </ol>
 */
public class SearchFragment extends Fragment {

    /** 网络拿不到热词时的兜底列表，保证首页不会空着。 */
    private static final String[] FALLBACK_HOT_WORDS = {
            "音乐", "BGM", "纯音乐", "翻唱", "ACG",
            "Lo-fi", "钢琴曲", "古风", "电子音", "爵士"
    };

    private static final int RECENT_LIMIT = 5;
    private static final int RANK_LIMIT = 10;

    /** 首页数据（热词、排行榜）的缓存有效期。 */
    private static final long HOME_CACHE_TTL_MS = 10 * 60 * 1000L;

    // 静态缓存：跨页面重建依然有效，避免每次切标签都重新请求。
    // 只缓存"变化很慢的首页装饰数据"，不缓存搜索结果（那个必须每次都是最新的）。
    private static List<String> cachedHotWords;
    private static long cachedHotWordsAt;
    private static List<Song> cachedRanking;
    private static long cachedRankingAt;

    private EditText searchBox;
    private View searchHome;
    private View historyScroll;
    private ChipGroup historyGroup;
    private ChipGroup hotGroup;
    private View rankSection;
    private RecyclerView rankList;
    private View recentSection;
    private RecyclerView recentList;
    private RecyclerView resultList;
    private TextView hint;
    private View progress;

    private SongAdapter adapter;
    private SongAdapter rankAdapter;
    private SongAdapter recentAdapter;

    private final List<Song> results = new ArrayList<>();
    private final List<Song> ranking = new ArrayList<>();
    private final List<Song> recent = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);
        setupLists();
        setupSearchBox();
    }

    private void bindViews(View view) {
        searchBox = view.findViewById(R.id.search_box);
        searchHome = view.findViewById(R.id.search_home);
        historyScroll = view.findViewById(R.id.history_scroll);
        historyGroup = view.findViewById(R.id.history_group);
        hotGroup = view.findViewById(R.id.hot_group);
        rankSection = view.findViewById(R.id.rank_section);
        rankList = view.findViewById(R.id.rank_list);
        recentSection = view.findViewById(R.id.recent_section);
        recentList = view.findViewById(R.id.recent_list);
        resultList = view.findViewById(R.id.result_list);
        hint = view.findViewById(R.id.search_hint);
        progress = view.findViewById(R.id.search_progress);
    }

    private void setupLists() {
        // 三个列表的点击行为完全一样：把整个列表作为播放队列
        adapter = new SongAdapter(results, this::playFrom);
        resultList.setLayoutManager(new LinearLayoutManager(getContext()));
        resultList.setAdapter(adapter);

        rankAdapter = new SongAdapter(ranking, this::playFrom);
        rankList.setLayoutManager(new LinearLayoutManager(getContext()));
        rankList.setAdapter(rankAdapter);
        // 排行榜嵌在外层 ScrollView 里，交给外层滚动，否则两层滚动会打架
        rankList.setNestedScrollingEnabled(false);

        recentAdapter = new SongAdapter(recent, this::playFrom);
        recentList.setLayoutManager(new LinearLayoutManager(getContext()));
        recentList.setAdapter(recentAdapter);
        recentList.setNestedScrollingEnabled(false);
    }

    private void playFrom(List<Song> songs, int position) {
        PlayerConnection.playQueue(requireContext(), songs, position);
    }

    private void setupSearchBox() {
        ImageButton searchButton = requireView().findViewById(R.id.search_btn);
        searchButton.setOnClickListener(v -> doSearch());
        searchBox.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 首页数据可能已经过期（例如用户在别处播放过歌），重新渲染
        renderHistory();
        renderRecent();
        loadHotWords();
        loadRanking();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 适配器持有播放器监听，必须注销
        if (adapter != null) {
            adapter.release();
            adapter = null;
        }
        if (rankAdapter != null) {
            rankAdapter.release();
            rankAdapter = null;
        }
        if (recentAdapter != null) {
            recentAdapter.release();
            recentAdapter = null;
        }
    }

    // ------------------------------------------------------------------
    // 首页三块内容
    // ------------------------------------------------------------------

    private void renderHistory() {
        List<String> history = MusicStore.searchHistory();
        if (history.isEmpty()) {
            historyScroll.setVisibility(View.GONE);
            return;
        }
        historyScroll.setVisibility(View.VISIBLE);
        fillChips(historyGroup, history);
    }

    private void renderRecent() {
        recent.clear();
        List<Song> all = MusicStore.history();
        recent.addAll(all.subList(0, Math.min(all.size(), RECENT_LIMIT)));
        if (recent.isEmpty()) {
            recentSection.setVisibility(View.GONE);
            return;
        }
        recentSection.setVisibility(View.VISIBLE);
        recentAdapter.notifyDataSetChanged();
    }

    /** 渲染热门搜索词；10 分钟内命中过缓存就不再请求。 */
    private void loadHotWords() {
        if (cachedHotWords != null
                && System.currentTimeMillis() - cachedHotWordsAt < HOME_CACHE_TTL_MS) {
            fillChips(hotGroup, cachedHotWords);
            return;
        }
        MusicRepository.hotWords(new MusicRepository.Callback<List<String>>() {
            @Override
            public void onSuccess(List<String> words) {
                // 关键：先确认页面还在，再碰 View
                if (!isViewUsable()) {
                    return;
                }
                if (words == null || words.isEmpty()) {
                    fillChips(hotGroup, Arrays.asList(FALLBACK_HOT_WORDS));
                    return;
                }
                cachedHotWords = words;
                cachedHotWordsAt = System.currentTimeMillis();
                fillChips(hotGroup, words);
            }

            @Override
            public void onError(String message) {
                if (!isViewUsable()) {
                    return;
                }
                // 网络失败也要让首页有东西可点
                fillChips(hotGroup, Arrays.asList(FALLBACK_HOT_WORDS));
            }
        });
    }

    /** 渲染音乐排行榜；同样带缓存。 */
    private void loadRanking() {
        if (cachedRanking != null
                && System.currentTimeMillis() - cachedRankingAt < HOME_CACHE_TTL_MS) {
            showRanking(cachedRanking);
            return;
        }
        MusicRepository.musicRanking(new MusicRepository.Callback<List<Song>>() {
            @Override
            public void onSuccess(List<Song> songs) {
                if (!isViewUsable()) {
                    return;
                }
                cachedRanking = songs;
                cachedRankingAt = System.currentTimeMillis();
                showRanking(songs);
            }

            @Override
            public void onError(String message) {
                if (!isViewUsable()) {
                    return;
                }
                // 排行榜拿不到就整块隐藏，首页仍然可用
                rankSection.setVisibility(View.GONE);
            }
        });
    }

    private void showRanking(List<Song> songs) {
        ranking.clear();
        if (songs != null) {
            ranking.addAll(songs.subList(0, Math.min(songs.size(), RANK_LIMIT)));
        }
        if (ranking.isEmpty()) {
            rankSection.setVisibility(View.GONE);
            return;
        }
        rankSection.setVisibility(View.VISIBLE);
        rankAdapter.notifyDataSetChanged();
    }

    private void fillChips(ChipGroup group, List<String> words) {
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (String keyword : words) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_search_chip, group, false);
            chip.setText(keyword);
            chip.setOnClickListener(v -> {
                searchBox.setText(keyword);
                doSearch();
            });
            group.addView(chip);
        }
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    private void doSearch() {
        String keyword = searchBox.getText().toString().trim();
        if (keyword.isEmpty()) {
            showHome();
            return;
        }

        // 搜索历史是本地操作，立刻记录并刷新 chips
        MusicStore.addSearchHistory(keyword);
        renderHistory();

        showResults();
        progress.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);

        MusicRepository.search(keyword, new MusicRepository.Callback<List<Song>>() {
            @Override
            public void onSuccess(List<Song> songs) {
                if (!isViewUsable()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                results.clear();
                results.addAll(songs);
                adapter.notifyDataSetChanged();
                if (songs.isEmpty()) {
                    showHint("没有找到相关内容");
                }
            }

            @Override
            public void onError(String message) {
                if (!isViewUsable()) {
                    return;
                }
                progress.setVisibility(View.GONE);
                showHint(message);
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showHome() {
        searchHome.setVisibility(View.VISIBLE);
        resultList.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void showResults() {
        searchHome.setVisibility(View.GONE);
        resultList.setVisibility(View.VISIBLE);
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
    }

    /**
     * 页面是否还能安全地操作 View。
     * <p>{@code isAdded()} 判断 Fragment 是否还挂在 Activity 上；
     * {@code getView() != null} 判断视图是否还没被销毁（在 onDestroyView 之后会变成 null）。
     * 两者都要判断，只判断一个仍可能崩。
     */
    private boolean isViewUsable() {
        return isAdded() && getView() != null;
    }
}

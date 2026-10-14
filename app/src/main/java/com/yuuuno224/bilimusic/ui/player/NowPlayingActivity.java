package com.yuuuno224.bilimusic.ui.player;

import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.core.AppExecutors;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.player.PlayerConnection;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.util.ImageLoader;

/**
 * 沉浸式播放页：大封面 + 进度条 + 上一首/播放/下一首 + 循环模式 + 播放队列。
 *
 * <h3>进度条为什么不只用监听，而要靠定时器</h3>
 * 播放器只在"状态变化"时回调（播放、暂停、切歌），但进度是<b>连续变化</b>的，
 * 没有任何事件会每秒通知你。所以必须自己起一个 500ms 的循环去读
 * {@code getCurrentPosition()} 并刷新进度条。
 *
 * <h3>这个定时器的两个坑</h3>
 * <ol>
 *   <li><b>页面不可见时要停掉</b>：否则用户切到后台后定时器还在跑，白耗电。
 *       所以在 {@code onResume} 启动、{@code onPause} 停止。</li>
 *   <li><b>用户拖动进度条时要暂停刷新</b>：不然手指还没松开，
 *       定时器就把进度条拽回当前播放位置，看起来像"拖不动"。
 *       用 {@link #dragging} 标记实现。</li>
 * </ol>
 */
public class NowPlayingActivity extends AppCompatActivity implements PlayerConnection.Listener {

    /** 进度刷新间隔。500ms 是流畅度与耗电的折中。 */
    private static final long PROGRESS_INTERVAL_MS = 500L;

    /**
     * 进度条的刻度总数，必须与 {@code activity_now_playing.xml} 里的
     * {@code android:max="1000"} 保持一致。
     * <p>用它把"0~1000 的刻度"和"毫秒"互相换算。
     */
    private static final int SEEK_BAR_MAX = 1000;

    private ImageView cover;
    private TextView title;
    private TextView up;
    private TextView currentTime;
    private TextView totalTime;
    private SeekBar seekBar;
    private ImageButton playPause;
    private ImageButton modeButton;
    private ImageButton favoriteButton;

    private final Handler handler = AppExecutors.main();

    /** 用户正在拖动进度条时为 true，此时不刷新进度显示。 */
    private boolean dragging;

    private Toast modeToast;
    private ImageLoader.Request coverRequest;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);

        bindViews();
        setupControls();

        // 连接是异步的，连上之后再注册监听并刷新一次界面
        PlayerConnection.connect(this, controller -> {
            PlayerConnection.addListener(this);
            onPlayerChanged();
            applyRepeatUi();
        });
    }

    private void bindViews() {
        cover = findViewById(R.id.np_cover);
        title = findViewById(R.id.np_title);
        up = findViewById(R.id.np_up);
        currentTime = findViewById(R.id.np_cur);
        totalTime = findViewById(R.id.np_total);
        seekBar = findViewById(R.id.np_seek);
        playPause = findViewById(R.id.np_play_pause);
        modeButton = findViewById(R.id.np_mode);
        favoriteButton = findViewById(R.id.np_fav);
    }

    private void setupControls() {
        ImageButton prev = findViewById(R.id.np_prev);
        ImageButton next = findViewById(R.id.np_next);
        ImageButton close = findViewById(R.id.np_close);

        close.setOnClickListener(v -> finish());
        playPause.setOnClickListener(v -> PlayerConnection.toggle());
        next.setOnClickListener(v -> PlayerConnection.next());
        prev.setOnClickListener(v -> PlayerConnection.prev());
        findViewById(R.id.np_playlist).setOnClickListener(v -> PlaylistDialog.show(this));

        modeButton.setOnClickListener(v -> cycleRepeatMode());
        favoriteButton.setOnClickListener(v -> toggleFavorite());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                // 拖动过程中实时显示目标时间，但不动播放器
                if (fromUser) {
                    Player player = PlayerConnection.get();
                    long duration = player == null ? 0 : player.getDuration();
                    if (duration > 0) {
                        long targetMs = duration * progress / SEEK_BAR_MAX;
                        currentTime.setText(Song.durationText(targetMs / 1000));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                Player player = PlayerConnection.get();
                if (player == null) {
                    return;
                }
                long duration = player.getDuration();
                if (duration > 0) {
                    player.seekTo(duration * bar.getProgress() / SEEK_BAR_MAX);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 生命周期：定时器只在可见时跑
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见就停掉进度刷新，避免无谓耗电
        handler.removeCallbacks(progressTick);
    }

    @Override
    protected void onDestroy() {
        PlayerConnection.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (coverRequest != null) {
            coverRequest.cancel();
            coverRequest = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 进度
    // ------------------------------------------------------------------

    private void updateProgress() {
        Player player = PlayerConnection.get();
        if (player == null || player.getPlaybackState() == Player.STATE_IDLE || dragging) {
            return;
        }
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        if (duration <= 0) {
            // 时长还没解析出来（刚切歌的一瞬间），只显示已播放时间
            currentTime.setText(Song.durationText(position / 1000));
            return;
        }
        seekBar.setProgress((int) (position * SEEK_BAR_MAX / duration));
        currentTime.setText(Song.durationText(position / 1000));
        totalTime.setText(Song.durationText(duration / 1000));
    }

    // ------------------------------------------------------------------
    // 循环模式
    // ------------------------------------------------------------------

    /** 三档循环：顺序 → 列表循环 → 单曲循环 → 顺序。 */
    private void cycleRepeatMode() {
        Player player = PlayerConnection.get();
        if (player == null) {
            return;
        }
        int current = player.getRepeatMode();
        int next;
        if (current == Player.REPEAT_MODE_OFF) {
            next = Player.REPEAT_MODE_ALL;
        } else if (current == Player.REPEAT_MODE_ALL) {
            next = Player.REPEAT_MODE_ONE;
        } else {
            next = Player.REPEAT_MODE_OFF;
        }
        player.setRepeatMode(next);
        applyRepeatUi();

        String label = next == Player.REPEAT_MODE_ALL ? "列表循环"
                : next == Player.REPEAT_MODE_ONE ? "单曲循环" : "顺序播放";
        if (modeToast != null) {
            modeToast.cancel();
        }
        modeToast = Toast.makeText(this, label, Toast.LENGTH_SHORT);
        modeToast.show();
    }

    /**
     * 以播放器<b>实际</b>的循环模式为准刷新按钮。
     * <p>读实际值而不是自己记一个变量，避免界面与播放器状态不一致。
     */
    private void applyRepeatUi() {
        Player player = PlayerConnection.get();
        int repeatMode = player == null ? Player.REPEAT_MODE_OFF : player.getRepeatMode();
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            modeButton.setImageResource(R.drawable.ic_repeat_one);
        } else if (repeatMode == Player.REPEAT_MODE_ALL) {
            modeButton.setImageResource(R.drawable.ic_repeat);
        } else {
            modeButton.setImageResource(R.drawable.ic_order);
        }
        modeButton.setColorFilter(ContextCompat.getColor(this, R.color.accent));
    }

    // ------------------------------------------------------------------
    // 收藏
    // ------------------------------------------------------------------

    private void toggleFavorite() {
        Song song = PlayerConnection.currentSong();
        if (song == null) {
            return;
        }
        boolean nowFavorite = MusicStore.toggleFavorite(song);
        applyFavorite(nowFavorite);
        Toast.makeText(this, nowFavorite ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    private void applyFavorite(boolean favorite) {
        favoriteButton.setImageResource(
                favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        favoriteButton.setColorFilter(ContextCompat.getColor(this,
                favorite ? R.color.heart_red : R.color.on_bg_secondary));
    }

    // ------------------------------------------------------------------
    // 播放器状态 → 界面
    // ------------------------------------------------------------------

    /**
     * 播放器状态变化。由 {@link PlayerConnection} 保证在主线程调用，
     * 并已做过"一帧内多次变化合并成一次"的处理。
     */
    @Override
    public void onPlayerChanged() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Song song = PlayerConnection.currentSong();
        if (song == null) {
            title.setText("尚未播放");
            up.setText("");
            cover.setImageResource(R.drawable.ic_music_note);
            return;
        }

        title.setText(song.title);
        up.setText(song.up);

        if (coverRequest != null) {
            coverRequest.cancel();
        }
        coverRequest = ImageLoader.load(song.coverLarge(), bitmap -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (bitmap != null) {
                cover.setImageBitmap(bitmap);
            } else {
                cover.setImageResource(R.drawable.ic_music_note);
            }
        });

        playPause.setImageResource(PlayerConnection.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        applyFavorite(MusicStore.isFavorite(song.bvid));
        applyRepeatUi();
    }
}

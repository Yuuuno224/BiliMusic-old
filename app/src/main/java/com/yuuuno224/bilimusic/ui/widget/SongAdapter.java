package com.yuuuno224.bilimusic.ui.widget;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.player.PlayerConnection;
import com.yuuuno224.bilimusic.store.MusicStore;
import com.yuuuno224.bilimusic.util.ImageLoader;

import java.util.List;

/**
 * 歌曲列表适配器：搜索页、排行榜、最近播放、收藏、收藏夹内容全都复用它。
 *
 * <h3>它做三件事</h3>
 * <ol>
 *   <li>绑定一行数据：标题、UP 主 + 时长、封面、收藏心形、播放/暂停按钮；</li>
 *   <li>点击整行 → 把<b>整个列表</b>和位置一起交给 {@link PlayerConnection}，
 *       于是播放队列就等于用户当前看到的这个列表；</li>
 *   <li>高亮正在播放的那一行（通过监听播放器状态）。</li>
 * </ol>
 *
 * <h3>两个容易写错的地方</h3>
 * <ul>
 *   <li><b>图片请求要能取消</b>：RecyclerView 会复用 itemView。
 *       如果上一行发起的图片请求回来时，这一行已经换成了别的歌，图片就会贴到错误的行上。
 *       所以每个 Holder 记住自己发起的请求，重新绑定前先取消旧的。</li>
 *   <li><b>不要一有变化就 {@code notifyDataSetChanged()}</b>：那会让整个列表重新绑定、
 *       重新下载所有封面。播放状态变化只影响"旧的那一行"和"新的那一行"，
 *       用 {@code notifyItemChanged} 精确刷新这两行就够了。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * 构造函数里就把自己注册成 {@link PlayerConnection.Listener}，
 * 所以页面销毁时<b>必须</b>调用 {@link #release()}，否则适配器会被一直持有（内存泄漏）。
 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder>
        implements PlayerConnection.Listener {

    /** 点击整行时的回调，参数是完整列表和被点的位置。 */
    public interface OnSongClick {
        void onClick(List<Song> songs, int position);
    }

    private final List<Song> songs;
    private final OnSongClick click;

    /** 上一次高亮的 bvid，用来算出"哪两行需要刷新"。 */
    private String highlightedBvid;

    public SongAdapter(List<Song> songs, OnSongClick click) {
        this.songs = songs;
        this.click = click;
        PlayerConnection.addListener(this);
    }

    /**
     * 播放器状态变化：只刷新受影响的两行（旧的高亮行、新的高亮行）。
     * <p>本方法由 {@link PlayerConnection} 保证在主线程调用。
     */
    @Override
    public void onPlayerChanged() {
        String now = PlayerConnection.currentBvid();
        String previous = highlightedBvid;
        highlightedBvid = now;

        for (int i = 0; i < songs.size(); i++) {
            String bvid = songs.get(i).bvid;
            boolean wasHighlighted = previous != null && previous.equals(bvid);
            boolean isHighlighted = now != null && now.equals(bvid);
            if (wasHighlighted || isHighlighted) {
                notifyItemChanged(i);
            }
        }
    }

    /** 页面销毁时调用，注销播放器监听。 */
    public void release() {
        PlayerConnection.removeListener(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Song song = songs.get(position);

        holder.title.setText(song.title);
        holder.subtitle.setText(song.up + " · " + song.durationText());

        // 先取消上一张图的请求，避免"图片贴错行"
        if (holder.coverRequest != null) {
            holder.coverRequest.cancel();
        }
        holder.coverRequest = ImageLoader.load(song.coverSmall(), bitmap -> {
            if (bitmap != null) {
                holder.cover.setImageBitmap(bitmap);
            } else {
                holder.cover.setImageResource(R.drawable.ic_music_note);
            }
        });

        applyFavorite(holder.fav, MusicStore.isFavorite(song.bvid));

        holder.itemView.setOnClickListener(view -> {
            // 用 getBindingAdapterPosition()：列表内容可能已经变了，position 参数可能失效
            int current = holder.getBindingAdapterPosition();
            if (current != RecyclerView.NO_POSITION && click != null) {
                click.onClick(songs, current);
            }
        });

        holder.fav.setOnClickListener(view -> {
            boolean nowFavorite = MusicStore.toggleFavorite(song);
            applyFavorite(holder.fav, nowFavorite);
        });

        // 当前播放行：图标显示暂停（点它等于暂停），并用主题色高亮
        String currentBvid = PlayerConnection.currentBvid();
        boolean isCurrent = currentBvid != null && currentBvid.equals(song.bvid);
        boolean playing = isCurrent && PlayerConnection.isPlaying();
        holder.more.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        holder.more.setColorFilter(ContextCompat.getColor(holder.more.getContext(),
                isCurrent ? R.color.accent : R.color.on_bg_secondary));

        holder.more.setOnClickListener(view -> {
            if (isCurrent) {
                // 点的是当前这首：切换播放 / 暂停
                PlayerConnection.toggle();
                return;
            }
            int current = holder.getBindingAdapterPosition();
            if (current != RecyclerView.NO_POSITION && click != null) {
                click.onClick(songs, current);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        // 这一行被回收了，还在路上的图片请求已经没意义
        if (holder.coverRequest != null) {
            holder.coverRequest.cancel();
            holder.coverRequest = null;
        }
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    private static void applyFavorite(ImageButton button, boolean favorite) {
        button.setImageResource(
                favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        button.setColorFilter(ContextCompat.getColor(button.getContext(),
                favorite ? R.color.heart_red : R.color.on_bg_secondary));
    }

    static class Holder extends RecyclerView.ViewHolder {

        final ImageView cover;
        final TextView title;
        final TextView subtitle;
        final ImageButton fav;
        final ImageButton more;

        /** 当前这一行正在进行的封面加载，用于取消。 */
        ImageLoader.Request coverRequest;

        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.item_cover);
            title = itemView.findViewById(R.id.item_title);
            subtitle = itemView.findViewById(R.id.item_subtitle);
            fav = itemView.findViewById(R.id.item_fav);
            more = itemView.findViewById(R.id.item_next);
        }
    }
}

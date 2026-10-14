package com.yuuuno224.bilimusic.ui.player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.RecyclerView;

import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.model.Song;
import com.yuuuno224.bilimusic.player.MediaItemFactory;

/**
 * 播放队列适配器：点击某一行跳过去播放，点垃圾桶从队列里删掉。
 *
 * <h3>它和 SongAdapter 的区别</h3>
 * {@code SongAdapter} 渲染的是"一个普通的歌曲列表"（数据源是 List&lt;Song&gt;），
 * 而这个渲染的是<b>播放器自己的队列</b>（数据源是 {@link Player}）。
 * 所以它的 {@code getItemCount()} 等方法都是实时问播放器要，而不是读一个 List。
 * 好处是永远和播放器保持一致；代价是任何增删都必须手动 {@code notifyDataSetChanged()}，
 * 因为播放器没法"观察"适配器。
 *
 * <h3>为什么删除后要重绑整个列表</h3>
 * 删掉第 3 项后，后面的每一项都往前挪了一位，
 * 局部刷新还要自己算下标，不如整体重绑来得可靠（队列最多几十首，代价可以忽略）。
 */
public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.Holder> {

    private final Player player;

    /** 队列变化后的通知（用来更新标题里的"N 首"），可为 null。 */
    private final Runnable onQueueChanged;

    public PlaylistAdapter(Player player, Runnable onQueueChanged) {
        this.player = player;
        this.onQueueChanged = onQueueChanged;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_playlist_song, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Song song = MediaItemFactory.songOf(player.getMediaItemAt(position));
        if (song != null) {
            holder.title.setText(song.title);
            holder.up.setText(song.up);
        } else {
            holder.title.setText("未知曲目");
            holder.up.setText("");
        }

        boolean isCurrent = position == player.getCurrentMediaItemIndex();
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                isCurrent ? R.color.accent : R.color.on_bg));

        // 点击：跳到这一首并播放
        holder.itemView.setOnClickListener(view -> {
            int target = holder.getBindingAdapterPosition();
            if (target == RecyclerView.NO_POSITION) {
                return;
            }
            player.seekTo(target, 0);
            player.play();
            notifyDataSetChanged();
        });

        // 垃圾桶：真的从播放器队列里删掉
        holder.delete.setOnClickListener(view -> {
            int target = holder.getBindingAdapterPosition();
            if (target == RecyclerView.NO_POSITION || target >= player.getMediaItemCount()) {
                return;
            }
            player.removeMediaItem(target);
            notifyDataSetChanged();
            if (onQueueChanged != null) {
                onQueueChanged.run();
            }
        });
    }

    @Override
    public int getItemCount() {
        return player.getMediaItemCount();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final TextView title;
        final TextView up;
        final ImageButton delete;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.pl_title);
            up = itemView.findViewById(R.id.pl_up);
            delete = itemView.findViewById(R.id.pl_delete);
        }
    }
}

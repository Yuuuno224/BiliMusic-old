package com.yuuuno224.bilimusic.ui.player;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.yuuuno224.bilimusic.R;
import com.yuuuno224.bilimusic.player.PlayerConnection;

/**
 * 播放队列弹窗（迷你播放条和播放页共用）。
 *
 * <h3>为什么是一个静态工具方法而不是 DialogFragment</h3>
 * 它不需要在配置变化（旋转屏幕）后存活，也不需要独立的事务管理，
 * 只是一次性的"看看队列、删两首"。用最简单的 {@link BottomSheetDialog} 足够了。
 *
 * <h3>队列数据从哪来</h3>
 * 直接读 {@link PlayerConnection#get()} 这个 MediaController。
 * 也就是说队列的"唯一真相"在播放服务里，界面只是它的一个视图——
 * 删掉一首歌时是<b>真的</b>从播放器队列里删掉，不是在界面列表里删。
 * 界面之所以能同步，是因为 {@link PlayerConnection} 监听了 {@code onTimelineChanged}
 * 并通知所有页面刷新。
 */
public final class PlaylistDialog {

    private PlaylistDialog() {
    }

    /** 弹出播放队列。队列为空时只提示，不弹空窗口。 */
    public static void show(@NonNull Context context) {
        Player player = PlayerConnection.get();
        if (player == null || player.getMediaItemCount() == 0) {
            Toast.makeText(context, "播放队列为空", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = dialog.getLayoutInflater().inflate(R.layout.dialog_playlist, null);

        TextView countText = content.findViewById(R.id.playlist_count);
        RecyclerView list = content.findViewById(R.id.playlist_list);
        list.setLayoutManager(new LinearLayoutManager(context));

        countText.setText(player.getMediaItemCount() + " 首");
        // 删歌之后要同步标题里的数量，所以把刷新动作传给适配器
        PlaylistAdapter adapter = new PlaylistAdapter(player,
                () -> countText.setText(player.getMediaItemCount() + " 首"));
        list.setAdapter(adapter);

        dialog.setContentView(content);
        dialog.show();
    }
}

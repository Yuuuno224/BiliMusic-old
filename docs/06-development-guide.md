# 06 · 开发指南

> 目标：想做某个改动时，知道从哪个文件下手、按什么顺序改、容易在哪出错。
> 每节都是"照着做"的步骤，末尾附自检清单。

---

## 0. 动手前先做三件事

```powershell
# 1. 确认现在能编译通过（基线干净，改坏了才知道是自己改坏的）
.\build.cmd

# 2. 不要在 main 上直接改
git checkout -b feature/我的改动

# 3. 确认你理解了要改的那条流程
#    见 04-core-flows.md
```

---

## 1. 加一个新页面（独立 Activity）

适合"全新的、不属于底部三栏的界面"，例如"设置页"。

### 步骤

**① 写布局** `app/src/main/res/layout/activity_settings.xml`

**② 写 Activity** `app/src/main/java/com/yuuuno224/bilimusic/ui/settings/SettingsActivity.java`

```java
package com.yuuuno224.bilimusic.ui.settings;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.yuuuno224.bilimusic.R;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // 这里写你的逻辑
    }
}
```

**③ 在 `AndroidManifest.xml` 里注册**

```xml
<activity
    android:name=".ui.settings.SettingsActivity"
    android:exported="false" />
```

> `android:exported="false"` 表示"只允许本 App 启动它"。
> **只有带 `LAUNCHER` intent-filter 的 Activity 才需要（也必须）写 `exported="true"`。**

**④ 在需要的地方打开它**

```java
startActivity(new Intent(requireContext(), SettingsActivity.class));
```

### 如果这个页面要显示播放状态

```java
public class SettingsActivity extends AppCompatActivity implements PlayerConnection.Listener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        PlayerConnection.acquire(this);        // 确保连接已建立
        PlayerConnection.addListener(this);    // 注册监听
    }

    @Override
    public void onPlayerChanged() {
        // 刷新界面。保证在主线程调用
    }

    @Override
    protected void onDestroy() {
        PlayerConnection.removeListener(this);  // ← 必须！否则内存泄漏
        super.onDestroy();
    }
}
```

### 如果页面里有列表

复用 `SongAdapter`：

```java
private final List<Song> songs = new ArrayList<>();
private SongAdapter adapter;

adapter = new SongAdapter(songs, (all, position) ->
        PlayerConnection.playQueue(this, all, position));
recyclerView.setLayoutManager(new LinearLayoutManager(this));
recyclerView.setAdapter(adapter);

@Override
protected void onDestroy() {
    adapter.release();   // ← 必须！SongAdapter 构造时就注册了播放器监听
    super.onDestroy();
}
```

---

## 2. 加一个底部导航标签

比独立 Activity 多两步，因为它要接进 `MainActivity` 的 Fragment 切换机制。

**① 加菜单项** `res/menu/bottom_nav.xml`

```xml
<item
    android:id="@+id/nav_settings"
    android:icon="@drawable/ic_info"
    android:title="设置" />
```

**② 加字符串**（可选，但推荐）`res/values/strings.xml`

**③ 在 `MainActivity.setupBottomNav()` 里加一个分支**

```java
nav.setOnItemSelectedListener(item -> {
    int id = item.getItemId();
    if (id == R.id.nav_search) { show(new SearchFragment(), "search"); return true; }
    if (id == R.id.nav_library) { /* …原样保留… */ return true; }
    if (id == R.id.nav_me) { show(new MeFragment(), "me"); return true; }
    // ↓ 新增
    if (id == R.id.nav_settings) { show(new SettingsFragment(), "settings"); return true; }
    return false;
});
```

**④ 写 Fragment**（结构参考 `MeFragment`）

### 注意

- `show()` 用的是 `replace()`，所以**每次切换标签都会重建 Fragment**。
  如果新页面有"进过一次就不用再拉"的数据，参考 `SearchFragment` 的静态缓存写法
  （注意：静态缓存是进程内共享的，要接受这个副作用）。
- 底部导航最多建议放 5 个标签，再多 Material 组件会挤在一起。

---

## 3. 改播放逻辑

播放是最容易改出问题的地方。先明确一件事：

> **`PlaybackService` 是队列的唯一真相。** 界面只是它的视图。
> 任何"改队列"的操作都应该发给 Service，而不是在界面里维护一份自己的列表。

### 3.1 加一条新的自定义命令

场景举例：加一个"立刻播放这首歌并清空队列"的按钮。

**① 在 `PlaybackService` 里定义命令名**

```java
public static final String CMD_PLAY_SINGLE = "bilimusic.PLAY_SINGLE";
```

**② 在 `onConnect` 里注册（不注册客户端调用会被拒绝）**

```java
SessionCommands commands = new SessionCommands.Builder()
        .add(new SessionCommand(CMD_PLAY_QUEUE, Bundle.EMPTY))
        .add(new SessionCommand(CMD_PLAY_NEXT, Bundle.EMPTY))
        .add(new SessionCommand(CMD_ADD_QUEUE, Bundle.EMPTY))
        .add(new SessionCommand(CMD_PLAY_SINGLE, Bundle.EMPTY))   // ← 新增
        .build();
```

**③ 在 `onCustomCommand` 的 switch 里处理**

```java
case CMD_PLAY_SINGLE:
    resolveQueueAndLoad(Collections.singletonList(song), 0);   // 复用现有方法
    break;
```

**④ 在 `PlayerConnection` 里加一个静态方法**

```java
public static void playSingle(Context context, Song song) {
    sendCommand(context, PlaybackService.CMD_PLAY_SINGLE,
            PlaybackService.queueBundle(Collections.singletonList(song), 0));
}
```

**⑤ 界面调用 `PlayerConnection.playSingle(ctx, song)`**

### 3.2 改队列解析策略

`resolveQueueAndLoad` 目前的策略是"**整条队列全部解析完才载入**"。
好处是通知栏的上一首/下一首立刻可用、循环模式立刻正确；代价是网络差时要等。

如果想改成"先播点的那首，其余后台补齐"，需要处理三个额外问题：

1. 插入位置要按原顺序（不能简单 append）
2. 已插入的项与用户后来点的歌要能对上（下标会变）
3. `mappedStart` 的映射逻辑要重写

**建议**：真要改，先只改"优先解析 `startIndex` 那一首，其余仍然等"，
既能加快点击响应（那次请求先发出去），又不破坏现有顺序保证。
改完务必测：**点第 N 首是否真的播第 N 首**（这就是那个下标映射的意义）。

### 3.3 改循环模式

现在三档：`REPEAT_MODE_OFF`（顺序）→ `REPEAT_MODE_ALL`（列表循环）→ `REPEAT_MODE_ONE`（单曲循环）。

改法：`NowPlayingActivity.cycleRepeatMode()` 里的判断链，
加上 `applyRepeatUi()` 的图标映射，以及 `applyRepeatUi` 里读取的是**播放器实际值**这一点别改成自记变量。

### 3.4 音频地址失效的重试逻辑

参数在 `PlaybackService`：

```java
private static final int MAX_ERROR_RETRIES = 2;
```

`handlePlaybackError()` 的逻辑是"重新解析当前歌的地址 → 替换 MediaItem → 回到原位置"。
**不要去掉重试次数上限**：视频真的下架时，无限重试会变成死循环。

### 3.5 播放相关的三条纪律

| 纪律 | 为什么 |
|---|---|
| `setMediaItems` / `prepare` / `play` 必须在主线程 | ExoPlayer 的 API 不是线程安全的 |
| 解析地址（网络请求）必须在后台线程 | 否则卡住主线程 → ANR |
| 不要缓存音频地址到本地文件 | 地址带时效，存了第二天也放不了 |

---

## 4. 改本地存储

### 4.1 加一个新字段（安全）

比如给 `Song` 加一个"是否已下载"标记：

```java
public class Song {
    // …现有字段…
    public boolean downloaded;      // 新增
}
```

**这是安全的**：老用户的 `music_store.json` 里没有这个键，
Gson 会把它留成默认值 `false`。不需要写迁移代码。

### 4.2 改字段名（危险）

```java
- public String up;
+ public String uploader;   // ← 老数据里的 "up" 读不进来了
```

**这会让老用户丢掉这个字段的数据**（其它字段还在）。
如果非改不可，有两种做法：

- **加 `@SerializedName`**（推荐，成本最低）：
  ```java
  @SerializedName("up")
  public String uploader;
  ```
- **写迁移**：读到旧结构后手动转换再写回。

> 注意：`model` 里的类**故意不加 Gson 注解**（保持 model 不依赖 Gson）。
> 但 `MusicStore` 确实用 Gson 序列化 `Song`，
> 所以真要改名字时，加一个 `@SerializedName` 是合理的例外——记得在注释里说明原因。

### 4.3 加一个新列表（比如"下载记录"）

**① 在 `MusicStore` 里加字段和读写方法**

```java
private static List<Song> downloads = new ArrayList<>();

public static synchronized void addDownload(Song song) {
    if (song == null || song.bvid == null) return;
    downloads.remove(song);
    downloads.add(0, song);
    persist();
}

public static synchronized List<Song> downloads() {
    return new ArrayList<>(downloads);
}
```

**② 在 `StoreData` 里加字段**

```java
static class StoreData {
    List<Song> favorites;
    List<Song> history;
    List<String> searchHistory;
    List<Song> downloads;      // 新增
}
```

**③ 在 `load()` 里读出（记得判空）**

```java
downloads = data.downloads != null ? data.downloads : new ArrayList<>();
```

**④ 在 `persist()` 的 StoreData 构造调用里带上它**

### 4.4 存储相关的纪律

| 纪律 | 为什么 |
|---|---|
| 不要自己去 `new FileOutputStream` | 落盘必须走 `persist()`，否则失去原子写保护 |
| 不要在后台线程读 `favorites()` 之外的内部字段 | 所有公开方法都是 `synchronized`，够用了 |
| 不要删掉 `writeAtomically` 的临时文件步骤 | 那一步是防"写一半被杀进程导致数据全丢"的 |
| 列表上限改动要三思 | 历史 200 条是流量/内存/实用性的折中 |

---

## 5. 改界面样式

### 5.1 改配色

只改 `res/values/colors.xml` 里的颜色值，全局生效：

```xml
<color name="bg">#121212</color>          <!-- 背景 -->
<color name="surface">#1E1E1E</color>     <!-- 卡片 -->
<color name="surface_high">#2A2A2A</color><!-- 迷你播放条 -->
<color name="accent">#1DB9C4</color>      <!-- 主色（亮青） -->
<color name="on_bg">#FFFFFF</color>       <!-- 主文字 -->
<color name="on_bg_secondary">#B3B3B3</color>
<color name="heart_red">#E91E63</color>   <!-- 收藏心形 -->
```

### 5.2 注意"硬编码颜色"的坑

有些地方的颜色是**写在 Java 里**的，改 `colors.xml` 不会生效。
例如 `NowPlayingActivity.applyRepeatUi()` / `applyFavorite()` 用的是 `R.color.accent`（这没问题），
但 `res/values/themes.xml` 里有几处直接写了十六进制（如 `colorOnPrimary` = `#003033`），
`fragment_me.xml` 的登录按钮也写死了 `android:textColor="#003033"`。

**改配色时请全局搜索 `#` 开头的颜色值**，别漏掉这些。

### 5.3 改文案

界面文字**大部分硬编码在 Java 里**（这是本项目的已知取舍，见
[07 篇](07-faq-and-pitfalls.md)）。只有这几处配置在 `strings.xml`：

`app_name` / `nav_search` / `nav_library` / `nav_me` / `search_hint` /
`search_history` / `login_title` / `login_desc` / `open_auth` / `copy_link` / `disclaimer`

想做国际化就必须先把硬编码文案搬进 `strings.xml`，那是一次不小的重构。

---

## 6. 改构建配置

### 6.1 加一个依赖

在 `app/build.gradle` 的 `dependencies { }` 里加，然后**重新编译**。
加依赖前先问：能不能不加？本项目的定位是"少依赖、能读懂"。

### 6.2 改 SDK 版本

```groovy
android {
    compileSdkVersion "android-36.1"   // 用什么 SDK 编译
    defaultConfig {
        minSdk 26                       // 最低支持 Android 8.0
        targetSdk 35                    // 面向哪个版本做适配
    }
}
```

| 改哪个 | 影响 |
|---|---|
| `compileSdkVersion` | 能用哪些新 API。改完要确保 SDK 里装了对应 platform |
| `minSdk` | **调高会让老设备装不上**。调低则要处理更多兼容分支 |
| `targetSdk` | 调高会启用新的系统限制（例如 Android 13 的通知权限就是 targetSdk 33+ 的行为） |

### 6.3 改 ABI 分包

```groovy
splits {
    abi {
        enable true
        reset()
        include 'arm64-v8a', 'armeabi-v7a'
        universalApk false      // 改成 true 会额外产出一个"万能包"，体积明显变大
    }
}
```

### 6.4 开启代码混淆

```groovy
buildTypes {
    release {
        minifyEnabled true      // 当前是 false
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                      'proguard-rules.pro'
    }
}
```

**开启前必读** `app/proguard-rules.pro`：里面已经写好了 Gson 需要的 keep 规则。
混淆最容易踩的坑就是"release 包能装但一打开就白屏/数据全空"，
原因通常是某个被 Gson 反射读写的类被改名了。

**建议**：开混淆后**必须**在真机上完整过一遍所有功能（搜索 / 播放 / 登录 / 收藏 / 收藏夹）。

---

## 7. 常见错误写法对照表

这 8 条是 Android 初学者最容易犯的错，本项目里全部按"正确"那一列写。

| # | ❌ 错误写法 | ✅ 正确写法 | 后果 |
|---|---|---|---|
| 1 | 在主线程里 `BiliHttp.get(...)` | 用 `MusicRepository` 的方法（内部切到 `bili-io`） | 界面卡住 → ANR |
| 2 | 在子线程里 `textView.setText(...)` | `AppExecutors.runOnMain(() -> ...)` | `CalledFromWrongThreadException` 崩溃 |
| 3 | 回调里直接操作 View | 先判断 `isAdded() && getView() != null` | 页面已销毁时崩溃 |
| 4 | `PlayerConnection.addListener(this)` 后不注销 | `onDestroy` 里 `removeListener(this)` | 内存泄漏 |
| 5 | 用 `position` 参数点列表项 | `holder.getBindingAdapterPosition()` 并判 `NO_POSITION` | 点错行 / 越界崩溃 |
| 6 | 播放状态一变就 `notifyDataSetChanged()` | 只 `notifyItemChanged` 受影响的行 | 列表闪烁、封面重复下载 |
| 7 | 图片加载完直接 `setImageBitmap` | 换图前先 `request.cancel()`，回调里再判页面存活 | 图片贴到错误的行上 |
| 8 | 直接 `new Thread(...).start()` | 用 `AppExecutors` 里已有的线程池 | 线程失控、无人管理 |

### 7.1 关于第 3 条的完整写法

```java
MusicRepository.search(keyword, new MusicRepository.Callback<List<Song>>() {

    @Override
    public void onSuccess(List<Song> songs) {
        // 网络请求期间用户可能已经切走页面了
        if (!isViewUsable()) {
            return;
        }
        // 到这里才能安全地碰 View
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onError(String message) {
        if (!isViewUsable()) {
            return;
        }
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }
});

/** Activity 里判 isFinishing() || isDestroyed()；Fragment 里判 isAdded() && getView() != null */
private boolean isViewUsable() {
    return isAdded() && getView() != null;
}
```

**为什么两个条件都要判**：

- `isAdded()` 为 false 表示 Fragment 已经从 Activity 上摘下来了
- `getView()` 为 null 表示视图已经被销毁（`onDestroyView` 之后）

只判一个仍可能崩：Fragment 还在 Activity 上，但 view 已经销毁了。

---

## 8. 改完后的自检清单

```
[ ] .\build.cmd 能编译通过（没有 error）
[ ] 新增的监听器都有对应的注销代码
[ ] 新增的回调都有 isViewUsable() / isFinishing() 判断
[ ] 新增的网络请求都在子线程
[ ] 新增的界面更新都在主线程
[ ] 没有新增魔法数字（都提成了命名常量）
[ ] 没有新增硬编码的中文（除非遵循本项目现状）
[ ] 改了接口 → 更新了 docs/05-bilibili-api.md
[ ] 改了结构 → 更新了 docs/03-code-map.md
[ ] 修了一个坑 → 记进了 docs/07-faq-and-pitfalls.md
```

### 真机验证的最小集合

编译通过 ≠ 能用。以下 6 项建议每次改动后都过一遍：

1. 打开 App，搜索页能出现热词与排行榜
2. 搜一个关键词，结果列表出现，封面能加载
3. 点一首歌，能出声；迷你播放条出现；通知栏有控制条
4. 打开播放页，进度条在走；拖动进度条能跳转
5. 点收藏，心形变红；去乐库"我喜欢"能看到它
6. （登录情况下）乐库"收藏夹"能列出收藏夹，点进去能出歌

---

## 9. 提交与发布

```powershell
# 提交
git add -A
git commit -m "feat(search): 支持下拉刷新"

# 发布（CI 会自动构建各 ABI 的 debug APK 并创建 Release）
# ① 在 CHANGELOG.md 顶部加一段，标题形如 ## [v1.1.0]
#    方括号里必须与 tag 名完全一致（含 v 前缀），后面跟日期或说明都可以
#    ## [v1.1.0] - 2026-01-01
# ② 打标签并推送
git tag v1.1.0
git push origin v1.1.0
```

CI 用 `awk` 在 `CHANGELOG.md` 里找标题**以** `## [v1.1.0]` **开头**的段落当 Release 说明，
抽取范围是从该标题到下一个 `## [` 标题之间的全部内容。
**方括号里不一致（缺 `v`、版本号写错）会导致 Release 说明为空。**

---

继续读：[07-faq-and-pitfalls.md](07-faq-and-pitfalls.md) —— 卡住时来这里找。

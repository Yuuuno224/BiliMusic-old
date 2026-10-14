# 02 · 架构总览

> 目标：读完这篇，你应该能在不打开代码的情况下，画出这个 App 的结构图，
> 并说清"哪个包能调用哪个包、为什么"。

---

## 1. 先说"分层"到底在解决什么问题

初学者常把"分层"理解成"把文件按类型放进不同文件夹"，然后觉得这是形式主义。
其实分层的核心目的只有一个：

> **限制"谁能知道谁"，从而让改动的影响范围可控。**

举个本项目的真实例子：B 站哪天把搜索接口的 JSON 字段改了，
你只需要改 `net/dto/SearchDto.java` 和 `net/api/SearchApi.java` 里的映射，
**界面代码一行都不用动**——因为界面从来没见过 B 站的 JSON，
它只认识 `List<Song>`。

如果反过来，界面里直接 `JSONObject.getString("bvid")`，
那么 B 站一改字段名，所有页面都得跟着改。这就是不分层的代价。

所以看每一层的时候，问自己两个问题就够了：

1. 这一层**知道**什么？（它认识哪些类型）
2. 这一层**不知道**什么？（它刻意不去认识的类型）

---

## 2. 分层图

自下而上，越靠下越"底层"、越稳定；越靠上越"业务"、越易变。

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ui  ── 界面层（Activity / Fragment / Adapter）                            │
│  MainActivity · SearchFragment · LibraryFragment · MeFragment              │
│  NowPlayingActivity · QrLoginActivity · SongAdapter · PlaylistDialog …     │
│  只认识 Song / UserProfile / FavoriteFolder 这些 App 自己的模型             │
└───────────┬───────────────────────────────┬──────────────────────────────┘
            │                               │
┌───────────▼──────────────┐   ┌────────────▼─────────────────────────────┐
│  player ── 播放层          │   │  auth ── 登录层                           │
│  PlaybackService          │   │  AuthManager（登录态 + 用户信息缓存）      │
│  PlayerConnection         │   └────────────┬─────────────────────────────┘
│  MediaItemFactory         │                │
└───────────┬──────────────┘                 │
            │                               │
┌───────────▼───────────────────────────────▼──────────────────────────────┐
│  repo ── 业务编排层                                                        │
│  MusicRepository：唯一职责是"切到后台线程 → 调 api → 切回主线程回调"        │
└───────────┬──────────────────────────────────────────────────────────────┘
            │
┌───────────▼──────────────────────────────────────────────────────────────┐
│  net ── 网络层                                                            │
│  net/       BiliHttp(传输) · Json(序列化) · CookieStore(Cookie)            │
│             WbiSigner(签名) · BiliSession(请求前置准备) · BiliException     │
│  net/api/   SearchApi · PlaybackApi · LoginApi · FavoriteApi               │
│             ↑ 负责"调接口"，并把 dto 翻译成 model                          │
│  net/dto/   ApiResponse · SearchDto · PlayUrlDto …                        │
│             ↑ 只是 B 站 JSON 的一比一镜像，不含任何逻辑                     │
└───────────┬──────────────────────────────────────────────────────────────┘
            │
┌───────────▼──────────────────────────────────────────────────────────────┐
│  model ── 领域模型层（最底层，不认识任何人）                                │
│  Song · UserProfile · FavoriteFolder · AudioStream                        │
└──────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────┐  ┌──────────────────────────────────────────┐
│  core/AppExecutors         │  │  util/ImageLoader                         │
│  全 App 的线程总控          │  │  图片加载（内存 LRU + 磁盘缓存 + 可取消）  │
│  横切关注点，任何层都可以用  │  │  只被 ui 依赖                              │
└───────────────────────────┘  └──────────────────────────────────────────┘

                    store/MusicStore ── 本地持久化（只借用 net.Json 做序列化，不发网络请求）
```

---

## 3. 依赖规则（谁能 import 谁）

这是最重要的一张表。**看代码时先判断"这个 import 合不合法"**，
不合法的依赖就是架构开始腐化的信号。

| 包 | 允许依赖 | 被谁依赖 | 一句话职责 |
|---|---|---|---|
| `model` | **无**（纯 Java + 无 Android 依赖） | 几乎所有层 | 应用自己的数据结构 |
| `net.dto` | 只有 Gson | `net`、`net.api` | B 站 JSON 的字段镜像 |
| `net` | `net.dto` | `net.api`、`auth`、`player`、`util`、`store`(仅 `Json`) | HTTP 传输、Cookie、签名、错误类型 |
| `net.api` | `net`、`net.dto`、`model` | `repo`、`auth`、`player` | 调接口 + dto→model 翻译 |
| `store` | `model`、`net.Json`、`core` | `ui`、`player` | 本地 JSON 持久化 |
| `repo` | `net.api`、`model`、`core` | `ui` | 异步编排（切线程 + 回调） |
| `auth` | `net`、`net.api`、`model`、`core` | `ui` | 登录态与用户信息 |
| `player` | `net`、`net.api`、`model`、`store`、`core` | `ui` | 播放服务与界面侧控制器 |
| `ui` | 以上全部 | — | 界面 |
| `core` | 无（只用 Android 框架） | `auth`、`store`、`repo`、`player`、`util`、`ui`（**`net` 与 `model` 不用它**） | 线程池集中管理 |
| `util` | `core`、`net.BiliHttp` | `ui` | 图片加载器（唯一内容） |

> 这张表可以用一段脚本自动核对：把每个 `.java` 文件的
> `import com.yuuuno224.bilimusic.*` 抽出来按包聚合，
> 就能看出真实依赖图。本文档就是这么做出来并核对过的，
> 结论是 **`model` 零依赖、`ui` 不直接依赖 `net`**。

### 三条铁律

1. **`model` 不依赖任何东西。** 它连 Gson 注解都不加（`@SerializedName` 只出现在 `net/dto` 里）。
   这样 `model` 可以随便被移动、被测试、被复用。
   > 这条规则有一个反面教材值得记住：`UserProfile` 曾经有一个
   > `from(NavDto)` 静态工厂，看起来很方便，却让 `model` 反过来依赖了 `net.dto`。
   > 现在映射放在 `net/api/LoginApi` 里——**翻译官应该站在被翻译的两边中间，而不是其中一边**。
2. **`net` 不知道界面存在。** 网络层永远不回调 UI，只返回结果或抛异常；
   "切回主线程"是 `repo` 和 `auth` 的活。
3. **`ui` 不直接调 `net`。** 界面只跟 `repo`/`auth`/`player` 打交道。
   连登录状态码这类"协议常量"都刻意放在 `model/LoginState` 里，
   就是为了让登录页不必 import 网络层的类。

### 3.1 已知的"破例"（诚实记录）

架构规则不是宗教，偶尔会有合理的破例。本项目**只有一处**，且在代码注释里写明了原因：

| 破例 | 位置 | 原因 | 风险 |
|---|---|---|---|
| `player` → `ui.player` | `PlaybackService` 里 `new Intent(this, NowPlayingActivity.class)` | Media3 要求给通知栏设置一个 `PendingIntent`（点击通知要打开某个 Activity），必须引用具体的 Activity 类 | 很低。只是一个类的引用，不涉及逻辑耦合 |

> 另一处曾经的破例（`net.api` → `util.BiliText`）已经修掉了：
> `BiliText` 描述的是"B 站返回数据的格式怪癖"，本来就属于网络层，
> 现在它在 `net/BiliText.java`。
> 这样一来还顺手解开了一个包级循环——原先 `util` 依赖 `net`（`ImageLoader` 用 `BiliHttp`），
> 而 `net.api` 又依赖 `util`，两者互相咬住了。

---

## 4. 包结构（重构后的实际结构）

```
app/src/main/java/com/yuuuno224/bilimusic/
│
├── BiliMusicApp.java             Application 入口：初始化 AuthManager / MusicStore / ImageLoader
│
├── core/
│   └── AppExecutors.java         线程总控：5 个线程池 + 主线程 Handler（全 App 唯一的线程出口）
│
├── net/                          网络层
│   ├── BiliHttp.java             传输：统一 UA/Referer/Cookie；把 4xx/5xx 翻译成带状态码的异常
│   ├── Json.java                 Gson 唯一入口：解析失败时抛出带上下文的 BiliException
│   ├── CookieStore.java          Cookie 仓库：内存 Map + SharedPreferences 持久化
│   ├── WbiSigner.java            WBI 签名：混淆出 mixinKey、算 w_rid
│   ├── BiliSession.java          请求前置准备：申请 buvid3 指纹、缓存 WBI 密钥、签名重试
│   ├── BiliException.java        带业务码/状态码的异常（-101 未登录 / -403 签名失效 / 412 风控）
│   ├── BiliText.java             B 站返回文本清洗：去高亮标签、解析两种时长格式
│   ├── UrlCodec.java             URL 编码（与 JS 的 encodeURIComponent 对齐）
│   │
│   ├── api/                      按业务拆分的接口封装，方法全部"同步阻塞"
│   │   ├── SearchApi.java        搜索、热门搜索词、音乐区排行榜
│   │   ├── PlaybackApi.java      取 cid/时长、取最优音频流（含音质选择策略）
│   │   ├── LoginApi.java         授权链接生成、登录轮询、用户信息、退出登录
│   │   └── FavoriteApi.java      收藏夹列表、收藏夹内容（内部自动翻页）
│   │
│   └── dto/                      纯 JSON 镜像（只映射字段，一行逻辑都没有）
│       ├── ApiResponse.java      { code, message, data } 统一响应外壳
│       ├── SearchDto.java        搜索结果
│       ├── HotSearchDto.java     热门搜索词
│       ├── RankingDto.java       音乐区排行榜（注意它的 data 是数组）
│       ├── VideoDetailDto.java   视频详情（取 cid / 时长）
│       ├── PlayUrlDto.java       播放地址（DASH 音频流）
│       ├── NavDto.java           用户信息 + WBI 密钥图片地址（一件两用）
│       ├── FavoriteDto.java      收藏夹相关（两个接口的响应外壳）
│       ├── FingerprintDto.java   风控指纹（buvid3 / buvid4）
│       └── QrLoginDto.java       登录授权链接 + 轮询状态
│
├── model/                        领域模型（**零依赖**，连 Gson 注解都不加）
│   ├── Song.java                 一首"歌" = 一个 B 站视频；bvid 是主键
│   ├── UserProfile.java          当前登录用户（同时是本地缓存的序列化对象）
│   ├── FavoriteFolder.java       一个 B 站收藏夹
│   ├── AudioStream.java          一条可直接播放的音频流（带时效的地址）
│   └── LoginState.java           登录进度状态码（放这里是为了让 ui 不必依赖 net）
│
├── store/
│   └── MusicStore.java           收藏 / 播放历史 / 搜索历史：内存为主 + 异步原子落盘
│
├── auth/
│   └── AuthManager.java          登录态：生成→轮询→存 Cookie→拉用户信息；含登录失效检测
│
├── repo/
│   └── MusicRepository.java      5 个公开方法，全部是"后台执行 + 主线程回调"同一套骨架
│
├── player/
│   ├── PlaybackService.java      MediaSessionService + ExoPlayer（播放的唯一真相）
│   ├── PlayerConnection.java     界面侧遥控器封装：连接管理、监听分发、自定义命令
│   └── MediaItemFactory.java     Song → MediaItem（解析 cid、取音频地址、附带业务数据）
│
├── util/
│   └── ImageLoader.java          图片加载：内存 LRU + 磁盘缓存 + 请求合并 + 可取消
│
└── ui/                           界面层，按功能分包
    ├── MainActivity.java         主界面：底部三栏 + 迷你播放条 + 通知权限申请
    ├── search/SearchFragment.java    搜索首页 + 结果列表
    ├── library/
    │   ├── LibraryFragment.java      乐库：我喜欢 / 最近播放 / 收藏夹（两级导航）
    │   └── FavFolderAdapter.java     收藏夹列表适配器
    ├── me/MeFragment.java            我的：账号信息、快捷入口、关于
    ├── player/
    │   ├── NowPlayingActivity.java   沉浸式播放页
    │   ├── PlaylistDialog.java       播放队列底部弹窗
    │   └── PlaylistAdapter.java      播放队列适配器
    ├── auth/QrLoginActivity.java     授权链接登录页
    └── widget/SongAdapter.java       歌曲列表适配器（多个页面复用）
```

---

## 5. 线程模型

Android 的线程规则只有两条，但违反了后果很严重：

- **网络请求、写大文件不能放主线程** → 界面卡死，超过约 5 秒系统弹 ANR 直接杀进程
- **改界面必须在主线程** → 否则抛 `CalledFromWrongThreadException`

本项目把"有哪些线程"这件事完全收敛到 `core/AppExecutors.java` 一个文件里。

| 名字 | 类型 | 数量 | 负责什么 | 谁在用 |
|---|---|---|---|---|
| （主线程） | Android 主线程 | 1 | 所有界面更新、播放器 API 调用 | `AppExecutors.main()` / `runOnMain()` / `postOnMain()` |
| `bili-io` | 单线程池 | 1 | 用户触发的网络请求：搜索、榜单、收藏夹 | `MusicRepository` |
| `bili-auth` | 单线程池 | 1 | 登录：生成链接、轮询、拉用户信息、退出 | `AuthManager` |
| `bili-resolve` | 固定线程池 | 6 | 播放队列并行解析（bvid → 音频地址） | `PlaybackService` |
| `bili-image` | 固定线程池 | 4 | 封面图下载与解码 | `ImageLoader` |
| `bili-store` | 单线程池 | 1 | 收藏/历史 JSON 落盘 | `MusicStore` |

### 5.1 为什么网络请求要串行（单线程）

B 站有风控：短时间内并发请求过多会被判定为异常客户端，返回 412 并可能临时封禁该 IP。
串行执行虽然略微慢一点，但稳定得多。用户在一次操作里只需要一个结果，
串行的代价几乎感知不到。

### 5.2 那为什么播放队列又要并行

因为它的场景完全不同：用户点了一首歌，我们却要把**整条队列**（可能 20 首）
的音频地址都解析出来才能填进播放器。串行做就是 20 次网络往返，可能要十几秒。

这里和风控并不冲突，因为：

- 这是**用户主动触发的一次性批量操作**，不是持续的高频请求；
- 并发度只有 6，且有 20 秒超时兜底（超时后只载入已解析成功的部分）。

### 5.3 存储为什么要单独一条线程

收藏/历史每次变更都要写文件。如果把它丢进 `bili-io`，
用户的一次搜索（可能要 2 秒）就会把"记录播放历史"堵在后面，
万一这时进程被杀，历史就丢了。给它一条独立的单线程池，
既能立刻落盘，又不会和网络抢线程。

### 5.4 线程切换的标准写法

```java
// 后台线程：做阻塞的事
AppExecutors.io().execute(() -> {
    List<Song> songs = SearchApi.search(keyword, 1);   // 同步网络请求
    // 回到主线程：更新界面
    AppExecutors.runOnMain(() -> adapter.notifyDataSetChanged());
});
```

`runOnMain()` 有个细节：如果当前已经在主线程，它会**直接同步执行**，
少一次消息投递，也避免界面更新被延后一帧。

### 5.5 播放器状态的"合并刷新"

播放器状态变化非常密集：一次切歌可能连续触发
`onPlaybackStateChanged` → `onMediaItemTransition` → `onIsPlayingChanged`。

如果每个回调都刷一遍界面，一次切歌要刷三遍。
所以 `PlayerConnection.scheduleNotify()` 用了一个标记位：

```
第一个回调 → 标记位 = true，向主线程投递一个刷新任务
第二、三个回调 → 发现标记位已经是 true，直接返回（丢弃）
刷新任务执行 → 标记位复位 → 真正通知所有 Listener 一次
```

这叫**合并刷新（coalescing）**，是处理高频事件的常用手法。

---

## 6. 数据存储清单

App 一共写四种本地数据，注意它们**分别**放在哪里、为什么。

| 数据 | 形式 | 位置 | 为什么这样选 |
|---|---|---|---|
| 收藏 / 播放历史 / 搜索历史 | JSON 文件 | `{filesDir}/music_store.json` | 数据量较大（历史最多 200 条）、结构复杂（嵌套 Song），用 Gson 一次性读写最省事 |
| 登录 Cookie | SharedPreferences | `bili_cookies` → `cookies_json` | 必须在启动最早期就可用；读写频繁但数据极小；`apply()` 异步写不阻塞 |
| 用户信息（昵称/头像/等级） | SharedPreferences | `bili_auth` → `me` | 只是展示缓存，丢了不影响登录，用最简单的键值存储即可 |
| 图片磁盘缓存 | WebP 文件 | `{cacheDir}/covers/{md5(url)}` | 用 URL 的 MD5 当文件名，避免特殊字符、长度固定；放 cacheDir 系统可随时回收 |

### 6.1 各种上限

| 限制 | 值 | 在哪里 |
|---|---|---|
| 播放历史上限 | 200 条 | `MusicStore.MAX_HISTORY` |
| 搜索历史上限 | 10 条 | `MusicStore.MAX_SEARCH_HISTORY` |
| 图片内存缓存 | 16 MB | `ImageLoader.MEMORY_CACHE_BYTES` |
| 图片磁盘缓存 | 64 MB（超出清掉最旧的一半） | `ImageLoader.DISK_CACHE_BYTES` |
| 收藏夹自动翻页上限 | 10 页（约 200 条） | `FavoriteApi.MAX_PAGES` |
| 搜索每页条数 | 20 | `SearchApi.PAGE_SIZE` |
| WBI 密钥缓存时长 | 2 小时 | `BiliSession.WBI_TTL_MS` |
| 登录轮询间隔 | 2 秒 | `QrLoginActivity.POLL_INTERVAL_MS` |
| 登录总超时 | 3 分钟 | `QrLoginActivity.TIMEOUT_MS` |
| 播放队列解析超时 | 20 秒 | `PlaybackService.RESOLVE_TIMEOUT_SECONDS` |
| 音频地址失效重试次数 | 2 次 | `PlaybackService.MAX_ERROR_RETRIES` |
| HTTP 连接/读取超时 | 10 秒 / 15 秒 | `BiliHttp` |

> **为什么这些数字要集中定义成常量**：它们都是"调参点"。
> 散落在代码里写成魔法数字后，想改一个值就得全文搜索。
> 全部提成 `static final` 常量并起个好名字，改动时一目了然。

---

## 7. 关键设计决策与取舍

这一节回答"为什么是这样，而不是那样"。每个决定都有代价，一并说明。

### 7.1 为什么播放器放在 Service 里

音乐必须能在界面关闭后继续播放、并且能被通知栏控制。
Activity 随时可能被系统销毁（内存不足、用户按返回键、旋转屏幕），
而**前台 Service** 可以长期存活，还能在通知栏显示一条常驻通知。

代价：Service 与界面之间不能直接互相调用，必须通过 MediaSession 通信，
代码比"在 Activity 里直接 new 一个播放器"复杂得多。

### 7.2 为什么由服务端持有队列，而不是界面

界面点歌时，它手上只有一个 `Song`（含 bvid），而播放器需要的是一个能直接播的 URL。
如果把"解析地址"放在界面侧做，会带来三个问题：

1. 界面要等网络，点击后有明显延迟；
2. 通知栏的"上一首/下一首"、播放器的"列表循环"都需要知道完整队列，
   而界面侧解析只能一首一首来，队列信息是残缺的；
3. 界面销毁后 Queue 就没了，后台播放会断。

所以本项目让 Service 持有队列，界面通过自定义 `SessionCommand` 把 `Song` 列表发过去：

| 命令 | 作用 |
|---|---|
| `CMD_PLAY_QUEUE` | 设置整条队列并从第 N 首开始 |
| `CMD_PLAY_NEXT` | 插到当前播放的下一首 |
| `CMD_ADD_QUEUE` | 追加到队列末尾 |

代价：`Song` 需要序列化才能跨进程传递（本项目用 Gson 转 JSON 塞进 `Bundle`）。

### 7.3 为什么 `Song` 要序列化进 `MediaItem` 的 extras

播放器队列里只有 `MediaItem`，而界面要显示"当前播放的是哪首歌"（标题、封面、收藏状态）。
`MediaItem` 的 URI 只是一个音频地址，不含业务信息。

Media3 允许在 `MediaMetadata.extras`（一个 `Bundle`）里携带任意数据，
所以把 `Song` 转成 JSON 存进去，用 `MediaItemFactory.songOf(MediaItem)` 反序列化回来。
这是 Media3 下携带业务数据的惯用做法。

代价：每次读当前歌曲都要做一次 JSON 解析（几十微秒，可忽略）。

### 7.4 为什么 `MusicStore` 是"内存为主 + 异步落盘 + 原子写"

三个问题决定了这个设计：

1. **界面要立刻拿到数据**（打开乐库就要显示收藏）→ 数据必须在内存里；
2. **不能在主线程写文件** → 落盘丢给 `bili-store` 线程；
3. **写到一半被杀进程会损坏文件** → 用"写临时文件再整体替换"的原子写。

原子写的原理：

```
1. 把完整 JSON 写进 music_store.json.tmp
2. 用 Files.move(tmp, music_store.json, REPLACE_EXISTING) 一步替换
```

操作系统保证 `move` 这个动作是原子的。所以要么看到完整的旧文件，
要么看到完整的新文件，永远不会出现"半截 JSON"。
如果没有这一步，一次意外断电就可能让所有收藏和播放历史一起丢失。

代价：每次变更都全量序列化三个列表。对几百条数据来说只要几毫秒，可以接受。

### 7.5 为什么 Cookie 用 SharedPreferences，而收藏用 JSON 文件

| | Cookie | 收藏/历史 |
|---|---|---|
| 何时需要 | App 启动最早期（WBI 密钥、指纹都要用） | 用户打开乐库时 |
| 数据量 | 几十字节 | 几十 KB |
| 读写频率 | 每个请求都要读 | 用户操作时才写 |
| 结构 | 平坦的 `Map<String,String>` | 嵌套对象列表 |

SharedPreferences 更适合"小而频繁"，JSON 文件更适合"大而结构化"。

### 7.6 为什么刻意不用 Retrofit / OkHttp / Room / Glide

这是**教学向项目**的主动选择，不是能力不足：

| 没用 | 用什么替代 | 换来什么 | 失去什么 |
|---|---|---|---|
| OkHttp | `HttpURLConnection` | 零依赖；能看清 HTTP 请求的每一行 | 连接池、拦截器、自动重试 |
| Retrofit | 手写 `net/api/*` | 能看清参数怎么拼、URL 怎么组 | 声明式接口、自动反序列化 |
| Room | `MusicStore` + Gson | 不用理解注解处理器和 SQL | 查询能力、增量更新、事务 |
| Glide | `ImageLoader` | 能理解内存 LRU 与磁盘缓存的关系 | 图形变换、GIF、生命周期感知 |

**如果你在学 Android**，建议先照现在这样写一遍，理解每一步在做什么；
之后再用这些库，你会明白它们替你省掉了什么。
**如果是做真实产品**，请直接用这些库——它们的边界情况处理比手写代码完善得多。

### 7.7 为什么不用 ViewModel / LiveData / 依赖注入

这三个都是好东西，但本项目规模（约 5000 行 Java，含大量注释；6 个界面）下收益不明显：

- **ViewModel** 的价值在于"配置变化（旋转屏幕）后保留数据 + 自动解绑生命周期"。
  本项目的页面数据都可以重新拉取，且 `isViewUsable()` 判断已经覆盖了"回调晚到"的问题。
- **依赖注入**（Hilt/Koin）的价值在于"大规模替换实现 + 测试替身"。
  本项目只有一个实现、没有单元测试，引入它只会增加构建复杂度。
- 代价要如实说：**本项目难以写单元测试**，因为大量静态方法和 Android 依赖。
  想做严格测试，就必须先引入 ViewModel + 依赖注入。

### 7.8 为什么 `PlayerConnection` 是长生命周期的静态单例

它与 `MediaController` 的连接需要跨进程握手，成本不低。
让连接与 App 进程同生命周期，界面来回切换时就不必反复握手。

代价：它是静态的，**必须显式清理**。规则是：

- `addListener()` 之后一定要在 `onDestroy` 里 `removeListener()`，否则监听器持有已销毁的界面 → 内存泄漏
- 一般**不要**调用 `release()`；只有确定要彻底断开播放时才用

### 7.9 为什么有两个"模型层"（`net/dto` 和 `model`）

初学者最容易困惑的一点。区别在于"谁的结构"：

| | `net/dto` | `model` |
|---|---|---|
| 结构反映谁 | **B 站**的 JSON 结构 | **本 App** 需要的结构 |
| 例子 | `NavDto.levelInfo.currentLevel` | `UserProfile.level` |
| 能否有逻辑 | 不能（只有字段） | 可以（如 `Song.durationText()`） |
| 字段命名 | 必须与 JSON 一致（必要时用 `@SerializedName`） | 取对 App 最自然的命名 |
| 被谁使用 | 只在 `net` 内部 | 所有层 |

`net/api` 里的 `*Api` 类就是两者之间的翻译官。
这样做的好处是：B 站改字段名，只影响 `dto` 和对应 `api`，`model` 与界面完全不受影响。

---

## 8. 一次冷启动发生了什么

把"进程启动 → 首页出现"这条链走一遍，你会对整体结构有直觉。

```
① 进程启动
   └── BiliMusicApp.onCreate()
       ├── AuthManager.init(context)
       │   ├── CookieStore.init()      读 SharedPreferences 里的登录 Cookie 到内存
       │   └── 读 bili_auth/me         上次缓存的用户信息
       ├── MusicStore.init(context)    同步读 music_store.json（收藏 + 历史）
       └── ImageLoader.init(context)   准备 cacheDir/covers 目录

② 显示界面
   └── MainActivity.onCreate()
       ├── setContentView(activity_main)    底部导航 + 迷你播放条
       ├── BottomNavigationView 监听就绪
       ├── show(SearchFragment)             默认停在搜索页
       ├── PlayerConnection.acquire(this)   异步连接播放服务
       │   └── MediaController 握手
       │       └── 首次会顺带启动 PlaybackService
       │           ├── 创建 ExoPlayer（含音频属性、唤醒锁、拔耳机暂停）
       │           └── 创建 MediaSession（注册 3 条自定义命令）
       └── 申请 POST_NOTIFICATIONS（仅 Android 13+）

③ 首页内容
   └── SearchFragment.onResume()
       ├── renderHistory()   读本地搜索历史 → 渲染 chips
       ├── renderRecent()    读本地播放历史 → 取前 5 条
       ├── loadHotWords()    命中 10 分钟缓存就直接渲染，否则走 bili-io 请求
       └── loadRanking()     同上
```

注意几个设计点：

- **本地数据是同步读的**（步骤 ①）。因为文件很小、且界面一打开就要用，
  异步读反而要处理"数据还没到"的空状态，得不偿失。
- **网络数据是异步、且带缓存的**（步骤 ③）。热门词和排行榜变化很慢，
  没必要每次切页面都请求。
- **播放器连接是异步但不阻塞界面的**。连接没完成时用户就点歌也不会丢命令——
  `PlayerConnection.sendCommand` 会把调用排进队列，连上后统一补发。

---

## 9. 三个"入口"

理解一个 Android 项目，先找到系统会调用哪些入口类：

| 入口 | 由谁启动 | 生命周期 | 本项目里做什么 |
|---|---|---|---|
| `BiliMusicApp`（Application） | 进程创建时，系统 | 与进程同生共死 | 初始化三个全局组件 |
| `MainActivity` | 用户点桌面图标（`LAUNCHER` intent-filter） | 用户可见期间 | 承载三个主页面 + 迷你播放条 |
| `PlaybackService` | MediaController 连接时自动拉起 | 播放期间（前台服务，不易被杀） | 持有 ExoPlayer 与队列 |

另外两个 Activity（`NowPlayingActivity`、`QrLoginActivity`）不由系统直接启动，
而是从界面里 `startActivity()` 打开的。

---

## 10. 改代码前的自检清单

想加功能时，先问自己：

1. **这是网络请求吗？** → 加在 `net/api`，返回 `model` 类型，不要回调 UI。
2. **需要切线程吗？** → 加一个 `MusicRepository` 方法，套用那 5 行骨架。
3. **要新增数据结构吗？** → B 站的 JSON 放 `net/dto`，App 自己的放 `model`。
4. **要写本地数据吗？** → 走 `MusicStore`，别自己开文件流。
5. **要更新界面吗？** → 确认在主线程；确认页面还活着（`isAdded() && getView() != null`）。
6. **要注册监听器了吗？** → 想好在哪里注销。

继续读：[03-code-map.md](03-code-map.md) 看每个类的细节，或
[04-core-flows.md](04-core-flows.md) 跟着真实流程走一遍。

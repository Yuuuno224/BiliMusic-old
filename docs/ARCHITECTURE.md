# BiliMusic 项目架构文档

> 本文档从项目入口出发，逐层梳理整体架构、模块划分、依赖关系、数据流向与核心流程，
> 帮助重新建立对项目的完整认知，并给出后续开发指引。

---

## 一、项目概述

### 1.1 用途与定位

BiliMusic 是一个**纯客户端 Android 音乐播放器**，把 B 站视频当作音乐来听。
不依赖任何自建后端服务，所有数据来自 B 站公开 API。

- **音源**：B 站视频的 DASH 音频流（抽取视频的音轨播放，不播放画面）
- **体验**：类似 Spotify / 网易云音乐的纯播放体验，深色主题
- **账号**：支持 B 站扫码登录，登录后可浏览个人收藏夹、获取更高音质
- **存储**：收藏 / 播放历史 / 搜索历史 均存本地 JSON 文件，无数据库

### 1.2 核心功能

| 功能 | 说明 |
|------|------|
| 搜索 | 关键词搜索 B 站视频，WBI 签名保证接口合规 |
| 搜索首页 | 热门搜索词 + 音乐区排行榜 + 最近播放 |
| 播放 | DASH 音频流式播放，后台播放、通知栏控制、自动连播 |
| 沉浸式播放页 | 大封面、进度拖拽、上一首/下一首、三档循环模式 |
| 播放队列 | BottomSheet 弹窗，支持点击跳转和删除 |
| 乐库 | 我喜欢 / 最近播放 / B 站收藏夹浏览（两级导航） |
| 收藏与历史 | 本地收藏夹 + 播放历史（上限 200 条） |
| 扫码登录 | 生成授权链接，B 站 APP 确认，自动获取 Cookie |
| 迷你播放条 | 底部常驻，快捷收藏、播放/暂停、打开队列 |
| 我的 | 账号信息、横向快捷入口、GitHub 仓库、关于 |

### 1.3 技术栈

| 层 | 方案 | 版本 |
|----|------|------|
| 语言 | Java | 17 |
| 构建 | AGP + Gradle | 9.2.1 + 9.4.1 |
| SDK | compileSdk / minSdk / targetSdk | android-36.1 / 26 / 35 |
| 播放 | Media3 ExoPlayer + MediaSession | 1.5.1 |
| 网络 | HttpURLConnection + Gson | gson 2.11.0 |
| 存储 | JSON 文件（不引入 Room） | - |
| 图片 | 自写 ImageLoader（不引入 Glide） | - |
| UI | Material Components | 1.10.0 |
| 容器 | AndroidX AppCompat / RecyclerView / ViewPager2 / ConstraintLayout | - |

**刻意不引入的依赖**：Retrofit / OkHttp / Room / Glide / Coil。
全部用标准库或自写组件替代，保持依赖最小化。

---

## 二、目录结构与模块职责

```
app/src/main/java/com/yuuuno224/bilimusic/
│
├── BiliMusicApp.java              # Application 入口，初始化四大全局组件
│
├── net/                            # ── 网络层 ──
│   ├── BiliHttp.java               #   HTTP 客户端：统一 UA/Referer/Cookie
│   ├── BiliApi.java                #   B 站 API 封装（全部同步阻塞方法）
│   ├── WbiSigner.java              #   WBI 参数签名（MD5 混淆 w_rid）
│   ├── CookieStore.java            #   Cookie 持久化（SharedPreferences）
│   └── UrlCodec.java               #   URL 编解码工具
│
├── model/                          # ── 数据模型层 ──（Gson POJO）
│   ├── ApiResp.java                #   统一响应包装 { code, message, data }
│   ├── SearchData.java             #   搜索结果
│   ├── RecommendData.java          #   热门搜索 + 排行榜
│   ├── ViewData.java               #   视频详情（取 cid）
│   ├── PlayData.java               #   播放地址（DASH 音频流）
│   ├── NavData.java                #   用户信息（nav 接口）
│   ├── FavData.java                #   收藏夹列表 + 收藏内容
│   ├── QrGenData.java              #   二维码生成结果
│   ├── QrPollData.java             #   二维码轮询状态
│   └── FingerData.java             #   buvid3 风控 Cookie
│
├── store/                          # ── 本地存储层 ──
│   ├── Song.java                   #   统一歌曲模型（bvid/cid/title/up/cover）
│   └── MusicStore.java             #   JSON 文件存储：收藏/历史/搜索历史
│
├── auth/                           # ── 认证层 ──
│   └── AuthManager.java            #   扫码登录全流程管理
│
├── repo/                           # ── 仓库层 ──
│   └── MusicRepository.java        #   异步编排：IO 线程执行 → 主线程回调
│
├── player/                         # ── 播放层 ──
│   ├── PlaybackService.java        #   MediaSessionService + ExoPlayer（服务端）
│   └── PlayerConnection.java       #   MediaController 单例封装（客户端）
│
├── ui/                             # ── UI 层 ──（按功能分包）
│   ├── MainActivity.java           #   主界面：底部三栏 + 迷你播放条
│   ├── search/
│   │   └── SearchFragment.java     #   搜索页
│   ├── library/
│   │   ├── LibraryFragment.java    #   乐库页（我喜欢/最近/收藏夹）
│   │   └── FavFolderAdapter.java   #   收藏夹列表适配器
│   ├── me/
│   │   └── MeFragment.java         #   我的页
│   ├── player/
│   │   ├── NowPlayingActivity.java #   沉浸式播放页
│   │   ├── PlaylistDialog.java     #   播放队列 BottomSheet
│   │   └── PlaylistAdapter.java    #   播放队列适配器
│   ├── auth/
│   │   └── QrLoginActivity.java    #   扫码登录页
│   └── widget/
│       └── SongAdapter.java        #   歌曲列表适配器（多页面复用）
│
└── util/
    └── ImageLoader.java            #   图片加载器：内存 LRU + 磁盘缓存
```

### 各模块职责一句话说明

| 包 | 职责 |
|----|------|
| `net` | 封装所有 HTTP 通信与 B 站接口调用，处理签名/Cookie/风控 |
| `model` | 纯数据 POJO，用 Gson 反序列化 B 站 JSON 响应 |
| `store` | 本地数据持久化（收藏/历史/搜索历史），统一歌曲模型 |
| `auth` | 管理登录态：生成授权链接 → 轮询 → 保存 Cookie → 拉取用户信息 |
| `repo` | 业务编排层：在子线程调 BiliApi，切回主线程回调 UI |
| `player` | 播放服务（ExoPlayer + MediaSession）与 UI 侧控制器封装 |
| `ui` | 所有界面：Activity / Fragment / Adapter |
| `util` | 通用工具（目前仅 ImageLoader） |

---

## 三、分层架构与依赖关系

### 3.1 分层架构图

```
┌──────────────────────────────────────────────────────────┐
│                        UI 层                              │
│  MainActivity · SearchFragment · LibraryFragment ·        │
│  MeFragment · NowPlayingActivity · QrLoginActivity ·      │
│  SongAdapter · PlaylistDialog · FavFolderAdapter          │
├──────────────────────────────────────────────────────────┤
│                      Player 层                            │
│  PlaybackService (ExoPlayer + MediaSession)  ← 服务端     │
│  PlayerConnection (MediaController 封装)      ← 客户端     │
├──────────────────────────────────────────────────────────┤
│                      Repo 层                              │
│  MusicRepository (异步编排，线程切换)                      │
├─────────────────────┬────────────────────────────────────┤
│      Net 层          │           Store 层                  │
│  BiliApi · BiliHttp  │  MusicStore (JSON 本地存储)         │
│  WbiSigner           │  Song (统一歌曲模型)                │
│  CookieStore         │                                    │
│  UrlCodec            │                                    │
├─────────────────────┴────────────────────────────────────┤
│                     Model 层                              │
│  ApiResp · SearchData · PlayData · NavData · FavData ...  │
└──────────────────────────────────────────────────────────┘
```

### 3.2 依赖方向（自顶向下）

```
UI ──→ Player ──→ Store
 │       │
 ├──→ Repo ──→ Net ──→ Model
 │              │
 │              └──→ Store (Song)
 │
 ├──→ Store (MusicStore, Song)
 ├──→ Auth ──→ Net ──→ Model
 └──→ Util (ImageLoader) ──→ Net (BiliHttp)
```

**关键依赖规则**：
- `model` 是最底层，被所有层依赖，不依赖任何层
- `net` 只依赖 `model`（解析响应）
- `store` 不依赖 `net`（本地存储独立）
- `repo` 依赖 `net` + `store`（编排网络请求并转换为 Song）
- `player` 依赖 `store` + `net`（PlaybackService 直接调 BiliApi 解析音频）
- `ui` 依赖 `player` + `repo` + `store` + `auth` + `util`
- `auth` 依赖 `net`（调 B 站登录接口）

### 3.3 Application 初始化链

```
BiliMusicApp.onCreate()
  ├── AuthManager.init(ctx)     → CookieStore.init + 加载缓存用户信息
  ├── CookieStore.init(ctx)     → 从 SharedPreferences 加载 Cookie
  ├── MusicStore.init(ctx)      → 从 JSON 文件加载收藏/历史
  └── ImageLoader.init(ctx)     → 创建磁盘缓存目录
```

---

## 四、核心流程详解

### 4.1 搜索流程

```
用户输入关键词
  ↓
SearchFragment.doSearch()
  ├── MusicStore.addSearchHistory(kw)     ← 本地记录搜索历史
  └── MusicRepository.search(kw, cb)      ← 发起异步搜索
        ↓ （IO 线程：music-io）
        BiliApi.searchVideos(keyword, 1)
          ├── ensureBuvid()               ← 首次获取 buvid3 风控 Cookie
          ├── ensureWbiKeys()             ← 拉取并缓存 WBI 签名密钥（2h 刷新）
          ├── WbiSigner.sign(params)      ← 注入 wts + 字典序 + MD5 生成 w_rid
          └── BiliHttp.get(url)           ← 带 Cookie 的 HTTP GET
                ↓
              B 站搜索接口返回 JSON
                ↓
          Gson 解析为 SearchData → 转换为 List<Song>
                ↓ （主线程）
        cb.onResult(List<Song>)
          ↓
SongAdapter.notifyDataSetChanged()        ← UI 刷新列表
```

**点击歌曲播放**：
```
SongAdapter 点击 → PlayerConnection.playQueue(ctx, allSongs, position)
  → sendCustomCommand(CMD_PLAY_QUEUE) → 进入播放流程（见 4.2）
```

### 4.2 播放流程（核心）

播放采用**服务端队列管理**设计：UI 发命令给 Service，Service 负责解析音频地址并填充 ExoPlayer 队列。

```
PlayerConnection.playQueue(ctx, songs, index)
  ↓
MediaController.sendCustomCommand(CMD_PLAY_QUEUE, Bundle{songs, index})
  ↓ （跨进程 / 同进程通信）
PlaybackService.handlePlayQueue(args)
  ├── 解析 Bundle 中的 Song 列表 JSON
  └── resolveQueueAndLoad(songs, startIndex)
        ↓
      player.stop() + clearMediaItems()
        ↓ （RESOLVER 线程池：6 线程并行）
      对每首歌并行执行 MediaItemBuilder.build(ctx, song):
        ├── 若 song.cid <= 0：BiliApi.videoDetail(bvid) 获取 cid
        └── BiliApi.bestAudio(bvid, cid) 获取 DASH 最优音频流
              ├── 优先 DASH audio 中 id 最大的（最高码率）
              ├── 其次 DASH flac（Hi-Res）
              └── 最后降级 durl[0].url
        ↓
      构建 MediaItem（uri=音频地址, metadata=标题/UP/封面/Song JSON）
        ↓ （CountDownLatch 等待全部完成，超时 20s）
      过滤掉解析失败的 null 槽位
        ↓ （主线程）
      player.setMediaItems(items, mappedStart, 0)
      player.prepare()
      player.play()
```

**播放状态通知 UI**：
```
ExoPlayer 状态变化（切歌/播放暂停）
  ↓
PlayerConnection.NOTIFY (Player.Listener)
  ↓
notifyListeners() → 所有 Listener.onPlayerChanged()
  ↓
MainActivity / NowPlayingActivity / SongAdapter 更新 UI
```

**自动记录播放历史**：
```
ExoPlayer.onMediaItemTransition（自动/手动切歌）
  ↓
PlaybackService: MusicStore.addHistory(song)   ← 写入本地 JSON
```

### 4.3 扫码登录流程

```
QrLoginActivity.onCreate()
  ↓
AuthManager.generate(callback)
  ↓ （auth-io 线程）
BiliApi.qrGenerate()  → B 站返回授权链接 + qrcode_key
  ↓ （主线程）
callback.onGenerated(authUrl, qrcodeKey)
  ├── 显示授权链接（可打开 B 站 APP / 复制链接）
  └── 启动轮询：每 2 秒执行 pollTask
        ↓
      AuthManager.pollOnce(qrcodeKey, callback)
        ↓ （auth-io 线程）
      BiliApi.qrPoll(qrcodeKey)
        ├── 读取响应头 Set-Cookie → CookieStore.putAll()
        └── 返回轮询状态码
              ↓ （主线程）
            state == ST_SUCCESS（86101 成功）
              ├── CookieStore.persist()        ← 持久化登录 Cookie
              ├── BiliApi.navInfo()            ← 拉取用户信息
              ├── persistMe()                  ← 缓存到 SharedPreferences
              └── callback.onSuccess(me) → finish()
            state == ST_SCANNED（86090 已扫码）
              → 显示"请在 B 站 APP 中确认"
            state == ST_EXPIRED（86091 过期）
              → 重新生成授权链接
```

**登录态判断**：`AuthManager.isLoggedIn()` = Cookie 中同时存在 `SESSDATA` 和 `bili_jct`。

### 4.4 收藏与播放历史

```
收藏（本地）：
  SongAdapter/NowPlayingActivity 点击心形
    → MusicStore.toggleFavorite(song)
      → favorites 列表增/删
      → save() → Gson 序列化为 JSON → 写入 music_store.json

播放历史（自动）：
  ExoPlayer 切歌回调
    → MusicStore.addHistory(song)
      → history 列表去重后插入头部（上限 200）
      → save()

搜索历史：
  SearchFragment.doSearch()
    → MusicStore.addSearchHistory(kw)
      → searchHistory 去重后插入头部（上限 10）
      → save()
```

**存储文件**：`{appFilesDir}/music_store.json`
```json
{
  "favorites": [Song, ...],
  "history": [Song, ...],
  "searchHistory": ["kw1", "kw2", ...]
}
```

### 4.5 图片加载流程

```
ImageLoader.load(url, callback)
  ├── 协议修正：http:// → https://，// → https://
  ├── 内存 LRU 缓存命中？→ 直接回调
  └── 线程池（4 线程）执行：
        ├── diskGet(url)  ← 磁盘缓存（MD5 文件名，WebP 格式）
        │   命中？→ 回调
        └── netGet(url)   ← 网络下载（复用 BiliHttp.open 带 Cookie）
            → diskPut()   → 写入磁盘缓存
            → MEM.put()   → 写入内存缓存
            → 主线程回调
```

**缓存目录**：`{appCacheDir}/covers/{md5(url)}`

### 4.6 收藏夹浏览流程

```
LibraryFragment 切到"收藏夹"Tab
  ↓
loadFolders()
  ├── 未登录？→ 显示"请先登录"
  └── MusicRepository.favFolders(mid, cb)
        ↓ （IO 线程）
        BiliApi.favFolders(mid)  ← WBI 签名
        ↓
      显示收藏夹列表（FavFolderAdapter）

点击某个收藏夹
  ↓
openFolder(folder)
  → MusicRepository.favResources(folder.id, cb)
    ↓ （IO 线程，自动翻页，上限 10 页）
    BiliApi.favResources(mediaId, page)  ← WBI 签名
    ↓
  显示收藏内容列表（SongAdapter）
```

---

## 五、关键设计决策与"为什么"

### 5.1 为什么 PlaybackService 自己调 BiliApi，而不走 MusicRepository？

`PlaybackService` 在解析播放队列时直接调用 `BiliApi.videoDetail()` 和 `BiliApi.bestAudio()`，没有经过 `MusicRepository`。

**原因**：PlaybackService 是 Service 组件，自带 `RESOLVER` 线程池（6 线程并行），需要并行解析多首歌曲以加快队列就绪速度。而 `MusicRepository` 的 IO 线程是**单线程串行**的（防风控），不适合并行解析整条播放队列。两者职责不同：Repository 面向 UI 的单次请求，Service 面向播放队列的批量解析。

### 5.2 为什么用自定义 SessionCommand 而不是标准 MediaController 命令？

标准 MediaController 只支持 `setMediaItems` 等命令，但 BiliMusic 的 MediaItem 需要先异步解析音频地址（cid → DASH 流），不能直接用 Song 列表构建 MediaItem。

**解决方案**：通过自定义 SessionCommand（`CMD_PLAY_QUEUE` / `CMD_PLAY_NEXT` / `CMD_ADD_QUEUE`）把原始 Song 列表 JSON 传给 Service，由 Service 在后台解析后填充 ExoPlayer 队列。这样 UI 侧不需要等待音频地址解析，点击即响应。

### 5.3 为什么 Song JSON 存在 MediaItem.metadata.extras 里？

UI 需要从当前播放的 MediaItem 还原出 Song 对象（显示标题、封面、收藏状态等）。MediaItem 的 URI 是音频流地址，不包含业务信息。把 Song 序列化为 JSON 存入 `metadata.extras`，通过 `PlaybackService.songOf(MediaItem)` 反序列化，是 Media3 下传递业务数据的惯用方式。

### 5.4 为什么 MusicRepository 用单线程 IO？

`Executors.newSingleThreadExecutor`，所有网络请求串行执行。

**原因**：B 站有风控，短时间并发请求过多会触发 412 / 验证码。串行请求更安全。播放队列的并行解析在 PlaybackService 的独立线程池中做，不影响 Repository。

### 5.5 为什么 WBI 签名有两套索引表（V1/V2）？

B 站 WBI 签名的 mixinKey 混淆表历史上存在 64 位（V1）和 84 位（V2）两个版本。`WbiSigner.mixinKey()` 根据 `imgKey + subKey` 的长度自适应选择：长度 > 64 用 V2，否则用 V1。这保证了在不同接口/不同时期签名都能正确生成。

### 5.6 为什么不引入 Retrofit / OkHttp / Room / Glide？

项目刻意保持依赖最小化：
- **HttpURLConnection** 替代 OkHttp：请求量不大，标准库够用
- **JSON 文件** 替代 Room：数据结构简单（三个列表），无需数据库
- **自写 ImageLoader** 替代 Glide：只需内存 LRU + 磁盘缓存，逻辑简单

这降低了 APK 体积和依赖维护成本，但也意味着没有 OkHttp 的连接池/拦截器、Room 的查询优化、Glide 的生命周期管理。

### 5.7 为什么 Cookie 存 SharedPreferences 而不存 JSON 文件？

Cookie 需要在 Application 启动时尽早加载（AuthManager.init 依赖它），且读写频繁。SharedPreferences 的 Apply 异步写入比 JSON 文件的全量读写更适合这个场景。而收藏/历史数据量大且结构复杂，用 JSON 文件 + Gson 更合适。

---

## 六、配置项与构建环境

### 6.1 构建配置

| 配置项 | 值 | 文件 |
|--------|-----|------|
| applicationId | `com.yuuuno224.bilimusic` | app/build.gradle |
| compileSdk | `android-36.1`（注意是预览版 SDK） | app/build.gradle |
| minSdk | 26（Android 8.0） | app/build.gradle |
| targetSdk | 35 | app/build.gradle |
| Java 版本 | 17 | app/build.gradle |
| ABI 分包 | `arm64-v8a`, `armeabi-v7a`（无 universal APK） | app/build.gradle |
| AGP | 9.2.1 | build.gradle |
| Gradle | 9.4.1 | gradle/wrapper |
| JDK | 21（JBR） | 构建环境 |

### 6.2 权限

| 权限 | 用途 |
|------|------|
| INTERNET | 网络请求 |
| POST_NOTIFICATIONS | 播放通知栏（Android 13+） |
| FOREGROUND_SERVICE | 后台播放保活 |
| FOREGROUND_SERVICE_MEDIA_PLAYBACK | 媒体播放前台服务类型 |
| WAKE_LOCK | 播放时保持 CPU 唤醒 |

### 6.3 关键配置文件

| 文件 | 说明 |
|------|------|
| `gradle.properties` | JVM 内存 3G、并行构建、AndroidX、nonTransitiveRClass |
| `app/src/main/AndroidManifest.xml` | 三 Activity + 一 Service，`usesCleartextTraffic=false` |
| `app/src/main/res/values/colors.xml` | Spotify 深色配色：bg=#121212, accent=#1DB9C4 |
| `app/src/main/res/values/themes.xml` | Material3 Dark NoActionBar 主题 |
| `.github/workflows/build-release.yml` | push tag v* 触发 CI 构建 + GitHub Release |

### 6.4 本地数据存储

| 数据 | 存储方式 | 路径 |
|------|----------|------|
| 收藏 / 历史 / 搜索历史 | JSON 文件 | `{filesDir}/music_store.json` |
| Cookie | SharedPreferences | `bili_cookies` → `cookies_json` |
| 用户信息 | SharedPreferences | `bili_auth` → `me` |
| 图片磁盘缓存 | WebP 文件 | `{cacheDir}/covers/{md5(url)}` |

### 6.5 依赖清单（app/build.gradle）

```groovy
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.10.0'
implementation 'androidx.constraintlayout:constraintlayout:2.0.1'
implementation 'androidx.recyclerview:recyclerview:1.3.2'
implementation 'androidx.viewpager2:viewpager2:1.0.0'
implementation 'com.google.code.gson:gson:2.11.0'
implementation 'androidx.media3:media3-exoplayer:1.5.1'
implementation 'androidx.media3:media3-session:1.5.1'
implementation 'androidx.media3:media3-common:1.5.1'
```

---

## 七、线程模型

| 线程 | 类型 | 用途 | 定义位置 |
|------|------|------|----------|
| music-io | 单线程 | Repository 的所有网络请求（串行防风控） | MusicRepository |
| bili-resolve | 固定池(6) | PlaybackService 并行解析播放队列 | PlaybackService |
| auth-io | 每次新建 | AuthManager 登录流程 | AuthManager |
| ImageLoader-POOL | 固定池(4) | 图片下载解码 | ImageLoader |
| Main | 主线程 | 所有 UI 回调 | Handler(Looper.getMainLooper()) |

**线程切换模式**：所有异步操作在子线程执行，完成后通过 `Handler(Looper.getMainLooper()).post()` 切回主线程回调 UI。

---

## 八、已知问题与待优化点

### 8.1 功能层面

1. **无分页加载**：搜索结果固定取第 1 页（20 条），无"加载更多"；收藏夹上限 10 页（200 条）
2. **无歌词显示**：纯音频播放，无歌词同步
3. **无音质选择**：固定取最高码率音频流，用户无法手动选音质
4. **无下载离线**：纯在线播放，无缓存/下载功能
5. **无播放进度记忆**：重启后不恢复上次播放位置
6. **搜索结果无分 P 支持**：视频多 P 时只取第一个 cid
7. **无刷新机制**：搜索首页下拉刷新未实现

### 8.2 健壮性层面

1. **错误处理较粗**：大量 `catch (Exception ignored)` 静默吞异常，用户无感知
2. **WBI 密钥刷新无重试**：签名失败直接抛异常，不自动重新获取密钥
3. **Cookie 过期无感知**：SESSDATA 过期后请求会失败，但无自动重新登录提示
4. **播放队列解析超时 20s 固定**：网络慢时可能丢弃未解析完的曲目
5. **ImageLoader 无生命周期绑定**：Fragment 销毁后图片回调可能触发 UI 操作（有 isAdded 判断但 ImageLoader 本身不感知）
6. **MusicStore 全量读写**：每次收藏/历史变更都全量序列化 JSON，数据量大时性能下降
7. **无 ProGuard 混淆**：release 构建 `minifyEnabled false`，APK 体积偏大

### 8.3 架构层面

1. **PlayerConnection 全静态**：单例用静态字段持有，多 Activity 场景下需注意生命周期
2. **无依赖注入**：全部静态方法调用，测试困难
3. **model 类无封装**：全部 public 字段，Gson 直接反射赋值
4. **tools/api-probe 未集成构建**：调试探针代码独立编译，未纳入 Gradle

---

## 九、后续开发指引

### 9.1 添加新 B 站接口的步骤

1. **在 `model/` 新建数据模型**：创建 POJO 类，字段名与 B 站 JSON key 一致（Gson 自动映射）
2. **在 `BiliApi.java` 新增方法**：
   - 需要签名的接口：`ensureBuvid()` + `ensureWbiKeys()` + `WbiSigner.sign(params, mixinKey)`
   - 不需要签名的接口：直接 `BiliHttp.get(url, null)`
   - 用 `parse(body, TypeToken)` 解析为 `ApiResp<T>`
3. **在 `MusicRepository.java` 新增异步方法**：
   - `IO.execute(() -> { ... MAIN.post(() -> cb.onResult(result)); })`
   - 将 B 站模型转换为 `List<Song>` 或其他 UI 需要的结构
4. **在 UI 调用**：`MusicRepository.xxx(new Callback<T>() { ... })`

### 9.2 添加新页面的步骤

1. 在 `ui/` 下新建子包（如 `ui/playlist/`）
2. 创建 Fragment/Activity + 布局 XML
3. 如需接入播放：用 `PlayerConnection.playQueue(ctx, songs, index)`
4. 如需列表展示：复用 `SongAdapter`，传入 `OnSongClick` 回调
5. 如需图片：用 `ImageLoader.load(url, bmp -> { ... })`
6. Fragment 中注册 `PlayerConnection.addListener(this)` 并在 `onDestroyView` 移除

### 9.3 修改播放逻辑的注意事项

- **PlaybackService** 是播放核心，修改队列逻辑注意线程安全：`RESOLVER` 线程池中操作，最终 `setMediaItems` 必须切回主线程
- **新增自定义命令**：在 `PlaybackService.onConnect` 注册 `SessionCommand`，在 `onCustomCommand` 处理，在 `PlayerConnection` 添加对应静态方法
- **循环模式**：直接用 `player.setRepeatMode()`，UI 在 `NowPlayingActivity.applyRepeatUi()` 同步
- **不要在 UI 线程调 BiliApi**：所有 BiliApi 方法都是同步阻塞的

### 9.4 修改网络层的注意事项

- **WBI 签名**：需要签名的接口必须用 `TreeMap`（字典序）传参，`WbiSigner.sign()` 会注入 `wts` 和 `w_rid`
- **风控**：`ensureBuvid()` 获取 buvid3，首次调用会自动触发；不要在短时间内并发大量请求
- **Cookie**：`BiliHttp.open()` 自动注入 `CookieStore.cookieHeader()`，无需手动添加
- **明文限制**：`usesCleartextTraffic=false`，所有 URL 必须 https；ImageLoader 内部已做 http→https 升级

### 9.5 修改本地存储的注意事项

- **MusicStore** 每次修改都全量 `save()`，频繁操作有性能开销
- **Song 的 equals/hashCode** 基于 bvid，同 bvid 视为同一首歌
- **新增存储字段**：修改 `StoreData` 内部类 + `load()` 中的读取逻辑，注意旧版本 JSON 兼容（用 null 判断）
- **不要在主线程做大量 MusicStore 操作**：虽然方法都 synchronized，但 `save()` 有文件 IO

### 9.6 构建与发布

1. **本地构建**：在 Android Studio 中 Build → Make Project（命令行 gradlew 有 native lock 冲突问题）
2. **发布流程**：
   - 在 `CHANGELOG.md` 顶部添加新版本描述
   - `git tag vX.Y.Z` + `git push origin vX.Y.Z`
   - GitHub Actions 自动构建各 ABI APK 并发布 Release
3. **分支管理**：`dev` 日常开发 → 合并到 `main` → 打 tag 发布

### 9.7 推荐的后续优化方向

| 优先级 | 方向 | 涉及模块 |
|--------|------|----------|
| 高 | 搜索结果分页加载 | SearchFragment + MusicRepository |
| 高 | Cookie 过期检测与重新登录提示 | AuthManager + BiliApi |
| 高 | 播放错误重试（音频地址过期） | PlaybackService.MediaItemBuilder |
| 中 | 播放进度记忆（SharedPreferences 存 bvid→position） | PlaybackService + MusicStore |
| 中 | 歌词显示（接入第三方歌词 API） | 新建 ui/lyric/ |
| 中 | 音质选择 | PlaybackService + NowPlayingActivity |
| 中 | 开启 ProGuard 混淆 | app/build.gradle + proguard-rules.pro |
| 低 | 下载离线播放 | 新建 store/DownloadStore + ExoPlayer cache |
| 低 | MV 模式（视频画面） | NowPlayingActivity + ExoPlayer video |
| 低 | 引入 OkHttp 替代 HttpURLConnection | net/ 全层 |

---

## 十、调试工具

`tools/api-probe/` 下有独立编译的 API 探针程序（未纳入 Gradle 构建），用于开发时验证 B 站接口：

| 文件 | 用途 |
|------|------|
| `_01_BiliApiProbe.java` | 基础 API 连通性测试 |
| `_02_PlayUrlProbe.java` | 播放地址解析测试 |
| `_03_AudioProbe.java` | 音频流下载测试 |
| `_04_WbiVerify.java` | WBI 签名验证 |
| `_05_FavProbe.java` | 收藏夹接口测试 |
| `TestLoad.java` | 加载测试入口 |

这些工具直接依赖 `net/` 包，可独立 javac 编译运行，用于在不启动 Android 的情况下调试接口。

---

## 附录：关键类索引

| 类 | 文件位置 | 核心方法 |
|----|----------|----------|
| `BiliMusicApp` | `BiliMusicApp.java` | `onCreate()` 初始化全局组件 |
| `BiliApi` | `net/BiliApi.java` | `searchVideos` `bestAudio` `qrGenerate` `qrPoll` `favFolders` `favResources` |
| `BiliHttp` | `net/BiliHttp.java` | `get()` `getResp()` `open()` |
| `WbiSigner` | `net/WbiSigner.java` | `mixinKey()` `sign()` `md5Hex()` |
| `CookieStore` | `net/CookieStore.java` | `put()` `get()` `has()` `cookieHeader()` `persist()` |
| `MusicStore` | `store/MusicStore.java` | `toggleFavorite()` `addHistory()` `addSearchHistory()` |
| `Song` | `store/Song.java` | `coverSmall()` `coverLarge()` `durationText()` |
| `AuthManager` | `auth/AuthManager.java` | `generate()` `pollOnce()` `isLoggedIn()` `logout()` |
| `MusicRepository` | `repo/MusicRepository.java` | `search()` `musicRanking()` `favFolders()` `resolveAudio()` |
| `PlaybackService` | `player/PlaybackService.java` | `handlePlayQueue()` `resolveQueueAndLoad()` `songOf()` |
| `PlayerConnection` | `player/PlayerConnection.java` | `connect()` `playQueue()` `toggle()` `next()` `currentSong()` |
| `ImageLoader` | `util/ImageLoader.java` | `load()` `init()` |
| `SongAdapter` | `ui/widget/SongAdapter.java` | `onBindViewHolder()` `release()` |

# 04 · 核心流程详解

> 目标：把散落的类连成线。
> 读完这篇，你应该能回答"从点击一首歌到听见声音，中间到底发生了什么"。

本篇用 `→` 表示调用或数据流动，`↓` 表示"下一步"。
每一节最后都有 **"出错会怎样"**——这往往比正常路径更有信息量。

---

## 流程 1：搜索

分两段看：**首页数据**（热词 + 排行榜）和**关键词搜索**。
后者更有代表性，因为它要过 WBI 签名这道关。

### 1.1 关键词搜索全链路

```
用户在搜索框输入关键词并回车
  ↓
SearchFragment.doSearch()
  ├── 关键词为空？→ showHome()，结束
  ├── MusicStore.addSearchHistory(keyword)      ← 本地记录，立刻刷新 chips
  ├── showResults() + progress 显示
  └── MusicRepository.search(keyword, callback)
        ↓ 切到 bili-io 线程（单线程串行）
      SearchApi.search(keyword, 1)
        ├── 参数用 TreeMap 装：keyword / page / page_size=20 /
        │   platform=pc / search_type=video
        └── BiliSession.getSigned(URL_SEARCH, params, TYPE, "搜索")
              ├── ensureFingerprint()             ← 第一次会真的发请求
              │     └── 已有 buvid3？→ 直接返回
              │           没有 → GET /x/frontend/finger/spi
              │                  → Json.parse → CookieStore.put("buvid3", ...)
              ├── wbiMixinKey()
              │     └── 缓存有效（< 2 小时）？→ 直接返回
              │           过期 → GET /x/web-interface/nav
              │                  → 取 wbi_img.img_url / sub_url
              │                  → 各取文件名（去目录去后缀）
              │                  → WbiSigner.mixinKey(imgKey, subKey)  得到 32 位密钥
              ├── WbiSigner.sign(params, mixinKey)
              │     ├── 复制成 TreeMap（按 key 字典序）
              │     ├── 注入 wts = 当前秒级时间戳
              │     ├── 逐个值：去掉 ! ' ( ) * → URL 编码
              │     ├── 拼成 a=1&b=2&wts=...
              │     └── w_rid = MD5(上面这串 + mixinKey)
              ├── GET https://api.bilibili.com/x/web-interface/search/type?<签名后的query>
              │     └── BiliHttp 自动带上 UA / Referer / Cookie
              ├── Json.parse(body, ApiResponse<SearchDto>)
              ├── code == 0 ？
              │     否 且 code == -403 → invalidateWbiKeys() → 重新取密钥 → 重试一次
              │     否 且其它        → 抛 BiliException(code, "搜索失败：...")
              └── 返回 SearchDto
        ↓
      SearchApi 把 dto 翻译成 model（这一步很重要，界面不认识 dto）
        for each SearchDto.Item:
          ├── bvid 为空？→ 跳过（已失效的视频）
          └── new Song(
                bvid,
                BiliText.cleanTitle(title),        ← 去掉 <em class="keyword"> 标签
                author,
                pic,
                BiliText.parseDurationSeconds(duration))   ← "3:45" → 225
        ↓ 注意：搜索结果不含 cid，Song.cid 保持 0
  ↓ 回到主线程
callback.onSuccess(List<Song>)
  ↓
SearchFragment：progress 隐藏 → results 清空并填入 → adapter.notifyDataSetChanged()
  ↓
RecyclerView 绑定每一行 → ImageLoader.load(coverSmall()) 拉封面（见流程 5）
```

### 1.2 首页数据（热词 + 排行榜）与缓存

```
SearchFragment.onResume()
  ├── renderHistory()   读 MusicStore.searchHistory()  → 渲染 chips（纯本地）
  ├── renderRecent()    读 MusicStore.history() 取前 5 → 渲染（纯本地）
  ├── loadHotWords()
  │     └── 静态缓存 < 10 分钟？→ 直接渲染
  │           否则 → MusicRepository.hotWords() → SearchApi.hotWords()
  │                  → 成功：写入静态缓存并渲染
  │                  → 失败：渲染 FALLBACK_HOT_WORDS（保证首页不空）
  └── loadRanking()
        └── 同上，取前 RANK_LIMIT=10 条；失败则整块隐藏
```

**为什么首页数据可以缓存，搜索结果不能**：
热词和排行榜是全局的、变化很慢（分钟级）；搜索结果与关键词强绑定，
而且用户按回车就是想要最新结果，缓存只会带来困惑。

### 1.3 出错会怎样

| 出错点 | 表现 | 处理 |
|---|---|---|
| 网络不通 | `IOException` → `BiliException`？不，是原始异常 | `MusicRepository` 捕获，`onError(消息)` → 界面显示提示 + Toast |
| HTTP 412 | `BiliHttp` 抛 `BiliException(412, "HTTP 412：...")` | 同上。提示里会带状态码，便于判断是风控 |
| 业务码 -403（签名失效） | `BiliSession.getSigned` 内部处理 | 自动刷新密钥重试一次，用户无感 |
| 业务码 -352（风控） | 抛 `BiliException` | 提示用户稍后再试 |
| 返回的 JSON 不是合法 JSON | `Json.parse` 抛 `BiliException` | 异常信息里带响应体前 200 字符，便于排查 |
| 回调到达时页面已销毁 | —— | `SearchFragment.isViewUsable()` 返回 false，直接 return，**不碰 View** |

---

## 流程 2：播放（最复杂的一条）

### 2.1 点歌 → 出声

```
用户在列表点第 4 项（下标 3）
  ↓
SongAdapter.onBindViewHolder 里注册的点击回调
  └── click.onClick(songs, position)          ← songs 就是当前列表
        └── SearchFragment.playFrom(...)
              └── PlayerConnection.playQueue(context, songs, 3)
                    └── sendCommand(CMD_PLAY_QUEUE, queueBundle(songs, 3))
                          ├── connect(ctx, cb)
                          │     ├── controller 已存在？→ 直接 cb(controller)
                          │     └── 否则：connecting 标记 → MediaController.Builder.buildAsync()
                          │            ↓ 握手（首次会拉起 PlaybackService）
                          │          PlaybackService.onCreate()
                          │            ├── 创建 ExoPlayer
                          │            │     · 音频用途 = USAGE_MEDIA / CONTENT_TYPE_MUSIC
                          │            │     · 自动处理音频焦点（来电话自动暂停）
                          │            │     · 拔耳机自动暂停
                          │            │     · 播放时持有网络唤醒锁
                          │            ├── 注册 Player.Listener（切歌记历史 / 出错重试 / 就绪清计数）
                          │            └── 创建 MediaSession
                          │                  · sessionActivity = 点击通知打开 NowPlayingActivity
                          │                  · 注册 3 条自定义命令
                          └── controller.sendCustomCommand(CMD_PLAY_QUEUE, bundle)
                                ↓ 命令跨线程送到 Service
                              PlaybackService.onCustomCommand()
                                └── handlePlayQueue(args)
                                      ├── Json 反序列化出 List<Song>
                                      └── resolveQueueAndLoad(songs, 3)
```

**队列解析阶段**（这是本项目最"重"的一段）：

```
resolveQueueAndLoad(songs, startIndex=3)
  ├── player.stop() + player.clearMediaItems()      ← 清掉旧队列
  └── AppExecutors.resolve().execute(...)            ← 进 bili-resolve 池（6 线程）
        仅占用 1 个线程做"指挥"，剩下 5 个线程干活
        ├── 为每首歌建一个 Callable<MediaItem>
        │     └── MediaItemFactory.create(song)
        │           ├── song.cid <= 0 ？→ PlaybackApi.videoMeta(bvid)
        │           │                     └── GET /x/web-interface/view?bvid=...
        │           │                     → 回填 song.cid 与 song.durationSec
        │           ├── PlaybackApi.bestAudio(bvid, cid)
        │           │     ├── GET /x/player/playurl?bvid=..&cid=..&fnval=16&fourk=1&platform=pc&qn=0
        │           │     └── pickBest(play)
        │           │           ① dash.audio 里 id 最大且带可用地址的
        │           │           ② dash.flac.audio（无损）
        │           │           ③ durl[0].url（兜底）
        │           │           都没有 → 抛 BiliException
        │           └── 构建 MediaItem
        │                 · mediaId = bvid
        │                 · uri = 音频地址，mimeType = audio/mp4 或 audio/flac
        │                 · metadata = 标题 / UP主 / 封面URI
        │                 · extras = { "song": Song的JSON }   ← 界面靠它还原 Song
        ├── AppExecutors.resolve().invokeAll(tasks, 20 秒)
        │     · 保证返回的 Future 顺序与任务顺序一致
        │     · 超时后未完成的会被取消
        └── 按原顺序收集成功的结果，并修正起始下标
              for i in 0..n-1:
                成功 → 若 i < startIndex 则 mappedStart++；加入 items
                失败/取消 → 跳过
              finalStart = min(mappedStart, items.size()-1)
  ↓ 回到主线程
player.setMediaItems(items, finalStart, 0)
player.prepare()
player.play()
```

**为什么要"修正起始下标"**：`startIndex` 是"在**原队列**里的下标"，
而载入播放器的列表已经剔除了解析失败的曲目，两者下标不再一一对应。
所以要从头数一遍"起始项之前有多少项成功解析了"，用这个数量当新的起始位置
（代码里就是那个 `mappedStart`）。不修正就会出现"点 A 播 B"。

> 注意下标是 **0 开始**的：`startIndex = 3` 指的是列表里**第 4 项**。

### 2.2 播放状态如何回流到界面

```
ExoPlayer 状态变化
  ↓
PlayerConnection 的 NOTIFY 监听器（5 类事件）
  · onIsPlayingChanged        播放/暂停切换
  · onMediaItemTransition     切歌
  · onPlaybackStateChanged    缓冲/就绪/结束
  · onRepeatModeChanged       循环模式改变
  · onTimelineChanged         队列被增删
  ↓
scheduleNotify()   ← 合并刷新：一帧内多次变化只通知一次
  ↓ AppExecutors.postOnMain
遍历所有 Listener，调用 onPlayerChanged()
  ├── MainActivity         刷新迷你播放条（标题/UP主/封面/播放图标/收藏状态）
  ├── NowPlayingActivity   刷新大封面、标题、播放按钮、循环按钮
  └── SongAdapter（每页一个） 只 notifyItemChanged 旧高亮行 + 新高亮行
```

### 2.3 自动记录播放历史

```
ExoPlayer 回调 onMediaItemTransition(mediaItem, reason)
  ↓
reason 是 AUTO / SEEK / PLAYLIST_CHANGED 之一？
  （三种都算"开始放这首歌"：自动连播 / 手动切歌 / 整条队列刚被替换）
  ↓
MediaItemFactory.songOf(mediaItem)   ← 从 extras 里的 JSON 还原 Song
  ↓
MusicStore.addHistory(song)          ← 去重、置顶、限 200 条、异步落盘
```

### 2.4 音频地址过期时的自愈

B 站 CDN 的音频地址带时效。App 挂后台几小时后回来播放，
或播放中途报错，最可能的原因就是地址失效了。

```
ExoPlayer 回调 onPlayerError(error)
  ↓
PlaybackService.handlePlaybackError()
  ├── 取出当前 MediaItem 对应的 Song
  ├── 这首歌已经重试过 MAX_ERROR_RETRIES(2) 次？→ 放弃，不重试
  │     （视频真的下架时，重试多少次都没用，必须停下避免死循环）
  ├── retryCounts[bvid] += 1
  └── AppExecutors.resolve().execute(...)
        ├── MediaItemFactory.create(song)   ← 用同一个 bvid 重新解析，拿到新地址
        └── 回到主线程
              player.replaceMediaItem(index, fresh)
              player.seekTo(index, 之前的位置)     ← 尽量不打断用户
              player.prepare() + player.play()
```

重试计数什么时候清零：`onPlaybackStateChanged(STATE_READY)` 时。
播起来了就说明这次地址是好的。

### 2.5 播放队列管理

| 操作 | 命令 | 行为 |
|---|---|---|
| 点列表里的歌 | `CMD_PLAY_QUEUE` | 清空旧队列，解析整条新队列，从点的那首开始 |
| 在迷你条/播放页打开队列弹窗 | （纯界面） | 读 `PlayerConnection.get()` 的队列渲染 |
| 弹窗里点某一行 | 直接 `player.seekTo(index, 0)` | 跳过去播 |
| 弹窗里点垃圾桶 | 直接 `player.removeMediaItem(index)` | **真的**从播放器队列删掉 |
| 循环模式 | 直接 `player.setRepeatMode(...)` | 三档：OFF / ALL / ONE |

> `CMD_PLAY_NEXT`（下一首播放）与 `CMD_ADD_QUEUE`（加入队列）已经在
> `PlayerConnection` 里实现好了，但没有界面入口——属于"预留能力"。

---

## 流程 3：登录

### 3.1 为什么要轮询

扫码/授权登录本质是**两台设备协作**：手机上的 B 站 App 负责确认，本 App 负责等待。
B 站没有给客户端推送通道，所以只能每隔 2 秒问一次"确认了吗"。

### 3.2 完整时序

```
用户点「我的」页的登录按钮
  ↓ startActivity(QrLoginActivity)
QrLoginActivity.onCreate()
  └── startLogin()
        ├── generation++                 ← 世代号，用于作废旧回调
        ├── polling = false，清掉待执行的 pollTask
        ├── status = "正在生成授权链接…"
        └── AuthManager.generate(callback)
              ↓ 切到 bili-auth 线程
            LoginApi.generate()
              └── GET /x/passport-login/web/qrcode/generate
                    → 得到 { url, qrcode_key }
              ↓ 回到主线程
            callback.onGenerated(url, qrcodeKey)
              ├── 检查世代号（旧的话直接 return）
              ├── 保存 authUrl / qrcodeKey
              ├── startedAt = now，polling = true
              ├── status = "已生成授权链接，请点击下方按钮在B站APP中确认"
              └── scheduleNextPoll()      ← 2 秒后执行 pollTask

（用户点「打开B站APP授权」→ 跳到 B 站 App → 点确认）
（此时本页进入 onPause，但轮询继续 —— 这一点很关键）

pollTask 执行 → AuthManager.pollOnce(qrcodeKey, callback)
  ↓ 切到 bili-auth 线程
LoginApi.poll(qrcodeKey)
  ├── GET /x/passport-login/web/qrcode/poll?qrcode_key=...
  ├── Json.parse → ApiResponse<QrLoginDto.Poll>
  ├── data.code == LoginState.SUCCESS(0) ？
  │     是 → 关键一步：CookieStore.putAll(
  │              CookieStore.parseSetCookie(response.headerValues("Set-Cookie")))
  │           ← 登录凭证不在响应体里，而在响应头里！
  └── 返回 data.code
  ↓
AuthManager.pollOnce 拿到 state
  ├── 不等于 LoginState.SUCCESS → 回主线程 cb.onStatus(state)
  └── 等于 LoginState.SUCCESS
        ├── CookieStore.persist(appContext)     ← 到这一步才真正"登录"（落盘）
        ├── LoginApi.profile()  → GET /x/web-interface/nav
        │     └── LoginApi 内部的 toProfile(NavDto) → UserProfile
        │     （拉不到也不当失败：构造一个 isLogin=true 的最小对象）
        ├── saveProfile(profile)                ← 写 SharedPreferences + 内存缓存
        └── 回主线程 cb.onSuccess(profile)
              ↓
        状态 = "登录成功"，Toast 欢迎语
        setResult(RESULT_OK) + finish()
  ↓
回到「我的」页
  └── onResume() → refreshUi()
        └── AuthManager.isLoggedIn() && profile.isLogin → 显示昵称、LV 等级、头像
```

### 3.3 三种非成功状态

| 状态码 | 含义 | 界面行为 |
|---|---|---|
| `LoginState.WAITING` = 86101 | 还没确认 | 显示"等待在B站APP中确认授权…"，继续轮询 |
| `LoginState.SCANNED` = 86090 | 已扫码待确认 | 显示"已在B站APP中确认，正在完成登录…"，继续轮询 |
| `LoginState.EXPIRED` = 86038 | 链接过期 | **重新生成**一条新链接（`startLogin()` 会让 `generation++`，旧回调自动作废） |

> **上一版文档在这里写错了**：把过期码写成 `86091`。
> 真实值是 `86038`（见 `model/LoginState.EXPIRED`）。这种错误会导致排查方向完全跑偏。

超过 `TIMEOUT_MS`（3 分钟）仍未成功 → `onTimeout()`：
停止轮询、禁用"打开"按钮、把"复制链接"按钮变成"重新生成"。

### 3.4 两个防重复的设计

**问题一：轮询重叠。** 如果"发请求"和"排下一次"是独立的（先发请求，立刻排下一次），
网络慢时就会有多个请求同时在飞。

解决：**下一次只在收到本次结果之后才安排**。
`onStatus` / `onError` 里才调用 `scheduleNextPoll()`，
而 `pollTask` 自己不再排下一次。

**问题二：过期重生成的竞态。** 假设第 5 秒发现链接过期，于是重新生成；
但第 4 秒发出的那次轮询结果刚到，它会以为自己还有效，可能再次触发重新生成，
于是两个轮询循环同时跑起来。

解决：**世代号 `generation`**。每次 `startLogin()` 都 `generation++`，
每个回调创建时捕获当时的值；回调执行时先比对，
不一致说明自己属于旧世代，直接 `return`。

> 这两个问题在重构前都真实存在（重复轮询那个是实际 bug），
> 详见 [07-faq-and-pitfalls.md](07-faq-and-pitfalls.md)。

### 3.5 登录态到底存在哪

| 内容 | 位置 | 作用 |
|---|---|---|
| `SESSDATA` / `bili_jct` / `DedeUserID` / … | `CookieStore` → SharedPreferences `bili_cookies` | **真正的凭证**。每个请求由 `BiliHttp` 自动带上 |
| 昵称 / 头像 / 等级 / mid | SharedPreferences `bili_auth` → `me` | 只是展示缓存，丢了不影响登录 |

`AuthManager.isLoggedIn()` 判断的是前者。
如果 B 站说"未登录"（-101），`refreshProfile()` 会清掉本地状态并让界面回到未登录。

---

## 流程 4：收藏与播放历史

### 4.1 收藏

```
点击心形按钮（三个入口：列表行 / 迷你条 / 播放页）
  ↓
MusicStore.toggleFavorite(song)
  ├── song 或 bvid 为空？→ 直接返回 false（空值安全）
  ├── favorites.remove(song)        ← Song.equals 只比 bvid，能移除"同一首歌"的旧对象
  ├── 现在还是收藏状态吗？→ nowFavorite
  │     是 → song.addedAt = now，favorites.add(0, song)   ← 新收藏排最前
  ├── persist()                     ← 异步落盘（见下）
  └── 返回 nowFavorite
  ↓
界面据此更新心形图标 + Toast「已收藏 / 已取消收藏」
```

### 4.2 播放历史（自动，用户无感）

```
ExoPlayer 切歌 → PlaybackService.onMediaItemTransition
  → MusicStore.addHistory(song)
      ├── history.remove(song)       ← 去重（同一首歌不会出现两次）
      ├── song.addedAt = now
      ├── history.add(0, song)       ← 最新的排最前
      ├── 超过 MAX_HISTORY(200) → 丢掉最旧的
      └── persist()
```

### 4.3 搜索历史

```
SearchFragment.doSearch() 的第一件事
  → MusicStore.addSearchHistory(keyword)
      ├── 已存在则先移除（去重）
      ├── 插到最前
      ├── 超过 MAX_SEARCH_HISTORY(10) → 丢掉最旧的
      └── persist()
  → renderHistory()   ← 立刻刷新 chips
```

### 4.4 落盘机制（三条流程共用）

```
persist()
  ├── storeFile 为 null（没 init）？→ 直接返回
  ├── 在 synchronized 块内做快照：
  │     Json.toJson(new StoreData(favorites, history, searchHistory副本))
  │     ← 在锁内快照，保证后台线程看到的是自洽的一份数据
  └── AppExecutors.store().execute(() -> writeAtomically(json))
        ↓ 切到 bili-store 单线程（独立于网络线程）
      writeAtomically(json)
        ├── 写到 music_store.json.tmp
        ├── 失败 → Log.w，保留旧文件不动
        └── Files.move(tmp, music_store.json, REPLACE_EXISTING)
              ↓ 这一步是原子的
            要么看到完整的旧文件，要么看到完整的新文件
```

### 4.5 读取路径

| 界面 | 读什么 | 何时 |
|---|---|---|
| `LibraryFragment` "我喜欢" | `MusicStore.favorites()` | `reload()` / `onResume()` |
| `LibraryFragment` "最近播放" | `MusicStore.history()` | 同上 |
| `SearchFragment` 搜索历史 | `MusicStore.searchHistory()` | `renderHistory()` |
| `SearchFragment` 最近播放 | `MusicStore.history()` 取前 5 | `renderRecent()` |
| `MeFragment` 统计数字 | 两个列表的 `size()` | `refreshUi()` |

这些方法都返回**副本**（`new ArrayList<>(list)`），所以界面拿到之后随便改都不会污染仓库。

---

## 流程 5：图片加载

### 5.1 三级查找

```
ImageLoader.load(url, callback)
  ├── callback 为 null？→ 返回一个空 Request
  ├── url 为空？→ 立刻 callback.onLoaded(null)      ← 界面显示占位图标
  ├── normalize(url)：// 开头补 https:，http:// 升级为 https://
  ├── 内存 LRU 命中？→ 立刻回调（主线程，最快路径）
  ├── 已经在下载同一个地址？
  │     是 → 只把自己的 Request 加进等待列表，然后返回
  │          （这就是"请求合并"，避免同一张图并发下载多次）
  │     否 → 登记"正在下载"，提交到 bili-image 池（4 线程）
  │           ├── diskGet(md5(url))
  │           │     文件存在且非空 → BitmapFactory.decodeFile
  │           ├── 没命中 → netGet(url)
  │           │     └── BiliHttp.open(url)   ← 自动带 UA / Referer / Cookie
  │           │         → 读字节流 → BitmapFactory.decodeByteArray
  │           │         → 成功则 diskPut
  │           │              └── compress(WebP) → 若目录超 64MB 删掉最旧的一半
  │           └── 成功后放入内存 LRU
  └── dispatch(url, bitmap)
        ├── 从"正在下载"表里取出所有等待者
        └── postOnMain：逐个检查 !cancelled → callback.onLoaded(bitmap)
```

### 5.2 为什么必须可取消

RecyclerView 会复用 itemView。考虑这个时序：

```
第 1 行显示「歌 A」，发起封面 A 的请求
用户快速滚动，第 1 行的视图被复用给「歌 Z」
封面 A 的请求这时返回了 → 图片被贴到「歌 Z」那一行上  ← 图片错位 bug
```

解决：每个 Holder 记住自己发起的 `ImageLoader.Request`，
在**重新绑定前**和**被回收时**调用 `cancel()`。
被取消的请求即使图片已经下载完，`dispatch` 里也会跳过它，不再回调。

### 5.3 三个调用点的收尾方式

| 调用点 | 取消时机 | 额外的防崩判断 |
|---|---|---|
| `SongAdapter.Holder` | `onBindViewHolder` 开头 + `onViewRecycled` | — |
| `MainActivity`（迷你条封面） | `onPlayerChanged` 里换图前 + `onDestroy` | 回调里判 `isFinishing() \|\| isDestroyed()` |
| `NowPlayingActivity`（大封面） | 换图前 + `onDestroy` | 同上 |
| `MeFragment`（头像） | 换图前 + `onDestroyView` | 回调里判 `isViewUsable()` |
| `FavFolderAdapter.Holder` | 同 `SongAdapter` | — |

> **为什么要多一层判断**：`cancel()` 能挡住"这次不要了"，
> 但挡不住"回调已经 post 到主线程队列里、正要执行时页面被销毁"。
> 两者都要有，才算完整。

---

## 流程 6（补充）：后台播放不被打断

用户按 Home 键或从最近任务划走时发生了什么：

```
按 Home 键
  ├── Activity 进 onPause/onStop（NowPlayingActivity 会停掉进度定时器）
  ├── ExoPlayer 继续播放（它在 Service 里，与 Activity 无关）
  ├── 前台服务 + 常驻通知保持存活（Android 不会轻易杀）
  └── PlayerConnection 的 MediaController 依然连着（静态单例）

从最近任务划掉 App
  └── PlaybackService.onTaskRemoved(rootIntent)
        ├── 正在播放？→ 什么都不做，继续播（音乐 App 的常规行为）
        └── 没在播放 → stopSelf()，避免留一个空的前台服务占内存
```

`onDestroy` 里的释放顺序也有讲究：

```
session.release()   ← 先断开会话，避免通知栏回调打到已释放的播放器上
player.release()    ← 再销毁播放器
```

---

继续读：[05-bilibili-api.md](05-bilibili-api.md) 看接口与签名细节，
或 [06-development-guide.md](06-development-guide.md) 开始动手改。

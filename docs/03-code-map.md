# 03 · 目录与代码地图

> 目标：知道"某个功能大概在哪个文件里"。
> 这是一份**查阅型**文档，不需要通读，遇到不认识的类时回来查即可。

阅读约定：

- **粗体** = 类名或文件名
- `等宽` = 代码标识符（方法名、常量名、字段名）
- 「注意」= 改这里时容易踩的坑

---

## 1. 源码总览

```
app/src/main/java/com/yuuuno224/bilimusic/
├── BiliMusicApp.java                    1 个文件
├── core/                               1 个文件（AppExecutors）
├── net/                                8 个文件 + api/ 4 个 + dto/ 10 个
├── model/                              5 个文件
├── store/                              1 个文件（MusicStore）
├── auth/                               1 个文件（AuthManager）
├── repo/                               1 个文件（MusicRepository）
├── player/                             3 个文件
├── util/                               1 个文件（ImageLoader）
└── ui/                                 10 个文件
```

---

## 2. `core` —— 基础设施

### AppExecutors

**全 App 唯一的线程出口。** 想知道"这个项目一共开了几条线程"，只看这个文件。

| 方法 / 常量 | 说明 |
|---|---|
| `main()` | 返回主线程 `Handler`，需要 `postDelayed` 时用 |
| `runOnMain(Runnable)` | 切回主线程。若已在主线程则**立即同步执行**（省一次消息投递） |
| `postOnMain(Runnable)` | 强制投递到主线程队列尾部，用于"合并刷新" |
| `io()` | 单线程：用户触发的网络请求（搜索/榜单/收藏夹） |
| `auth()` | 单线程：登录相关请求 |
| `resolve()` | 固定 6 线程：播放队列并行解析 |
| `image()` | 固定 4 线程：图片下载解码 |
| `store()` | 单线程：本地 JSON 落盘 |

「注意」所有线程都是 daemon（守护线程），进程结束时不会拖着 App 不退。
新增线程池请加在这里，不要在别的类里直接 `Executors.newXxx()`。

---

## 3. `net` —— 网络层

### 3.1 传输与基础工具

#### BiliHttp

**唯一直接碰网络连接的地方。** 基于 JDK 自带的 `HttpURLConnection`。

| 成员 | 说明 |
|---|---|
| `UA` / `REFERER` | 统一请求头。伪装桌面 Chrome + 带 Referer，否则部分接口直接 412 |
| `Response` | 一次请求的完整结果：`status` / `body` / `header(name)` / `headerValues(name)` |
| `get(url)` / `get(url, extraHeaders)` | 发起 GET。HTTP ≥ 400 时抛 `BiliException`（带状态码 + 响应体片段） |
| `open(url, extraHeaders)` | 返回已配好请求头的连接，**调用方必须自己 `disconnect()`** |
| `CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` | 10 秒 / 15 秒 |

「注意」`open()` 是给"需要自己控制读取过程"的两个场景用的：

| 调用方 | 场景 |
|---|---|
| `util/ImageLoader` | 下载图片字节流 → 解码 Bitmap |
| `net/api/LoginApi.revokeSession()` | 退出登录的 POST 请求（`get()` 只支持 GET） |

`PlaybackService` 播放音频流时**没有**用 `open()`：它需要的是 ExoPlayer 的
`DataSource.Factory`，只复用了 `BiliHttp.UA` 与 `BiliHttp.REFERER` 两个常量自建工厂
（见 `createHttpFactory()`）。

#### Json

**Gson 的唯一入口。** 全 App 共用一个 `Gson` 实例。

| 方法 | 说明 |
|---|---|
| `parse(body, type)` | 解析成指定类型；空响应 / 非法 JSON 时抛 `BiliException` |
| `toJson(Object)` | 对象转 JSON 字符串 |
| `fromJsonOrNull(json, Class/Type)` | 解析失败返回 `null` 而不抛异常（用于本地缓存） |

「注意」本地缓存损坏不应该让 App 崩溃，所以读本地数据一律用 `fromJsonOrNull`。

#### CookieStore

**登录 Cookie 的仓库。** 内存 `Map` + `SharedPreferences` 持久化。

| 成员 | 说明 |
|---|---|
| `PREF_NAME` / `KEY_COOKIES` | `"bili_cookies"` / `"cookies_json"` |
| `init(Context)` | 把上次登录的 Cookie 读进内存（在 `AuthManager.init` 里被调用） |
| `put` / `putAll` / `get` / `has` | 读写单个或多个 Cookie |
| `cookieHeader()` | 拼出 `Cookie:` 请求头；没有 Cookie 时返回 `null` |
| `clearSession()` | 只清登录相关的 5 个键，**保留** `buvid3` 等风控指纹 |
| `persist(Context)` | 写回磁盘 |
| `parseSetCookie(lines)` | 解析 `Set-Cookie`，只取第一个分号前的 `name=value` |

#### BiliException

**带错误码的异常。** 区分"网络断了"和"B 站业务拒绝了"。

| 常量 | 值 | 含义 |
|---|---|---|
| `CODE_NOT_LOGGED_IN` | -101 | 未登录 / 登录过期 |
| `CODE_SIGN_INVALID` | -403 | WBI 签名失效（密钥轮换了） |
| `CODE_RISK_CONTROL` | -352 | 风控拦截 |
| `CODE_NONE` | -1 | 无业务码（纯网络或解析错误） |

| 方法 | 说明 |
|---|---|
| `code()` | 业务码，或 HTTP 状态码（4xx/5xx 时） |
| `isSignInvalid()` / `isNotLoggedIn()` / `isRiskControl()` | 语义判断。`isRiskControl()` 同时认 -352 和 HTTP 412 |

#### WbiSigner

**WBI 签名算法实现。**

| 成员 | 说明 |
|---|---|
| `MIXIN_TABLE` | 64 个下标的混淆表（官方 JS 里硬编码的，是 0~63 的一个排列） |
| `mixinKey(imgKey, subKey)` | 把 64 字符拼起来按表重排，取前 32 个 |
| `sign(params, mixinKey)` | 注入 `wts` → 按键排序 → 去掉 `!'()*` → 编码 → 拼 `mixinKey` → MD5 得 `w_rid` |
| `md5Hex(text)` | MD5 十六进制小写 |

「注意」三步的**顺序不能变**：先排序、再去非法字符、再 URL 编码，最后才拼 mixinKey。
顺序错了服务端只会回一句"签名校验失败"，不会告诉你错在哪。

#### BiliSession

**请求前置准备 + 带签名请求的统一入口。**

| 方法 | 说明 |
|---|---|
| `ensureFingerprint()` | 首次调用时申请 `buvid3`/`buvid4` 风控指纹（`synchronized`，并发时只发一次） |
| `wbiMixinKey()` | 取缓存的 mixinKey；超过 `WBI_TTL_MS`（2 小时）自动重新拉取 |
| `invalidateWbiKeys()` | 主动丢弃密钥缓存 |
| `get(url, type, label)` | 调**不需要签名**的接口 |
| `getWithParams(baseUrl, params, type, label)` | 调**需要拼参数但不签名**的接口（参数自动排序 + 编码） |
| `getSigned(baseUrl, params, type, label)` | 调**需要 WBI 签名**的接口；遇到 -403 自动刷新密钥重试一次 |

「注意」这是"接口需不需要签名"的唯一权威来源。新增接口时先看同类接口用的是哪个方法。

#### UrlCodec

**URL 编码工具**（包内可见）。等价于 JavaScript 的 `encodeURIComponent`：
空格编成 `%20` 而不是 `+`，且不编码 `~`。

#### BiliText

**B 站返回文本的清洗。**

| 方法 | 说明 |
|---|---|
| `cleanTitle(raw)` | 去掉 `<em class="keyword">` 一类高亮标签，还原 HTML 实体，换行转空格 |
| `parseDurationSeconds(text)` | **同时兼容两种格式**：`"3:45"`（冒号文本）和 `"245"`（秒数） |

「注意」搜索结果给的是 `"3:45"`，排行榜/收藏夹给的是秒数。用同一个函数解析，
靠有没有冒号来区分。解析失败返回 0（界面显示 `--:--`）。

**为什么它在 `net` 而不是 `util`**：它处理的是"B 站线上数据的格式怪癖"，
属于网络层的知识。放这里还能避免 `util`（依赖 `net.BiliHttp`）与
`net.api` 互相依赖形成包级循环。

### 3.2 `net/api` —— 接口封装

**所有方法都是同步阻塞的，必须在子线程调用。**
它们的共同职责是：拼参数调接口 → 把 `dto` 翻译成 `model`。
**它们不切线程、不缓存、不回调。**

#### SearchApi

| 方法 | 签名 | 接口 |
|---|---|---|
| `search(keyword, page)` | `List<Song>` | 搜索，**需要 WBI 签名** |
| `hotWords()` | `List<String>` | 热门搜索词，不需要签名 |
| `musicRanking()` | `List<Song>` | 音乐区排行榜，不需要签名 |

「注意」搜索结果**不含 cid**，`Song.cid` 会是 0，播放时才去补。

#### PlaybackApi

| 成员 | 说明 |
|---|---|
| `VideoMeta{cid, durationSeconds}` | 视频详情里真正需要的两个值 |
| `videoMeta(bvid)` | 取 cid 与时长（搜索来的歌播放前必须调一次） |
| `bestAudio(bvid, cid)` | 取最优音频流，返回 `AudioStream` |
| `pickBest(play)` | 包内可见，便于单独测试；选择策略见下 |

音频流选择优先级：

1. `dash.audio` 中 `id` 最大**且带可用地址**的一条（id 越大音质越高）
2. `dash.flac.audio`（无损，通常要大会员）
3. `durl[0].url`（老接口整段流，音质最差，仅兜底）

「注意」`playurl` 接口目前**不签名**（`getWithParams`）。这是经过验证的现状：
盲目加签名一旦服务端校验的参数集合与我们对不上，会直接把播放功能打死。

#### LoginApi

状态码常量**不在这个类里**，而是放在 `model/LoginState`（这样界面层不必 import 网络层）。
四个值：`SUCCESS`(0) / `SCANNED`(86090) / `WAITING`(86101) / `EXPIRED`(86038)。

| 方法 | 说明 |
|---|---|
| `generate()` | 生成授权链接，返回 `QrLoginDto.Generate` |
| `poll(qrcodeKey)` | 查一次状态；**成功时顺手把 Cookie 写进 `CookieStore`**（但不落盘） |
| `profile()` | 读用户信息；未登录（-101）时返回 `isLogin=false` 的空对象而不是抛异常 |
| `revokeSession()` | 通知 B 站作废凭证（尽力而为，失败不影响本地退出） |
| `toProfile(NavDto)`（私有） | 把 dto 翻译成 `UserProfile`。放在这里是为了让 `model` 保持零依赖 |

#### FavoriteApi

| 成员 | 说明 |
|---|---|
| `PAGE_SIZE` | 20（B 站该接口单页上限就是 20） |
| `MAX_PAGES` | 10（自动翻页上限，约 200 条） |
| `folders(mid)` | 收藏夹列表，**需要签名** |
| `allResources(mediaId)` | 某个收藏夹的全部视频，内部自动翻页 |

「注意」`folders` 需要用户 `mid`，传 0 会被 B 站拒绝。
`allResources` 里 `type=2` 表示只取视频（过滤音频/专栏）。

### 3.3 `net/dto` —— JSON 镜像

**只映射字段，一行逻辑都没有。** 这是刻意的：
B 站改字段名时，你只需要改这里和对应的 `api` 映射。

| 类 | 对应接口 | 备注 |
|---|---|---|
| `ApiResponse<T>` | 所有接口的外壳 | `{code, message, data}`；**失败也是 HTTP 200**，必须查 `code` |
| `SearchDto` | `search/type` | 标题带 `<em class="keyword">` 高亮标签 |
| `HotSearchDto` | `search/square` | `show_name` 比 `keyword` 更像人话 |
| `RankingDto` | `ranking/index` | **它的 `data` 直接是数组**；`duration` 是秒数不是 `"3:45"` |
| `VideoDetailDto` | `view` | 取 `cid` 和 `duration` |
| `PlayUrlDto` | `player/playurl` | 同义字段有两套命名（`baseUrl` / `base_url`），都保留 |
| `NavDto` | `nav` | 一件两用：用户信息 + **WBI 密钥图片地址** |
| `FavoriteDto` | 收藏夹两个接口 | 内含 `FolderList` 与 `ResourceList` 两组结构 |
| `FingerprintDto` | `finger/spi` | `b_3` → `buvid3`，`b_4` → `buvid4` |
| `QrLoginDto` | 登录两个接口 | 内含 `Generate` 与 `Poll`；**注意 Poll 里还有一层 `code`** |

「注意」下划线风格的 JSON 键必须加 `@SerializedName`，例如
`@SerializedName("show_name") public String showName;`。
忘了加的话 Gson 映射不上，字段会一直是 `null`，而且**不会报错**——这类 bug 很难查。

---

## 4. `model` —— 领域模型

**不依赖任何层**（连 Gson 注解都不加）。`net/api` 负责把 dto 翻译成它们。

### Song

**全 App 最核心的数据结构**：搜索结果、排行榜、收藏、历史、播放队列里流动的都是它。

| 字段 | 说明 |
|---|---|
| `bvid` | 视频编号，**同时是主键**（`equals`/`hashCode` 只看它） |
| `cid` | 分 P 编号；**0 表示还没解析过**，播放前需要补 |
| `title` / `up` / `cover` | 展示用 |
| `durationSec` | 秒；0 表示未知 |
| `addedAt` | 加入收藏/历史的时间戳 |

| 方法 | 说明 |
|---|---|
| `coverUrl()` | 把 `//` 开头的协议相对地址补成 https |
| `coverSmall()` / `coverLarge()` | 拼 B 站 CDN 的裁剪参数（`@320w_200h.webp` / `@672w_378h.webp`） |
| `durationText()` / `durationText(long)` | 秒数转 `m:ss`；未知时返回 `--:--` |

「注意」`coverSmall()` 在 `cover` 为空时返回**空串**（而不是拼出一个非法 URL）。
`ImageLoader` 收到空串会走"没有图片"分支。

### UserProfile

当前登录用户（`isLogin` / `mid` / `uname` / `face` / `level`）。
它同时是**本地缓存的序列化对象**（存进 SharedPreferences 的 `bili_auth/me`）。

- `displayName()` —— 昵称为空时给占位，避免界面出现 `null`

「注意」**它刻意没有 `from(NavDto)` 这样的转换方法。**
把接口的嵌套结构（`level_info.current_level`）拍平成 `level` 这件事，
由 `net/api/LoginApi` 里的私有方法负责。
原因是：一旦 `UserProfile` 认识 `NavDto`，`model` 就反过来依赖 `net.dto`，
而"model 零依赖"是本项目最重要的一条分层规则。

### LoginState

登录进度状态码常量：`SUCCESS`(0) / `SCANNED`(86090) / `WAITING`(86101) / `EXPIRED`(86038)。

「注意」这些值来自 B 站轮询接口的 `data.code`，本质是协议常量，
放在 `model` 是**刻意的分层妥协**：登录页需要按状态显示不同文案，
如果常量定义在 `net/api/LoginApi` 里，界面就不得不 import 网络层的类。
（也不能放在 `auth/AuthManager` 里——`auth` 依赖 `net.api`，
反过来 `net.api` 依赖 `auth` 就成环了。）

### FavoriteFolder

一个 B 站收藏夹（`id` / `title` / `mediaCount` / `cover`），带 `countText()`。

「注意」`id` 是**收藏夹 id**，不是用户 uid。请求夹内内容用的是它。

### AudioStream

一条可直接播放的音频流（`qualityId` / `url` / `bandwidth` / `flac`），带 `mimeType()`。

「注意」**这个地址带时效**（B 站 CDN 链接通常几小时后 403）。
它只适合当场用掉，**绝不能存进本地文件**。

---

## 5. `store` —— 本地持久化

### MusicStore

**收藏 / 播放历史 / 搜索历史。** 三个列表 + 一个 JSON 文件。

| 成员 | 说明 |
|---|---|
| `FILE_NAME` | `"music_store.json"`，位于 `{filesDir}` |
| `MAX_HISTORY` / `MAX_SEARCH_HISTORY` | 200 / 10 |
| `init(Context)` | **同步**读取文件（文件小、界面一打开就要用） |
| `isFavorite(bvid)` | 空值安全（`bvid` 为 null 返回 false） |
| `toggleFavorite(song)` | 返回**操作之后**是否已收藏，界面据此显示"已收藏/已取消收藏" |
| `favorites()` / `history()` / `searchHistory()` | 返回**副本**，外部修改不影响仓库 |
| `addHistory(song)` | 去重后插到最前，超上限丢最旧的 |
| `addSearchHistory(kw)` / `clearSearchHistory()` | 同上 |
| `clearHistory()` | 清空播放历史 |

内部机制：

- 所有公开方法都是 `synchronized`（在主线程改内存，很快）
- `persist()` 在锁内做快照，然后把 JSON 串交给 `AppExecutors.store()` 后台写
- `writeAtomically()` 先写 `music_store.json.tmp`，再用 `Files.move(..., REPLACE_EXISTING)` 替换

「注意」改动 `StoreData` 的字段名等于**改变存储格式**，会导致老用户数据读不出来。
加字段是安全的（旧的 JSON 里没有该字段，Gson 会给默认值）；改字段名不安全。

---

## 6. `auth` —— 登录态

### AuthManager

| 成员 | 说明 |
|---|---|
| `PREF_NAME` / `KEY_PROFILE` | `"bili_auth"` / `"me"` |
| `PROFILE_TTL_MS` | 10 分钟；超过就认为是"过期的用户信息" |
| `LoginCallback` | `onGenerated` / `onStatus` / `onSuccess` / `onError`，**都在主线程回调** |
| `ProfileCallback` | `onResult(profile)`；失败或未登录时为 `null` |
| `init(Context)` | 顺带完成 `CookieStore.init`（所以 `BiliMusicApp` 不必再单独初始化 Cookie） |
| `isLoggedIn()` | 本地有没有 `SESSDATA` + `bili_jct`。**不代表凭证一定还有效** |
| `cachedProfile()` / `isProfileStale()` | 用户信息缓存与过期判断 |
| `generate(cb)` / `pollOnce(key, cb)` | 登录两步 |
| `refreshProfile(cb)` | 从网络刷新；若 B 站说未登录则**顺手清理本地状态** |
| `logout(onDone)` | 先尽力作废凭证，再清本地，**最后才回调** |

「注意」`logout` 是异步的。界面刷新必须放在 `onDone` 回调里，
否则刷新时本地 Cookie 还没清掉，会显示"仍然已登录"。

---

## 7. `repo` —— 业务编排

### MusicRepository

**只有 5 个公开方法，写法完全一致。** 它的存在就是为了让界面不写 `new Thread()`。

| 方法 | 返回 |
|---|---|
| `search(keyword, cb)` | `List<Song>` |
| `hotWords(cb)` | `List<String>` |
| `musicRanking(cb)` | `List<Song>` |
| `favoriteFolders(mid, cb)` | `List<FavoriteFolder>` |
| `favoriteResources(mediaId, cb)` | `List<Song>` |

`Callback<T>` 的 `onSuccess` / `onError` **都在主线程**被调用。

内部只有一个私有骨架 `run(errorLabel, Callable, Callback)`：

```
AppExecutors.io().execute(() -> {
    try { T r = task.call(); AppExecutors.runOnMain(() -> cb.onSuccess(r)); }
    catch (Exception e) { AppExecutors.runOnMain(() -> cb.onError(可读文案)); }
});
```

「注意」回调可能在任何一次页面切换之后才到达。
界面里必须判断页面是否还活着（见 [07 篇](07-faq-and-pitfalls.md) 第 3 节）。

---

## 8. `player` —— 播放

### MediaItemFactory

**`Song` → `MediaItem` 的翻译器。**

| 方法 | 说明 |
|---|---|
| `create(song)` | 补 cid（若为 0）→ 取音频地址 → 构建 `MediaItem`；顺带回填 `song.cid`/`durationSec` |
| `songOf(item)` | 从 `mediaMetadata.extras` 里的 JSON 反序列化出 `Song` |
| `bvidOf(item)` | 只取 bvid（列表高亮用，省一次解析） |

「注意」`EXTRA_SONG = "song"` 这个键名两端必须一致（写和读都在这一个文件里，所以安全）。

### PlaybackService

**App 里唯一持有 ExoPlayer 的地方。** 继承 `MediaSessionService`。

| 成员 | 说明 |
|---|---|
| `CMD_PLAY_QUEUE` / `CMD_PLAY_NEXT` / `CMD_ADD_QUEUE` | 三条自定义命令 |
| `RESOLVE_TIMEOUT_SECONDS` | 20 秒；整条队列解析的总超时 |
| `MAX_ERROR_RETRIES` | 2 次；同一首歌因地址失效重新解析的上限 |
| `onCreate()` | 建 ExoPlayer + MediaSession，注册播放器监听 |
| `onGetSession()` | 返回 MediaSession（Media3 要求的接入点） |
| `onTaskRemoved()` | 没在播放就关掉服务，避免留空的前台服务 |
| `queueBundle(songs, index)` | 静态方法，供 `PlayerConnection` 打包命令参数 |

关键私有方法：

| 方法 | 说明 |
|---|---|
| `resolveQueueAndLoad(songs, startIndex)` | 并行解析整条队列 → 按原顺序载入 → 从 startIndex 播放 |
| `handleInsert(args, asNext)` | "下一首播放" / "加入队列" |
| `handlePlaybackError()` | 地址过期时重新解析当前歌并重试（有次数上限） |
| `createHttpFactory()` | 音频流的 HTTP 配置（带 Referer、允许跨协议重定向） |

「注意」`resolveQueueAndLoad` 里有一个**起始位置映射**：如果目标之前的某首歌解析失败被跳过，
起始下标要往前挪。忘了这一步会出现"点 A 播 B"。

### PlayerConnection

**界面侧的遥控器封装（静态单例）。**

| 成员 | 说明 |
|---|---|
| `Listener` | `onPlayerChanged()`，**保证在主线程** |
| `connect(ctx, onReady)` | 建立连接（幂等）；`onReady` 在主线程回调，失败时传 `null` |
| `acquire(ctx)` | 语义化的"我来了"，内部就是 `connect(ctx, null)` |
| `get()` | 取 `MediaController`，未连接时返回 `null`（**调用方必须判空**） |
| `release()` | 断开连接。**一般不要调用**，只在确定彻底退出播放时用 |
| `addListener` / `removeListener` | 注册/注销；**`onDestroy` 里必须注销** |
| `playQueue(ctx, songs, index)` | 播放整条队列 |
| `playNext(ctx, song)` / `addToQueue(ctx, song)` | 下一首播放 / 加入队列 |
| `toggle()` / `next()` / `prev()` | 播放暂停 / 上一首 / 下一首 |
| `currentSong()` / `currentBvid()` / `isPlaying()` | 当前状态查询 |

内部机制：

- 监听 `onIsPlayingChanged` / `onMediaItemTransition` / `onPlaybackStateChanged` /
  `onRepeatModeChanged` / `onTimelineChanged` 五类事件
- `scheduleNotify()` 做**合并刷新**：一帧内多次变化只通知界面一次
- 连接回调通过 `AppExecutors.main()::post` 投递，从根上避免"回调跑在子线程"

「注意」`get()` 返回的 `MediaController` 就是 `Player`，
所以想调 Media3 的其它 API（如 `setRepeatMode`）直接用 `get()` 即可。

---

## 9. `util` —— 通用工具

### ImageLoader

**图片加载：内存 LRU + 磁盘缓存 + 请求合并 + 可取消。**

> `util` 包现在**只有这一个类**。它依赖 `core.AppExecutors` 与 `net.BiliHttp`
> （为了复用统一的请求头：UA / Referer / Cookie），只被 `ui` 使用。
> 原先放在这里的 `BiliText` 已移到 `net/BiliText`。

| 成员 | 说明 |
|---|---|
| `Callback` | `onLoaded(bitmap)`，**主线程**回调；失败时传 `null` |
| `Request` | 加载句柄，`cancel()` 后即使图已经在路上也不会再回调 |
| `MEMORY_CACHE_BYTES` / `DISK_CACHE_BYTES` | 16 MB / 64 MB |
| `init(Context)` | 准备 `cacheDir/covers` |
| `load(url, cb)` | 返回 `Request`；`url` 为空时立刻回调 `null` |

三级查找顺序：内存 → 磁盘 → 网络（网络成功后同时写磁盘与内存）。
两个关键机制：

- **请求合并**：同一 URL 正在下载时，后来的请求只登记等待，不再发起下载
- **可取消**：`onViewRecycled` / `onBindViewHolder` 开头调用旧请求的 `cancel()`

「注意」`normalize()` 会把 `http://` 与 `//` 开头的地址升级为 `https://`。
因为 `AndroidManifest` 里 `usesCleartextTraffic="false"`，明文请求会被系统直接拒绝。

---

## 10. `ui` —— 界面

### MainActivity

**壳**：底部三栏 + 迷你播放条 + 通知权限申请。不含业务逻辑。

| 成员 | 说明 |
|---|---|
| `REQUEST_NOTIFICATIONS` | 权限请求码 1001 |
| `onCreate` | 绑定视图、设置底部导航、默认显示搜索页、连接播放器、申请通知权限 |
| `openLibraryTab(tab)` | 供"我的"页快捷入口调用；会把目标标签传给 `LibraryFragment` |
| `onPlayerChanged()` | 刷新迷你播放条（标题/封面/播放图标/收藏状态） |
| `requestNotificationPermissionIfNeeded()` | 仅 Android 13+，已授权则不重复弹 |

「注意」切页面用的是 `replace()`，所以每次切换都会重建 Fragment——
这也是 `SearchFragment` 给首页数据加静态缓存的原因。

### SearchFragment

搜索首页 + 结果列表。**这个类集中体现了"异步回调要防页面已销毁"的写法。**

| 成员 | 说明 |
|---|---|
| `FALLBACK_HOT_WORDS` | 网络失败时的兜底热词 |
| `HOME_CACHE_TTL_MS` | 10 分钟；首页数据（热词/排行榜）的静态缓存有效期 |
| `loadHotWords()` / `loadRanking()` | 命中缓存直接渲染，否则请求 |
| `renderHistory()` / `renderRecent()` | 读本地数据（不走网络） |
| `doSearch()` | 记录搜索历史 → 显示进度 → 请求 → 渲染结果 |
| `isViewUsable()` | `isAdded() && getView() != null`；**每个回调第一行都要检查它** |

「注意」热门词与排行榜用的是 `private static` 缓存。这是**有意为之**的取舍：
Fragment 每次切换都会重建，静态缓存能避免重复请求；代价是进程内共享，多实例时会互相看到缓存。

### LibraryFragment

乐库三个标签，其中"收藏夹"是**两级导航**（列表 → 夹内内容）。

| 成员 | 说明 |
|---|---|
| `ARG_TAB` | 由外部指定初始标签 |
| `TAB_FAVORITE` / `TAB_HISTORY` / `TAB_FOLDERS` | 0 / 1 / 2 |
| `showingFolderList` | `true` = 正在看收藏夹列表；`false` = 正在看夹内内容 |
| `backCallback` | 只在夹内时拦截返回键（否则用户按返回会直接退出 App） |
| `loadFolders()` | 先确认登录与 `mid`，缺用户信息时先 `refreshProfile` 再请求 |
| `openFolder(folder)` | 进入夹内；`backToFolders()` 返回 |

「注意」`actionButton`（右上角那个按钮）在两种标签下语义不同：
"最近播放"里是**清空历史**，"收藏夹内容"里是**返回列表**。改动时别搞混。

### FavFolderAdapter

收藏夹列表适配器（封面 + 名字 + 内容数量）。
`release()` 是空实现——它不监听播放器，保留这个方法只是让调用方写法统一。

### MeFragment

账号信息、快捷入口、关于。

| 成员 | 说明 |
|---|---|
| `refreshUi()` | 按当前登录态刷新整页 |
| 退出登录按钮 | `AuthManager.logout(onDone)`，**在回调里**刷新界面 |

「注意」`onResume` 里会在缓存过期时（`isProfileStale()`）顺手刷新一次用户信息。

### NowPlayingActivity

沉浸式播放页。

| 成员 | 说明 |
|---|---|
| `PROGRESS_INTERVAL_MS` | 500 ms |
| `SEEK_BAR_MAX` | 1000，**必须与布局里的 `android:max="1000"` 一致** |
| `progressTick` | 定时读播放进度并刷新进度条 |
| `dragging` | 用户拖动进度条时为 true，此时不刷新进度显示 |
| `cycleRepeatMode()` | 顺序 → 列表循环 → 单曲循环 → 顺序 |
| `applyRepeatUi()` | 读播放器**实际**的循环模式来刷新按钮图标 |

「注意」定时器在 `onResume` 启动、`onPause` 停止。
如果只在 `onCreate` 启动，切到后台后仍会每秒唤醒 CPU 一次，白耗电。
另外这个页面**没有申请通知权限**，那是 `MainActivity` 的活。

### PlaylistDialog

播放队列弹窗。静态工具方法 `show(context)`，用 `BottomSheetDialog` 实现。
队列为空时只弹一个 Toast，不开空窗口。

### PlaylistAdapter

播放队列适配器。它的数据源是**播放器本身**（`Player`）而不是一个 `List`，
所以 `getItemCount()` 等方法都是实时问播放器要；增删后必须手动 `notifyDataSetChanged()`。

「注意」删除时调用的是 `player.removeMediaItem(i)`——**真的**从播放器队列里删掉，
不是在界面列表里删。界面之所以能同步，是因为 `PlayerConnection` 监听了 `onTimelineChanged`。

### QrLoginActivity

授权链接登录页。

| 成员 | 说明 |
|---|---|
| `POLL_INTERVAL_MS` | 2000 ms |
| `TIMEOUT_MS` | 180000 ms（3 分钟） |
| `generation` | **世代号**：每次重新生成 +1，回调发现自己是旧世代就直接忽略 |
| `pollTask` | 到点查一次状态 |
| `scheduleNextPoll()` | **在收到本次结果之后**才安排下一次，天然不会重叠 |
| `startLogin()` | 生成新链接并启动轮询 |
| `onTimeout()` / `restartLogin()` | 超时后把"复制链接"按钮变成"重新生成" |

「注意」轮询**只在 `onDestroy` 才停止**。
因为用户确认登录时必须切到 B 站 App，本页会进 `onPause`——
如果那时停掉轮询，就永远等不到成功。

### SongAdapter

**多个页面复用的歌曲列表适配器**（搜索/排行榜/最近播放/收藏/收藏夹内容）。

| 成员 | 说明 |
|---|---|
| `OnSongClick` | `onClick(songs, position)`，把整个列表交出去当播放队列 |
| `highlightedBvid` | 上次高亮的 bvid，用来算出"哪两行要刷新" |
| `onPlayerChanged()` | 只 `notifyItemChanged` 受影响的两行，**不做全量刷新** |
| `release()` | 注销播放器监听，**页面销毁时必须调用** |
| `Holder.coverRequest` | 本行正在进行的图片请求，重绑前取消 |

「注意」构造函数里就注册了播放器监听，所以 `release()` 漏调会内存泄漏。

---

## 11. 资源文件地图

### 布局（`res/layout/`）

| 文件 | 对应界面 |
|---|---|
| `activity_main.xml` | 主体：`fragment_container` + `mini_bar` + `bottom_nav` |
| `activity_now_playing.xml` | 播放页（`np_seek` 的 `android:max="1000"` 与代码常量对应） |
| `activity_qr_login.xml` | 登录页（中间那张图是启动图占位，**不是二维码**） |
| `fragment_search.xml` | 搜索页（搜索栏 + `search_home` + `result_list` + 进度/提示） |
| `fragment_library.xml` | 乐库（`library_count` + `library_tabs` + `library_list`） |
| `fragment_me.xml` | 我的（用户卡片 + 横向快捷入口 + GitHub/关于 + 登录按钮） |
| `dialog_playlist.xml` | 播放队列弹窗 |
| `item_song.xml` | 歌曲列表行 |
| `item_fav_folder.xml` | 收藏夹列表行 |
| `item_playlist_song.xml` | 队列弹窗里的行 |
| `item_search_chip.xml` | 搜索关键词胶囊 |

### 其它资源

| 位置 | 内容 |
|---|---|
| `res/values/colors.xml` | 配色：`bg=#121212`、`accent=#1DB9C4`、`heart_red=#E91E63` 等 |
| `res/values/themes.xml` | `Theme.BiliMusic`，继承 Material3 Dark NoActionBar |
| `res/values/strings.xml` | 少量文案（注：**很多界面文字是硬编码在 Java 里的**，见 07 篇已知限制） |
| `res/menu/bottom_nav.xml` | 底部三个标签：`nav_search` / `nav_library` / `nav_me` |
| `res/color/nav_item_color.xml` | 底部导航选中/未选中颜色 |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应应用图标（引用了 `drawable/ic_launcher_fg.xml`） |
| `res/drawable/*.xml` | 图标与背景（矢量图，**没有任何位图资源**） |

---

## 12. 配置与构建文件

| 文件 | 作用 | 改动时注意 |
|---|---|---|
| `settings.gradle` | 仓库配置、模块声明 | 加国内镜像就改这里 |
| `build.gradle`（根） | 声明 AGP 插件版本 | 与 `app/build.gradle` 的 AGP 版本配套 |
| `app/build.gradle` | 应用模块：SDK 版本、ABI 分包、依赖 | `namespace` 决定 `R` 类的包名 |
| `gradle.properties` | JVM 内存、AndroidX 开关、资源 ID 策略 | — |
| `gradle/wrapper/gradle-wrapper.properties` | 固定 Gradle 版本 | 下载慢可换镜像 URL |
| `local.properties` | SDK 路径 | **不入库**，每台机器自己写 |
| `app/proguard-rules.pro` | 混淆保留规则 | 当前 `minifyEnabled false`，规则已备好 |
| `build.ps1` | 一键构建脚本 | 自动找 JDK，用 `gradlew` 执行 |
| `.github/workflows/build-release.yml` | CI：推 `v*` tag 自动构建并发布 | 从 `CHANGELOG.md` 提取 Release 说明 |
| `tools/api-probe/*.java` | 独立的接口探针程序 | **未纳入 Gradle 构建**，需要单独 `javac` 编译 |

---

下一步：[04-core-flows.md](04-core-flows.md) 跟着真实流程走一遍。

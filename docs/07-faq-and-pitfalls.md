# 07 · 踩坑记录与 FAQ

> 这篇有三个用途：
> 1. **回头看**：重构改掉了哪些真实缺陷（万一你觉得行为变了，来这对照）
> 2. **少走弯路**：Android 新手在这个项目里最容易混淆的概念
> 3. **知道边界**：本项目还没做什么

---

## 第一部分：这次重构修掉的真实缺陷

下面是**逐条核对过代码**确认存在的问题，不是猜测。
每条都给出症状、根因、修法——因为症状常常指向完全错误的根因。

### A 类：会导致崩溃或数据丢失（severity 高）

#### A1. 播放地址过期后无法恢复

| | |
|---|---|
| **症状** | 挂后台几小时后回来点播放，报错且不会恢复；必须手动切歌 |
| **根因** | B 站 CDN 的音频地址带时效（通常几小时后 403）。原代码没有任何 `onPlayerError` 处理，地址失效就直接失败 |
| **修法** | `PlaybackService` 监听 `onPlayerError` → 用同一个 bvid 重新解析地址 → `replaceMediaItem` + `seekTo` 原位置 + 重新播放。重试上限 2 次（`MAX_ERROR_RETRIES`），避免视频已下架时死循环 |
| **位置** | `player/PlaybackService.java` → `handlePlaybackError()` |

#### A2. 本地数据可能被整个写坏

| | |
|---|---|
| **症状** | 偶发"收藏和播放历史全没了" |
| **根因** | `MusicStore.save()` 直接 `new FileOutputStream(file)` 覆盖写。如果写到一半进程被杀（系统回收、崩溃、断电），文件就变成半截 JSON，下次启动解析失败 → 全部数据丢失 |
| **修法** | 原子写：先写 `music_store.json.tmp`，再用 `Files.move(tmp, target, REPLACE_EXISTING)` 一步替换。操作系统保证 move 是原子的，所以只会看到完整的新文件或完整的旧文件 |
| **位置** | `store/MusicStore.java` → `writeAtomically()` |

#### A3. 页面销毁后仍操作视图

| | |
|---|---|
| **症状** | 搜索页偶发崩溃 |
| **根因** | `SearchFragment.renderHot()` 里写的是 `if (!isAdded() \|\| words.isEmpty()) { fillChips(...); return; }` —— Fragment **已经销毁**时，这个条件成立，代码反而去操作已销毁的 View |
| **修法** | 所有网络回调第一行统一判 `isViewUsable()`（= `isAdded() && getView() != null`）。两个条件都要判：`isAdded()` 管"是否还挂在 Activity 上"，`getView() != null` 管"视图是否还活着" |
| **位置** | `ui/search/SearchFragment.java` |

#### A4. 播放器状态回调可能跑在子线程

| | |
|---|---|
| **症状** | 偶发 `CalledFromWrongThreadException`，或界面状态莫名不更新 |
| **根因** | `PlayerConnection.connect()` 用的是 `future.addListener(runnable, Runnable::run)`。`Runnable::run` 这个 Executor 会在**完成 future 的那个线程**上直接执行 runnable，而那不一定主线程。紧接着的 `notifyListeners()` 就会在子线程里调用 `setText` / `notifyDataSetChanged` |
| **修法** | ①监听器改用 `AppExecutors.main()::post` 执行；②`scheduleNotify()` 内部再 `postOnMain` 兜一层。双保险 |
| **位置** | `player/PlayerConnection.java` |

#### A5. 登录页链接过期后会同时跑两个轮询

| | |
|---|---|
| **症状** | 链接过期后请求量翻倍，登录流程行为不可预测 |
| **根因** | `pollTask` 每次先发请求、**立刻**排下一次（`handler.postDelayed`）。当发现链接过期并调用 `generate()` 重新生成时，`onGenerated` 又立即 `handler.post(pollTask)`，而旧的那个 delayed 定时器**还在队列里** → 两个循环并行 |
| **修法** | ①**世代号** `generation`：每次重新生成 +1，回调执行前先比对自己的世代号；②下一次轮询**只在收到本次结果之后**才安排，天然不重叠 |
| **位置** | `ui/auth/QrLoginActivity.java` |

### B 类：功能不工作或行为错误（severity 中）

#### B1. Android 13+ 通知栏没有播放控制条

| | |
|---|---|
| **症状** | 音乐能放，但通知栏里没有控制条；锁屏也没有 |
| **根因** | `AndroidManifest.xml` 声明了 `POST_NOTIFICATIONS`，但**代码里从未申请**。Android 13（API 33）起这是运行时权限，不申请就拿不到 |
| **修法** | `MainActivity.onCreate` 里按需申请（仅 Android 13+，已授权则不重复弹） |
| **位置** | `ui/MainActivity.java` → `requestNotificationPermissionIfNeeded()` |

#### B2. 退出登录后界面仍显示已登录

| | |
|---|---|
| **症状** | 点"退出登录"，Toast 弹了，但昵称头像还在 |
| **根因** | `AuthManager.logout()` 是异步的（要先通知 B 站作废凭证）。原代码调用后立刻 `refresh()`，那时本地 Cookie 还没清掉 |
| **修法** | `logout(Runnable onDone)` 带完成回调，界面在回调里刷新 |
| **位置** | `auth/AuthManager.java` + `ui/me/MeFragment.java` |

#### B3. 用户信息缓存丢失后用 mid=0 请求收藏夹

| | |
|---|---|
| **症状** | 收藏夹标签提示"未获取到用户信息"或接口报错 |
| **根因** | 收藏夹接口需要用户 uid，原代码直接取 `cachedMe.mid`；缓存丢失（清数据、升级）时它是 0，B 站直接拒绝 |
| **修法** | `LibraryFragment.loadFolders()` 先检查 mid，缺失时先 `AuthManager.refreshProfile()` 补回来再请求 |
| **位置** | `ui/library/LibraryFragment.java` |

#### B4. 收藏夹内容里按返回键会退出 App

| | |
|---|---|
| **症状** | 在某个收藏夹的歌单里按返回，直接退出了 App，而不是回到收藏夹列表 |
| **根因** | 没有拦截返回键，系统默认行为就是关 Activity |
| **修法** | 注册 `OnBackPressedCallback`，**只在夹内时**启用它（`setEnabled(true)`），返回列表后禁用 |
| **位置** | `ui/library/LibraryFragment.java` |

#### B5. 列表图片错位

| | |
|---|---|
| **症状** | 快速滚动时，某行的封面是别的歌的 |
| **根因** | RecyclerView 复用 itemView。上一行发起的图片请求返回时，这个视图可能已经绑给了另一首歌 → 图片贴错行 |
| **修法** | `ImageLoader.load()` 返回 `Request`；每个 Holder 记住自己的请求，**重新绑定前**和 `onViewRecycled` 时调 `cancel()`。被取消的请求即使图片已下载完也不会回调 |
| **位置** | `util/ImageLoader.java` + `ui/widget/SongAdapter.java` + `ui/library/FavFolderAdapter.java` |

#### B6. 同一张封面被并发下载多次

| | |
|---|---|
| **症状** | 流量浪费；首屏加载慢 |
| **根因** | 同一个 URL 在短时间内被多个 item 请求时，每次都发起一次独立下载 |
| **修法** | `ImageLoader` 用 `IN_FLIGHT` 表记录"这个地址正在下载"。后来的请求只登记等待，下载完成后统一分发给所有等待者 |
| **位置** | `util/ImageLoader.java` |

#### B7. 进度条定时器在后台仍运行

| | |
|---|---|
| **症状** | 切到后台后仍每 500ms 唤醒 CPU 一次，白耗电 |
| **根因** | 定时器在 `onCreate` 启动后就没停过 |
| **修法** | `onResume` 启动、`onPause` 停止 |
| **位置** | `ui/player/NowPlayingActivity.java` |

#### B8. 图片磁盘缓存无限增长

| | |
|---|---|
| **症状** | 缓存目录越用越大 |
| **根因** | 只写不删 |
| **修法** | 超过 64 MB 时按最后修改时间删掉最旧的一半 |
| **位置** | `util/ImageLoader.java` → `trimDiskCacheIfNeeded()` |

#### B9. 封面地址为空时拼出非法 URL

| | |
|---|---|
| **症状** | 无封面的条目会去请求一个形如 `@320w_200h.webp` 的非法地址，白费一次网络 |
| **根因** | `coverSmall()` 无条件在 `coverUrl()` 后面拼裁剪参数 |
| **修法** | `cover` 为空时返回空串，让 `ImageLoader` 走"没有图片"分支 |
| **位置** | `model/Song.java` |

#### B10. 切歌时在主线程写文件

| | |
|---|---|
| **症状** | 切歌瞬间界面可能卡顿 |
| **根因** | `onMediaItemTransition` 在播放器回调线程（主线程）里调用 `MusicStore.addHistory()`，而写文件是同步的 |
| **修法** | 内存更新仍在主线程（很快），写文件丢给 `AppExecutors.store()` 单线程后台执行 |
| **位置** | `store/MusicStore.java` |

### C 类：健壮性与可维护性（severity 低，但影响排查效率）

| # | 问题 | 修法 |
|---|---|---|
| C1 | `MusicStore.isFavorite()` 遇到 `bvid` 为 null 会 NPE | 空值安全判断 |
| C2 | `BiliHttp` 在 4xx 时用 `getInputStream()`，抛出的 `FileNotFoundException` 里既没有状态码也没有响应体，根本没法排查 | 先 `getResponseCode()`；≥400 时改读 `getErrorStream()`，抛带状态码 + 响应体片段的 `BiliException` |
| C3 | 播放队列解析自己数 `CountDownLatch` 且嵌套提交到同一个线程池，顺序映射靠手算 | 改用 `ExecutorService.invokeAll(tasks, 20s)`：顺序由 API 保证，超时自动取消 |
| C4 | 播放队列弹窗关闭时手动调用界面的 `onPlayerChanged()`（hack），因为队列增删后界面收不到通知 | `PlayerConnection` 增加 `onTimelineChanged` 监听，删掉 hack |
| C5 | `AuthManager` 每次请求 `new Thread()` | 改用 `AppExecutors.auth()` 单线程池 |
| C6 | `CookieStore.init()` 被调用了两次（`BiliMusicApp` 里一次，`AuthManager.init` 内部又一次） | 只在 `AuthManager.init` 里调，并在注释里说明 |
| C7 | 进度条刻度 `1000` 是魔法数字，靠"布局里也写了 1000"隐式约定 | 提成 `SEEK_BAR_MAX` 常量并注明必须与布局一致 |
| C8 | 各处散落的魔法数字（超时、上限、间隔） | 全部提成命名常量，并在 [02 篇](02-architecture.md#61-各种上限) 汇总成表 |

### D 类：删掉的死代码

这些代码**没有任何调用者**，删掉不影响功能：

| 位置 | 内容 |
|---|---|
| `BiliHttp.getStrict()` | 从未被调用 |
| `BiliApi.debugHeaders()` | 调试残留 |
| `RecommendData.Owner` | 未映射的嵌套类 |
| `QrLoginActivity.this2()` | 一个只返回 `callback` 的无意义方法 |
| `MainActivity.newOwningIntent()` | 从未被调用 |
| `MusicRepository.ensureCid()` / `resolveAudio()` | 从未被调用（播放走的是 `PlaybackService` 那条路） |
| `WbiSigner.TAB_V2` | **永远走不到**（见下） |
| `Song implements Serializable` | 序列化走的是 Gson，不是 Java 序列化 |

### E 类：构建与环境

#### E1. `build.ps1` 硬编码了 Gradle 发行版的哈希目录

| | |
|---|---|
| **症状** | 换一台机器、或 Gradle 版本一升级，脚本立刻报"本地 Gradle 未找到"，然后所有人都在"环境问题"上卡住 |
| **根因** | 脚本里写死了 `D:\Android\AndroidGradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\...`，那个 `arn2…` 是 Gradle 自己生成的随机目录名 |
| **修法** | 改用项目自带的 `gradlew.bat`（版本由 `gradle-wrapper.properties` 固定），并自动查找 JDK（`JAVA_HOME` → Android Studio 的 JBR → 常见安装路径） |
| **位置** | `build.ps1` |

> 旧的 `ARCHITECTURE.md` 里写着"命令行 gradlew 有 native lock 冲突问题，建议用 Android Studio 构建"。
> 实际上问题不在这里：一是脚本用了写死的 Gradle 路径，二是 Android Studio 开着时会和命令行抢文件锁。
> **关掉 Android Studio 后，命令行构建完全正常**（本次重构已实测通过）。

#### E2. `build.ps1` 没有 UTF-8 BOM，Windows PowerShell 5.1 会读成乱码

| | |
|---|---|
| **症状** | 运行脚本报 `Missing closing '}'` / `The string is missing the terminator` 这类语法错误，但文件看起来完全正常 |
| **根因** | 没有 BOM 的 UTF-8 文件，Windows PowerShell 5.1 会按**系统 ANSI 代码页**（中文系统是 GBK）解码。中文注释与提示文字被解成乱码，乱码里可能包含引号类字符，于是把语法结构撑破 |
| **修法** | 把 `build.ps1` 保存为**带 BOM 的 UTF-8**（文件前三个字节是 `EF BB BF`） |
| **位置** | `build.ps1` |

> **改这个文件的注意事项**：如果编辑后保存成"无 BOM 的 UTF-8"，问题会立刻复现。
> 用 VS Code 时右下角要选 `UTF-8 with BOM`。

#### E3. PowerShell 默认不允许运行未签名的 `.ps1`

**症状**：`无法加载文件 build.ps1。未对文件进行数字签名。`

这是 PowerShell 的执行策略（本机为 `RemoteSigned`）导致的，**不是脚本的问题**。
两种解决办法：

```powershell
# 方法一（推荐）：用项目根目录的 build.cmd，它内部已经带上了 Bypass
.\build.cmd

# 方法二：显式绕过执行策略
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

> 旧的 README 写的是 `build.ps1 -VerboseBuild`：
> `-ExecutionPolicy Bypass` 这半句是**必需的**，
> 而 `-VerboseBuild` 这个参数**脚本根本不支持**（传进去会被当成未知参数）。
> 现在两个问题都修好了：`build.cmd` 负责绕过策略，参数走 `-Task` / `-GradleArgs`。

#### E4. `tools/api-probe/_04_WbiVerify.java` 根本编译不过

| | |
|---|---|
| **症状** | `javac` 报找不到符号 |
| **根因** | 它 `import com.bilimusic.app._01_net.WbiSigner;` —— 这个包名**在当前源码里不存在**（只在旧的构建缓存目录里残留过） |
| **修法** | 改为引用真实存在的 `com.yuuuno224.bilimusic.net.WbiSigner`，并扩充成完整的对照验证程序。**已实测通过** |
| **位置** | `tools/api-probe/_04_WbiVerify.java` |

#### E5. 混淆 keep 规则指向了已经不存在的包

| | |
|---|---|
| **症状** | 一旦开启 `minifyEnabled true`，release 包会"能装但数据全空" |
| **根因** | DTO 从 `model` 包移到了 `net.dto`，而 `proguard-rules.pro` 只保留 `model.**` |
| **修法** | 规则更新为同时保留 `net.dto.**`、`model.**`、`MusicStore$StoreData`，并补充泛型签名/注解的保留说明 |
| **位置** | `app/proguard-rules.pro` |

---

## 第二部分：旧文档的错误清单

这是**逐条对照代码核实过**的。区分"确实是错的"和"因重构而过期的"。

### 确实是错的（4 条）

| # | 旧文档原文 | 实际情况 | 影响 |
|---|---|---|---|
| 1 | `ST_EXPIRED（86091 过期）` | 正确值是 **86038**（`model/LoginState.EXPIRED`） | 排查"登录不成功"时会查错方向 |
| 2 | "WBI 签名的 mixinKey 混淆表历史上存在 64 位（V1）和 84 位（V2）两个版本，`mixinKey()` 按长度自适应选择" | 表中确实有两份数据，但 `TAB_V2` 只有 **64 项**（不是 84），且判别条件是 `joined.length() > 64`，而 `imgKey + subKey` **恒为 64 个字符** → 那段代码**永远走不到** | 让人以为签名逻辑有两套分支，读代码时白找半天 |
| 3 | README："扫码登录：生成授权**二维码**，用 B 站 APP **扫码**确认" | 本项目**不生成二维码图片**（登录页中间那张图是启动图标占位）。它生成的是一个**授权链接**，用户点链接跳到 B 站 App 确认 | 与实际功能不符；也和同一份 `ARCHITECTURE.md` 里的描述自相矛盾（那里写的是"授权链接"） |
| 4 | README：`build.ps1 -VerboseBuild` | 脚本不支持 `-VerboseBuild`；而且少了必需的 `-ExecutionPolicy Bypass` | 照着做会直接失败 |

### 因重构而过期的（不算错，但已不适用）

| 旧文档内容 | 现状 |
|---|---|
| 目录树里 `store/Song.java` | `Song` 已移到 `model/`，`store/` 只剩 `MusicStore` |
| 目录树里没有 `core/`、`net/api/`、`net/dto/` | 新增了这三层 |
| 线程表里的 `ImageLoader-POOL`、`auth-io 每次新建` | 线程池已统一收敛到 `core/AppExecutors` |
| 附录把 `MusicRepository.resolveAudio()` / `ensureCid()` 列为"核心方法" | 这两个方法没有任何调用者，属死代码，已删除 |
| `PlaybackService.songOf()` 作为反序列化入口 | 已移到 `MediaItemFactory.songOf()` |
| "为什么 PlaybackService 自己调 BiliApi 而不走 MusicRepository" | 结论仍成立，但 `BiliApi` 已拆成 `net/api` 下四个类 |
| 无 `03`~`07` 分篇，全部塞在单篇 600 多行的文档里 | 已拆成 7 篇 + 索引 |

### 核对后确认**是正确**的（保留原有判断）

不为了让"文档有错"这个说法成立而硬凑。以下内容旧文档写对了：

- 依赖清单（appcompat 1.6.1 / material 1.10.0 / constraintlayout 2.0.1 / recyclerview 1.3.2 / viewpager2 1.0.0 / gson 2.11.0 / media3 1.5.1）
- 构建参数（compileSdk `android-36.1` / minSdk 26 / targetSdk 35 / Java 17 / ABI 分包无 universal APK）
- 权限清单（5 个权限）
- 存储位置（`music_store.json` / `bili_cookies` → `cookies_json` / `bili_auth` → `me` / `{cacheDir}/covers/{md5(url)}`）
- 配色（`bg=#121212`、`accent=#1DB9C4`）
- 音频流选择优先级（DASH audio 最大 id → FLAC → durl 兜底）
- 收藏夹自动翻页上限 10 页（200 条）
- 播放历史上限 200、搜索历史上限 10

> 另外，旧 `ARCHITECTURE.md` §3.3 把 `CookieStore.init(ctx)` 画成了 `BiliMusicApp` 的独立初始化步骤。
> 这一条**文档没错**——是当时的代码真的重复初始化了两次。
> 属于"实现冗余"，已在重构中清理。

---

## 第三部分：Android 新手最容易混的概念

这些概念在本项目里都有具体体现，对照着看最容易懂。

### 3.1 主线程 / 子线程到底怎么分

**唯一的规则**：只有主线程能改界面。

| 做的事情 | 能在主线程做吗 | 本项目怎么做 |
|---|---|---|
| 发网络请求 | ❌ 会 ANR | `AppExecutors.io()` 等线程池 |
| 读写大文件 | ❌ 会卡顿 | `AppExecutors.store()` / `image()` |
| 解析 JSON | ⚠️ 小数据可以，大数据别 | 在后台线程做 |
| `setText` / `notifyDataSetChanged` | ✅ 必须 | `AppExecutors.runOnMain()` |
| 调 ExoPlayer 的 API | ✅ 必须 | 由 `PlayerConnection` 保证 |

**最常见的错误写法**：

```java
// ❌ 在主线程直接请求；在子线程直接改界面
new Thread(() -> {
    String body = BiliHttp.get(url);
    textView.setText(body);      // 崩！子线程不能碰 View
}).start();
```

### 3.2 Activity / Fragment 的生命周期为什么重要

一个异步操作从发起到回调，中间用户完全可能已经离开了这个页面。
**回调到达时页面可能已经死了。**

```java
// Fragment 里的判断写法
private boolean isViewUsable() {
    return isAdded() && getView() != null;
}
```

- `isAdded()`：Fragment 是否还挂在 Activity 上
- `getView()`：Fragment 的视图是否还没被销毁

Activity 里对应的是 `isFinishing() || isDestroyed()`。

**记住**：`onCreate` 里注册的东西，必须在对应的销毁回调里注销；
忘记注销的监听器会持有已销毁的界面 → **内存泄漏**。

本项目需要注销的清单：

| 注册 | 注销位置 |
|---|---|
| `PlayerConnection.addListener(this)` | `onDestroy` |
| `SongAdapter`（构造时自动注册） | `adapter.release()`，在 `onDestroyView`/`onDestroy` |
| `ImageLoader.Request` | `request.cancel()` |
| `OnBackPressedCallback` | 用 `getViewLifecycleOwner()` 注册，自动注销 |
| `Handler.postDelayed` | `handler.removeCallbacks(...)` |

### 3.3 为什么参数里有个 `Context`，到处都在传

`Context` 是"当前所处环境"的句柄，用来拿资源、开文件、启动组件。
两个容易混的点：

- **`this`**（Activity 里的 Context）：生命周期跟 Activity，**不能长期持有**
- **`getApplicationContext()`**：跟进程，可以长期持有

**规则**：凡是会被静态变量或长生命周期对象持有的 Context，
一律用 `getApplicationContext()`。本项目的 `AuthManager`、`MusicStore`、
`ImageLoader`、`CookieStore` 都是这么做的。

### 3.4 为什么 `Song` 要放进 `Bundle` 还得转 JSON

`Bundle` 只支持 Parcelable / Serializable / 基本类型。
`Song` 是个普通 POJO，最省事的做法是转成 JSON 字符串放进去：

```java
bundle.putString("songs", Json.toJson(songs));       // 发送端
List<Song> songs = Json.fromJsonOrNull(json, TYPE);  // 接收端
```

替代方案是让 `Song` 实现 `Parcelable`（要手写一堆读写代码，
或者用 `@Parcelize` 注解——那是 Kotlin 的）。对 10 个字段的类来说，
JSON 方案更省事，代价是每次传递有一次序列化开销。

### 3.5 为什么需要 `MediaSession`，不能直接调 ExoPlayer

因为播放器在 Service 里，界面在进程的另一头（概念上）。
两者不能直接互相持有引用，否则界面一销毁播放器就崩。

`MediaSession` 是 Media3 提供的"标准插座"：Service 端插上 ExoPlayer，
界面端用一个 `MediaController` 遥控它。好处是：

- 界面死了，播放器不受影响
- 通知栏、锁屏、蓝牙耳机、Android Auto 都能通过同一套标准接口控制它

代价是通信是异步的（连接要握手），且只能传"标准命令"——
自定义操作得自己注册 `SessionCommand`（本项目注册了 3 条）。

### 3.6 `RecyclerView.Adapter` 到底该怎么写

三个必须理解的点：

1. **`onBindViewHolder` 会被反复调用**（滚动时视图被复用）。
   所以它里面不能做"只该做一次"的事。
2. **不要用构造时的 `position`**。列表可能已经变了，
   用 `holder.getBindingAdapterPosition()` 并判断 `NO_POSITION`。
3. **不要一有变化就 `notifyDataSetChanged()`**。
   它会重建整个列表。播放状态变化只影响两行，用 `notifyItemChanged(index)`。

### 3.7 什么是"合并刷新"，什么时候该用

播放器一次切歌会连续触发好几个回调。如果每个都刷一遍界面，
一次切歌就要刷三遍。做法是：

```
第一个回调 → 置标记位、投递一个主线程任务
后续回调   → 发现标记位已置，直接返回（丢弃）
任务执行   → 复位标记位，真正通知一次
```

适用场景：**事件频率高于界面刷新需求**。
本项目除了播放器状态，没有别处需要它。

### 3.8 静态字段什么时候是坏的

`static` 会让对象的生命周期变成"进程级"。两种情况要小心：

| 情况 | 后果 | 本项目怎么做 |
|---|---|---|
| 静态字段持有 Activity / View | 内存泄漏 | 全部用 `getApplicationContext()`；监听器一定注销 |
| 静态字段当"缓存"用 | 多实例互相污染 | `SearchFragment` 的热词缓存是有意为之（见 `03` 篇注释），代价已知并接受 |

---

## 第四部分：已知限制

**这份清单是刻意写全的**——知道边界在哪，比以为它什么都能做更有用。

### 功能层面

| # | 限制 | 说明 | 涉及模块 |
|---|---|---|---|
| 1 | 搜索结果无分页 | 固定取第 1 页（20 条），没有"加载更多" | `SearchFragment` + `SearchApi` |
| 2 | 收藏夹最多 200 条 | `FavoriteApi.MAX_PAGES = 10`，超过的看不到 | `FavoriteApi` |
| 3 | 无歌词 | 纯音频播放，没有歌词同步 | 需新建模块 |
| 4 | 无音质选择 | 自动取当前账号权限内的最高音质 | `PlaybackApi` + 播放页 |
| 5 | 无离线下载 | 纯在线播放 | 需 `ExoPlayer` 缓存 + 新存储 |
| 6 | 无播放进度记忆 | 杀进程后重开，不恢复上次播到哪 | `MusicStore` + `PlaybackService` |
| 7 | 播放队列不持久化 | 进程被杀后队列清空 | `PlaybackService` |
| 8 | 多 P 视频只取第一个 cid | `VideoDetailDto` 没用 `pages` 字段 | `PlaybackApi` |
| 9 | 登录是"授权链接"而非二维码图片 | 需要用户点链接跳到 B 站 App；不能拿别的设备扫 | `QrLoginActivity` |
| 10 | 搜索首页无下拉刷新 | 靠 10 分钟缓存自动更新 | `SearchFragment` |
| 11 | 不支持多账号切换 | 只有"登录/退出" | `AuthManager` |
| 12 | 无 MV / 视频画面 | 只播音频轨 | `PlaybackService` |
| 13 | 不能删除/新建 B 站收藏夹 | 只读浏览 | `FavoriteApi` |
| 14 | 不能"下一首播放"/"加入队列"（有实现无入口） | `PlayerConnection.playNext()` / `addToQueue()` 已实现，但界面上没有按钮 | 界面 |

### 工程层面

| # | 限制 | 说明 |
|---|---|---|
| 15 | 没有单元测试 | 大量静态方法 + Android 依赖，难以直接测。只有 `tools/api-probe` 这类手工探针 |
| 16 | `model` 字段全是 public | 没有封装、没有校验。Gson 直接反射赋值，改起来方便但也容易写出非法状态 |
| 17 | 无依赖注入 | 全部静态单例，替换实现困难 |
| 18 | 无 ViewModel / LiveData | 页面数据在配置变化后靠重新请求恢复 |
| 19 | Release 未开启混淆 | `minifyEnabled false`；规则已备好（`proguard-rules.pro`）但未验证 |
| 20 | 界面文案硬编码在 Java 里 | 只有 11 条在 `strings.xml`，无法做国际化 |
| 21 | `PlayerConnection` 是长生命周期静态单例 | 是刻意设计（避免反复握手），但必须显式清理监听器 |
| 22 | `tools/api-probe` 未纳入构建 | 需要手工 `javac` 编译，且不随项目代码更新 |
| 23 | `player` 包引用了 `ui.player.NowPlayingActivity` | 为构造通知的 `PendingIntent`，是已知的合理破例（见 02 篇 3.1） |
| 24 | 搜索首页缓存用 `private static` | Fragment 重建后仍复用；多实例场景下会互相看到缓存 |

### 改进路线（按性价比排序）

| 优先级 | 方向 | 涉及文件 | 大致工作量 |
|---|---|---|---|
| 高 | 搜索结果分页（"加载更多"） | `SearchFragment` + `SearchApi` + `MusicRepository` | 小 |
| 高 | 给"下一首播放/加入队列"加入口（长按菜单） | `SongAdapter` + `PlayerConnection` | 小 |
| 高 | 播放错误时给用户可见提示（现在是静默重试） | `PlaybackService` | 小 |
| 中 | 播放进度记忆（记住每首歌播到哪） | `MusicStore` + `PlaybackService` | 中 |
| 中 | 把界面硬编码文案搬进 `strings.xml` | 所有 `ui/` | 中 |
| 中 | 音质选择（在设置里给几档） | `PlaybackApi` + 新增设置页 | 中 |
| 中 | 开启并验证代码混淆 | `app/build.gradle` + `proguard-rules.pro` | 中（需真机回归） |
| 低 | 歌词显示 | 新建模块 | 大 |
| 低 | 离线下载 | `ExoPlayer` 缓存 + 新存储 | 大 |
| 低 | 引入 ViewModel + Repository 接口以支持测试 | 几乎全部 | 大 |

---

## 第五部分：FAQ

### Q1：搜索时报"HTTP 412"怎么办？

触发 B 站风控了。等几分钟再试。
本项目已经把所有用户触发的网络请求放在**单线程**（`bili-io`）串行执行，
就是为了避免这个。如果你新加了并发请求，把它去掉。

### Q2：为什么第一首歌点下去要等一下才响？

因为要先把**整条队列**的音频地址都解析出来（20 首 = 20 次请求，
虽然并发 6 路，仍需要时间）。这样做是为了让通知栏的上一首/下一首、
列表循环模式立刻就是正确的。

想加快，见 [06 篇 3.2](06-development-guide.md#32-改队列解析策略)。

### Q3：为什么 B 站收藏夹里的歌点了能立刻播，搜索结果的歌慢一点？

因为收藏夹接口**直接返回 cid**，而搜索接口不返回。
搜索来的歌必须先调一次"视频详情"拿 cid，多一次网络往返。
见 [05 篇 4.6](05-bilibili-api.md#46-视频详情取-cid)。

### Q4：`Song.equalus()` 为什么只比 bvid？

见 `model/Song.java`。这样"同一首歌"在收藏和历史里去重、在队列里判断是否当前播放，
都不需要写额外的比较逻辑。代价是：如果同一个 bvid 的标题变了（UP 主改了标题），
旧对象不会被自动更新。

### Q5：改了 `MusicStore` 的字段，老用户的数据会丢吗？

- **加字段**：安全。旧 JSON 里没有这个键，Gson 会留默认值。
- **改字段名**：**会丢**。给新字段加 `@SerializedName("旧名字")` 可以兼容。
- **改文件结构**：会丢。

详见 [06 篇 4](06-development-guide.md#4-改本地存储)。

### Q6：为什么登录页不显示二维码？

因为不需要。B 站返回的授权链接本身就能被 B 站 App 识别，
用户点一下就直接跳到确认页，比"生成二维码 + 用另一台设备扫"少一步。
详见 [04 篇 3](04-core-flows.md#流程-3登录)。

### Q7：`PlayerConnection.get()` 可能返回 null，怎么写得优雅？

```java
Player player = PlayerConnection.get();
if (player == null) {
    return;      // 还没连上，或者用的是不需要播放器的界面
}
```

这是刻意的：连接是异步的，界面可能比播放器先就绪。
所有调用点都做了判空（`toggle()` / `next()` / `prev()` / `currentSong()` 内部都判了）。

### Q8：为什么 `ImageLoader.load()` 要返回一个 `Request`？

为了能取消。RecyclerView 复用视图时，上一行的图片请求如果还在路上，
回来时会贴到错误的行上。`cancel()` 能挡住它。
详见 [04 篇 5.2](04-core-flows.md#52-为什么必须可取消)。

### Q9：我把 `build.ps1` 改动之后脚本报语法错误了，为什么？

大概率是保存成了**无 BOM 的 UTF-8**，Windows PowerShell 5.1 会按 GBK 解码导致乱码。
用 VS Code 右下角选 `UTF-8 with BOM` 重新保存。
详见本篇 E2。

### Q10：`clean` 之后重新构建变慢了是正常的吗？

正常。`clean` 会删掉 `app/build` 下所有中间产物，下次要重新编译打包。
不 `clean` 的话，Gradle 的增量编译通常只要几秒到十几秒。

### Q11：能不能用 Kotlin 重写？

可以，但没必要。这个项目的价值在于"分层清晰、依赖最少、每一步都能看懂"，
语言换成 Kotlin 会少写很多样板代码（尤其是 `Parcelable`、`findViewById`），
但架构上的取舍（不用 ViewModel、不用 DI）如果不改，可读性提升有限。
真想重写，建议先补上 ViewModel + 依赖注入，那是真正的现代化。

### Q12：这个 App 会收到 B 站的律师函吗？

本项目只调用 B 站的公开 web 接口，不绕过付费内容、不破解 DRM，
音频地址是服务端按当前账号权限正常下发的。
但**技术上合规不等于法律上没风险**——请仅用于个人学习，
不要分发、不要商用，并遵守 B 站的服务条款。

---

## 最后

发现问题时的正确顺序：

1. 先看 [03 篇](03-code-map.md) 找到相关类
2. 再看 [04 篇](04-core-flows.md) 确认它属于哪条流程
3. 用 `adb logcat -s AndroidRuntime:E` 抓崩溃堆栈
4. 回来查本篇前四部分
5. 还是没有 → 在文档里补一条（这也是这份文档继续有用的方式）

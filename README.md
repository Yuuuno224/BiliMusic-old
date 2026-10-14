# BiliMusic

> 把 B 站视频当音乐听的 Android 播放器。纯客户端，不需要任何后端服务。

BiliMusic 拿 B 站公开视频的**音频流**来播放（不要画面），提供接近主流音乐 App 的体验：
搜索、播放队列、后台播放、通知栏控制、收藏、播放历史、B 站收藏夹浏览。

它同时也是一个**教学向的 Android 示例项目**：刻意不引入 Retrofit / OkHttp / Room / Glide，
用标准库和少量自写组件把每一层都摊开给你看。如果你正在学 Android，
建议从 [`docs/`](docs/README.md) 的阅读路线开始。

---

## 功能一览

| 功能 | 说明 |
|---|---|
| 授权链接登录 | 生成一个 B 站授权链接，点它跳到 B 站 App 确认，自动获取登录凭证 |
| 搜索 | 关键词搜索 B 站视频；请求带 WBI 签名；记录本地搜索历史 |
| 搜索首页 | 热门搜索词（B 站热搜接口）+ 音乐区排行榜 + 最近播放 |
| 播放 | DASH 音频流式播放；后台播放；通知栏控制；自动连播 |
| 播放队列 | 底部弹窗查看队列，可点击跳转、删除 |
| 沉浸式播放页 | 大封面、进度拖拽、上一首/下一首、三档循环模式 |
| 乐库 | 我喜欢 / 最近播放 / B 站收藏夹（两级导航，需登录） |
| 迷你播放条 | 底部常驻，快捷收藏、播放暂停、打开播放队列 |
| 我的 | 账号信息、快捷入口、GitHub 仓库、关于 |
| 深色主题 | Spotify 风格深色 UI，亮青点缀 |

> **关于"登录方式"**：本项目**不自己绘制二维码图片**。
> B 站返回的授权链接本身就能被 B 站 App 识别，用户点一下即可确认登录。
> 单机使用场景下这比"生成二维码 + 用另一台设备扫"少一步。
> 详见 [04-core-flows.md](docs/04-core-flows.md#流程-3登录)。

---

## 技术栈

| 层 | 方案 | 版本 |
|---|---|---|
| 语言 | Java | 源码级别 17（编译需要 JDK 21） |
| 构建 | AGP + Gradle | 9.2.1 + 9.4.1 |
| SDK | compileSdk / minSdk / targetSdk | `android-36.1` / 26 / 35 |
| 播放 | Media3 ExoPlayer + MediaSession | 1.5.1 |
| 网络 | `HttpURLConnection` + Gson | gson 2.11.0 |
| 存储 | JSON 文件 + SharedPreferences | 无数据库 |
| 图片 | 自写 `ImageLoader`（内存 LRU + 磁盘缓存） | 无第三方库 |
| UI | Material Components | 1.10.0 |
| 容器 | AppCompat / RecyclerView / ConstraintLayout | — |

**刻意不引入的依赖**：Retrofit、OkHttp、Room、Glide、Coil、Hilt。
目的是保持依赖最小、每一层都能读懂；代价是没有连接池、没有查询优化、没有图片变换。

---

## 快速开始

```powershell
# 1. 构建 Debug APK（脚本会自动定位 JDK）
.\build.cmd

# 2. 产物
#    app\build\outputs\apk\debug\app-arm64-v8a-debug.apk      ← 现代手机装这个
#    app\build\outputs\apk\debug\app-armeabi-v7a-debug.apk    ← 老旧 32 位设备
```

> **为什么用 `build.cmd` 而不是 `build.ps1`**：
> Windows PowerShell 默认的执行策略不允许运行未签名的 `.ps1` 脚本，
> 直接跑 `.\build.ps1` 会报"未对文件进行数字签名"。
> `build.cmd` 是一个很短的批处理包装（内部就两行命令），已经带上了
> `-ExecutionPolicy Bypass`，双击即可用。
> 想直接调 PowerShell 脚本就显式绕过策略：
>
> ```powershell
> powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
> powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 release
> powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Task release -GradleArgs '--stacktrace'
> ```

- 需要 **JDK 21**（推荐直接用 Android Studio 自带的 JBR，`build.ps1` 会自动找到它）
- 需要 **Android SDK**，路径写在 `local.properties` 的 `sdk.dir`（该文件不入库）
- 也可以用 Android Studio 直接打开项目，Build → Make Project

完整的环境准备、安装到手机、常见报错排查见
[**01-getting-started.md**](docs/01-getting-started.md)。

---

## 文档

推荐按顺序阅读，也可以直接跳到你需要的那一篇：

| 文档 | 内容 | 适合谁 |
|---|---|---|
| [docs/README.md](docs/README.md) | 文档索引与阅读路线 | 第一次来 |
| [01-getting-started.md](docs/01-getting-started.md) | 装环境、编译、安装、排查报错 | 想把它跑起来 |
| [02-architecture.md](docs/02-architecture.md) | 分层架构、依赖方向、数据流 | 想理解整体设计 |
| [03-code-map.md](docs/03-code-map.md) | 目录与代码地图，每个类干什么 | 想找代码在哪 |
| [04-core-flows.md](docs/04-core-flows.md) | 搜索 / 播放 / 登录 / 收藏 / 图片 五条核心流程 | 想知道"怎么跑通的" |
| [05-bilibili-api.md](docs/05-bilibili-api.md) | B 站接口清单、WBI 签名、风控应对 | 想调接口 |
| [06-development-guide.md](docs/06-development-guide.md) | 常见改动怎么做（加接口 / 加页面 / 改播放） | 想动手改 |
| [07-faq-and-pitfalls.md](docs/07-faq-and-pitfalls.md) | 踩坑记录、FAQ、已知限制 | 卡住了 / 想知道缺什么 |

---

## 项目结构（速览）

```
app/src/main/java/com/yuuuno224/bilimusic/
├── BiliMusicApp.java         Application 入口，初始化全局组件
├── core/AppExecutors.java    全 App 的线程总控（5 个线程池 + 主线程）
├── net/                      网络层：传输、Cookie、签名、请求前置准备
│   ├── api/                  按业务拆分的接口封装（搜索/播放地址/登录/收藏）
│   └── dto/                  B 站 JSON 的一比一镜像（只映射字段，不含逻辑）
├── model/                    应用自己的领域模型（Song 等），不依赖任何层
├── store/MusicStore.java     本地 JSON 存储：收藏 / 播放历史 / 搜索历史
├── auth/AuthManager.java     登录态管理
├── repo/MusicRepository.java 业务编排：后台执行 + 主线程回调
├── player/                   播放服务、界面侧控制器、MediaItem 构建
├── util/                     图片加载器、B 站文本清洗
└── ui/                       所有界面（Activity / Fragment / Adapter）
```

详细说明见 [03-code-map.md](docs/03-code-map.md)。

---

## 发布

项目配置了 GitHub Actions（[`.github/workflows/build-release.yml`](.github/workflows/build-release.yml)），
推送 `v*` 标签即自动构建各 ABI 的 APK 并发布 Release，Release 说明从 `CHANGELOG.md` 提取。

> CI 构建的是 **debug APK**（workflow 里跑的是 `assembleDebug`），
> 产物命名为 `BiliMusic-<tag>-<abi>-debug.apk`。
> 想发布正式签名包需要自己配 keystore 并把 workflow 改成 `assembleRelease`。

```bash
# 1. 在 CHANGELOG.md 顶部添加与版本号一致的段落，例如 ## [v1.1.0]
# 2. 打标签并推送
git tag v1.1.0
git push origin v1.1.0
```

> CI 用 `awk` 在 `CHANGELOG.md` 里找标题**以** `## [v1.1.0]` **开头**的段落
> （tag 名要带 `v` 前缀，且方括号里必须完全等于 tag 名）。
> 标题后面跟什么都可以——写 `## [v1.1.0] - 2026-01-01` 或 `## [v1.1.0] 修复若干问题` 都能命中。
> 真正被抽出来当 Release 说明的，是这个标题到下一个 `## [` 标题之间的全部内容。

---

## 协议

本项目仅供学习和个人使用。音源来自 B 站公开视频，请支持正版，并遵守 B 站相关服务条款。
